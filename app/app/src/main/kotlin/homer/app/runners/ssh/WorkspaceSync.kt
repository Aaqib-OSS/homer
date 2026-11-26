package homer.app.runners.ssh

import homer.app.infra.PathSafety
import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.time.Duration

/**
 * Recursive SFTP transfer over an authenticated [session], on a fresh SftpClient per operation: uploads before
 * a run, downloads after, removes the remote project root on cleanup. Every blocking round trip, the client's
 * close included, is bounded by [transferTimeout] against a peer that stalls SFTP data (CWE-400, ADR-0064).
 */
class WorkspaceSync(
    session: ClientSession,
    transferTimeout: Duration,
    private val maxDirEntries: Int = DEFAULT_MAX_DIR_ENTRIES,
    // Test seam: runs after a file's link checks and before its open, so a test can
    // swap the file for a symlink inside that window deterministically.
    internal val beforeLocalOpen: (Path) -> Unit = {},
    // Test seam: MINA's close only enqueues its packet, so no peer can stall the release; an injected
    // client is how a test proves the release is under the deadline.
    internal val openSftp: (ClientSession) -> SftpClient = { SftpClientFactory.instance().createSftpClient(it) },
) {
    companion object {
        const val DEFAULT_MAX_DIR_ENTRIES = 100_000

        private val NOFOLLOW_WRITE = PathSafety.NOFOLLOW_WRITE.toTypedArray()

        /**
         * Resolves a remote-supplied entry name against [localDir], accepting only a plain single-segment child: an
         * SFTP listing crosses the runner trust boundary, so `../x` or an absolute name is refused (CWE-22, ADR-0047).
         */
        internal fun containedChild(
            localDir: Path,
            filename: String,
        ): Path {
            if (!isPlainFileName(filename)) {
                throw SecurityException("Remote SFTP entry name '$filename' is not a plain file name")
            }
            val base = localDir.toAbsolutePath().normalize()
            val child = base.resolve(filename).normalize()
            if (child.parent != base) {
                throw SecurityException("Remote SFTP entry name '$filename' resolves outside '$localDir'")
            }
            return child
        }

        /** A single path segment: not empty, not `.` or `..`, and free of separators and NUL. */
        private fun isPlainFileName(filename: String): Boolean =
            filename.isNotEmpty() &&
                filename != "." &&
                filename != ".." &&
                '/' !in filename &&
                '\\' !in filename &&
                '\u0000' !in filename
    }

    private val logger = LoggerFactory.getLogger(WorkspaceSync::class.java)

    private val deadline = SftpTransferDeadline(session, transferTimeout, openSftp, logger)

    private suspend fun <T> withSftp(block: suspend (SftpClient) -> T): T = deadline.withSftp(block = block)

    private suspend fun <T> withTransferTimeout(block: suspend () -> T): T = deadline.withTransferTimeout(block = block)

    suspend fun mkdirs(remoteDir: String) = withSftp { sftp -> withTransferTimeout { sftp.mkdirsIdempotent(remoteDir) } }

    /** Writes [content] to a new owner-only file at [remote], in a parent directory kept owner-only. */
    suspend fun writePrivateFile(
        remote: String,
        content: ByteArray,
    ) = withSftp { sftp ->
        withTransferTimeout {
            sftp.ensurePrivateDir(remote.substringBeforeLast('/'))
            sftp.createPrivateFile(remote, content)
        }
    }

    /** Removes the file at [remote], a missing one included, within [timeout] rather than the transfer timeout. */
    suspend fun removeIfExists(
        remote: String,
        timeout: Duration,
    ) = deadline.withSftp(timeout) { sftp -> deadline.withTransferTimeout(timeout) { sftp.removeIfExists(remote) } }

    /** `lstat`: true when [remotePath] itself is a symbolic link; false when it does not exist. */
    suspend fun isSymlink(remotePath: String): Boolean =
        withSftp { sftp ->
            withTransferTimeout {
                try {
                    sftp.lstat(remotePath).isSymbolicLink
                } catch (e: SftpException) {
                    if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE || e.status == SftpConstants.SSH_FX_NO_SUCH_PATH) false else throw e
                }
            }
        }

    suspend fun uploadDir(
        localDir: Path,
        remoteDir: String,
        excludes: List<String> = emptyList(),
    ) = withSftp { sftp ->
        // Same refusal as upload(): a symlinked root would walk the link's target (CWE-59).
        if (Files.isSymbolicLink(localDir)) throw SecurityException("upload source '$localDir' is a symlink")
        uploadDirInto(sftp, localDir, remoteDir, excludes, localDir, "", mutableSetOf(localDir.toRealPath()), 0, viaLink = false)
    }

    /**
     * Uploads an existing local file or directory to [remote]. In a directory a bare [excludes] name matches at
     * any depth and a leading `/` anchors it to the upload root; a single file gets its remote parent dirs created.
     */
    suspend fun upload(
        local: Path,
        remote: String,
        excludes: List<String> = emptyList(),
    ) = withSftp { sftp ->
        // CWE-59: a symlinked source is refused before any remote effect, since isDirectory()/isRegularFile()
        // follow links; parity with TarStreams.pack (ADR-0042).
        if (Files.isSymbolicLink(local)) throw SecurityException("upload source '$local' is a symlink")
        when {
            local.isDirectory() -> {
                uploadDirInto(sftp, local, remote, excludes, local, "", mutableSetOf(local.toRealPath()), 0, viaLink = false)
            }

            local.isRegularFile() -> {
                // CWE-59: the walk skips a hard link, but a source file named explicitly is refused, as in
                // TarStreams.pack, so it cannot vanish silently.
                if (PathSafety.isHardLinked(local)) throw SecurityException("upload source '$local' is hard-linked")
                remote.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { withTransferTimeout { sftp.mkdirsIdempotent(it) } }
                uploadFile(sftp, local, remote)
            }

            else -> {
                error("upload source does not exist: $local")
            }
        }
    }

    /**
     * Walks [localDir] on realpaths; [visited] (seeded with the root's realpath) and the [depth] cap make a walk
     * through in-tree symlinks terminate. [prefix] is built by concatenation, never `relativize`, which corrupts
     * anchored excludes below a link (ADR-0042).
     */
    private suspend fun uploadDirInto(
        sftp: SftpClient,
        localDir: Path,
        remoteDir: String,
        excludes: List<String>,
        root: Path,
        prefix: String,
        visited: MutableSet<Path>,
        depth: Int,
        viaLink: Boolean,
    ) {
        // Below the root the walk only ever descends on a checked realpath.
        val realDir = if (depth == 0) root.toRealPath() else localDir
        withTransferTimeout { sftp.mkdirsIdempotent(remoteDir) }
        TarStreams
            .walkEntries(localDir, prefix, excludes)
            .forEach { (entry, entryName) ->
                // CWE-59: a link escaping the root is skipped with a warning, never failed; a contained one is checked
                // and streamed at its target, since the link's own nlink is always 1. Parity with TarStreams (ADR-0042).
                val source = TarStreams.containedSource(entry, root)
                if (source == null) {
                    logger.warn("Skipping symlink '{}' escaping the upload root", entry)
                    return@forEach
                }
                val remoteChild = "$remoteDir/${entry.name}"
                when {
                    source.isDirectory() -> {
                        uploadDirEntry(sftp, entry, source, remoteChild, excludes, root, entryName, visited, depth, viaLink, realDir)
                    }

                    source.isRegularFile() -> {
                        if (PathSafety.isHardLinked(source)) {
                            // CWE-59: a hard link's other name may lie outside the root, so containment cannot be
                            // decided; skipped with a warning like an escaping symlink, as in TarStreams.
                            logger.warn("Skipping '{}': hard-linked file, its other name may be outside the upload root", entry)
                            return@forEach
                        }
                        uploadFile(sftp, source, remoteChild)
                    }
                }
            }
    }

    /** A directory entry of [uploadDirInto]'s walk: created and descended into within the walk's bounds. */
    private suspend fun uploadDirEntry(
        sftp: SftpClient,
        entry: Path,
        source: Path,
        remoteChild: String,
        excludes: List<String>,
        root: Path,
        entryName: String,
        visited: MutableSet<Path>,
        depth: Int,
        viaLink: Boolean,
        realDir: Path,
    ) {
        // Resolve every directory, symlink or not: an unresolved root (macOS /var -> /private/var) would let
        // `visited` hold two forms of one directory (ADR-0042).
        val real = runCatching { source.toRealPath() }.getOrNull()
        if (real == null || !real.startsWith(root.toRealPath())) {
            logger.warn("Skipping '{}': does not resolve inside the upload root", entry)
            return
        }
        // CWE-59: descend on the checked realpath, never the name, which a repointed link would lead out of the
        // root; a link into a directory already visited is skipped with a warning, as in TarStreams (ADR-0042).
        val throughLink = viaLink || real != realDir.resolve(entry.name)
        when {
            depth >= TarStreams.MAX_UPLOAD_DEPTH -> {
                logger.warn("Skipping '{}': upload nesting exceeds {} levels", entry, TarStreams.MAX_UPLOAD_DEPTH)
            }

            // Always add: a real directory's realpath must stop a later link to it.
            !visited.add(real) && throughLink -> {
                logger.warn("Skipping '{}': already uploaded as another path", entry)
            }

            else -> {
                uploadDirInto(sftp, real, remoteChild, excludes, root, entryName, visited, depth + 1, throughLink)
            }
        }
    }

    /**
     * Streams [local], already resolved by the caller, from one handle opened before any remote write. The mode
     * is read by path with `NOFOLLOW_LINKS`, as in `TarStreams.addFile`: java.nio reads no mode on a handle.
     */
    private suspend fun uploadFile(
        sftp: SftpClient,
        local: Path,
        remote: String,
    ) {
        beforeLocalOpen(local)
        // CWE-367: one open with NOFOLLOW_LINKS refuses a symlink swapped in after the caller's checks; an
        // ancestor swap remains (AR-04).
        FileChannel.open(local, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            // CWE-400: bound the transfer so a stalling peer cannot hold resources indefinitely.
            withTransferTimeout { sftp.write(remote).use { out -> Channels.newInputStream(channel).copyTo(out) } }
        }
        try {
            val attrs = withTransferTimeout { sftp.stat(remote) }
            attrs.permissions = TarStreams.posixToMode(local.getPosixFilePermissions(LinkOption.NOFOLLOW_LINKS))
            withTransferTimeout { sftp.setStat(remote, attrs) }
        } catch (e: IOException) {
            logger.warn("Failed to set permissions on uploaded file {}", remote, e)
        }
    }

    suspend fun downloadDir(
        remoteDir: String,
        localDir: Path,
        mode: DownloadMode,
    ) = withSftp { sftp ->
        if (!withTransferTimeout { remoteExists(sftp, remoteDir) }) return@withSftp
        val materialized = if (mode == DownloadMode.MIRROR) mutableSetOf<Path>() else null
        downloadDirInto(sftp, remoteDir, localDir, mode, localDir, materialized)
        // Prune only after the SFTP walk completes without error — never on a partial/failed
        // transfer or a missing remote dir (the early return above skips this entirely). ADR-0055.
        if (materialized != null) TarStreams.prune(localDir, materialized)
    }

    /**
     * Downloads a remote file or directory to [local]; a missing remote path is a no-op, as in [downloadDir].
     * [mode] reconciles remote with local content; a single file has no extras, so MIRROR acts as OVERWRITE.
     */
    suspend fun download(
        remote: String,
        local: Path,
        mode: DownloadMode,
    ) = withSftp { sftp ->
        if (!withTransferTimeout { remoteExists(sftp, remote) }) return@withSftp
        if (withTransferTimeout { sftp.stat(remote) }.isDirectory) {
            val materialized = if (mode == DownloadMode.MIRROR) mutableSetOf<Path>() else null
            downloadDirInto(sftp, remote, local, mode, local, materialized)
            if (materialized != null) TarStreams.prune(local, materialized)
        } else {
            if (mode == DownloadMode.OVERLAY && Files.exists(local)) return@withSftp
            downloadFile(sftp, remote, local)
        }
    }

    /** Streams [remote] to the file [local], refusing a symlink there, live, dangling or swapped in before the open. */
    private suspend fun downloadFile(
        sftp: SftpClient,
        remote: String,
        local: Path,
    ) {
        local
            .toAbsolutePath()
            .normalize()
            .parent
            ?.let { Files.createDirectories(it) }
        PathSafety.assertSingleFileTarget(local)
        beforeLocalOpen(local)
        // CWE-400: bound the transfer so a stalling peer cannot hold resources indefinitely.
        withTransferTimeout { sftp.read(remote).use { input -> Files.newOutputStream(local, *NOFOLLOW_WRITE).use { input.copyTo(it) } } }
    }

    private suspend fun downloadDirInto(
        sftp: SftpClient,
        remoteDir: String,
        localDir: Path,
        mode: DownloadMode,
        root: Path,
        materialized: MutableSet<Path>?,
    ) {
        if (!withTransferTimeout { remoteExists(sftp, remoteDir) }) return
        Files.createDirectories(localDir)
        // CWE-400: cap the listing to prevent heap exhaustion from a hostile peer.
        val entries = mutableListOf<SftpClient.DirEntry>()
        withTransferTimeout {
            for (entry in sftp.readDir(remoteDir)) {
                if (entries.size >= maxDirEntries) throw IOException("remote readDir cap of $maxDirEntries entries exceeded")
                entries.add(entry)
            }
        }
        for (entry in entries) {
            val fn = entry.filename
            if (fn == "." || fn == "..") continue
            val remoteChild = "$remoteDir/$fn"
            // CWE-22, CWE-59: containedChild is lexical, so PathSafety adds a realpath check, used only as a check:
            // the walk, its writes and `materialized` stay lexical, like prune() (ADR-0055).
            val localChild = containedChild(localDir, fn)
            PathSafety.assertContained(root, localChild)
            // CWE-59: assertContained resolves past a dangling link, which the write below follows (ADR-0055).
            PathSafety.assertDanglingLinkContained(root, localChild)
            materialized?.add(root.toAbsolutePath().normalize().relativize(localChild.toAbsolutePath().normalize()))
            if (entry.attributes.isDirectory) {
                downloadDirInto(sftp, remoteChild, localChild, mode, root, materialized)
            } else {
                if (mode == DownloadMode.OVERLAY && Files.exists(localChild)) {
                    // OVERLAY: keep locally-streamed files (e.g. cmd_N.out); only
                    // pull remote files that don't already exist locally.
                    continue
                }
                // CWE-400: bound the transfer so a stalling peer cannot hold resources indefinitely.
                withTransferTimeout {
                    sftp.read(remoteChild).use { input ->
                        Files.newOutputStream(localChild).use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    suspend fun removeRecursively(remoteDir: String) = withSftp { sftp -> withTransferTimeout { removeRecursivelyIn(sftp, remoteDir) } }

    private fun removeRecursivelyIn(
        sftp: SftpClient,
        remoteDir: String,
    ) {
        if (!remoteExists(sftp, remoteDir)) return
        for (entry in sftp.readDir(remoteDir)) {
            val fn = entry.filename
            if (fn == "." || fn == "..") continue
            val child = "$remoteDir/$fn"
            if (entry.attributes.isDirectory) removeRecursivelyIn(sftp, child) else sftp.remove(child)
        }
        sftp.rmdir(remoteDir)
    }
}
