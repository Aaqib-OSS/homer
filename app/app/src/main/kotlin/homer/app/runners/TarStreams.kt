package homer.app.runners

import homer.app.exceptions.RunnerTransferTooLargeException
import homer.app.infra.PathSafety
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

// Transport-neutral gzip'd-tar packing/extraction for the Kubernetes sync and the external runner
// protocol; extraction treats the far side as a trust boundary. See ADR-0043, ADR-0055, ADR-0066.
object TarStreams {
    private val logger = LoggerFactory.getLogger(TarStreams::class.java)

    // Extraction bound where the caller configures none; matches `maxTransferBytes`'s default.
    const val DEFAULT_MAX_EXTRACTED_BYTES: Long = 1L * 1024 * 1024 * 1024

    /** Directory nesting an upload walk descends, here and in the ssh upload (ADR-0047 rule 4, CWE-674). */
    internal const val MAX_UPLOAD_DEPTH = 64

    /** Octal `0100`, the owner-execute mode bit. */
    internal const val OWNER_EXECUTE_BIT = 64

    // Realpath of an operator-configured pack source, so [pack]'s symlink refusal does not break
    // a legitimate link; never call on an agent-written path (ADR-0047 rule 2, CWE-59). See ADR-0042.
    fun resolvedPackSource(local: Path): Path = runCatching { local.toRealPath() }.getOrDefault(local)

    // Writes [local] as a gzip'd tar to [out] and closes [out]; a directory contributes its
    // contents, a regular file one entry named [singleFileEntryName] (default: its file name).
    fun pack(
        out: OutputStream,
        local: Path,
        excludes: List<String> = emptyList(),
        omitDirEntries: Set<String> = emptySet(),
        singleFileEntryName: String? = null,
    ) {
        // A symlink at the source itself is checked nowhere else — isDirectory()/isRegularFile()
        // below follow it and addFile would stream the target's bytes (ADR-0047 rule 2, CWE-59).
        if (Files.isSymbolicLink(local)) {
            throw SecurityException("pack source '$local' is a symlink")
        }
        TarArchiveOutputStream(GzipCompressorOutputStream(out)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            when {
                local.isDirectory() -> {
                    addDirContents(tar, local, "", excludes, local, omitDirEntries, mutableSetOf(local.toRealPath()), 0, viaLink = false)
                }

                local.isRegularFile() -> {
                    // Refused, not skipped: a single file was named explicitly, so an empty tar would hide it.
                    if (PathSafety.isHardLinked(local)) throw SecurityException("pack source '$local' is hard-linked")
                    addFile(tar, local, singleFileEntryName ?: local.name)
                }

                else -> {
                    error("pack source does not exist: $local")
                }
            }
        }
    }

    // Extracts a gzip'd tar from [input] into [local]; [maxDecompressedBytes] bounds decompressed size
    // (ADR-0047 rule 4, CWE-409). A refused entry leaves only a partial tree, never anything outside [local].
    fun extract(
        input: InputStream,
        local: Path,
        mode: DownloadMode,
        remoteIsDir: Boolean,
        fileName: String,
        sourceLabel: String,
        maxDecompressedBytes: Long,
        posixViewLookup: ((Path, Boolean) -> PosixFileAttributeView?)? = null,
    ): Set<Path>? {
        val materialized = if (mode == DownloadMode.MIRROR && remoteIsDir) mutableSetOf<Path>() else null
        TarArchiveInputStream(BoundedInputStream(GzipCompressorInputStream(input), maxDecompressedBytes)).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                TarExtraction.extractEntry(tar, entry, remoteIsDir, local, fileName, mode, materialized, sourceLabel, posixViewLookup)
            }
        }
        return materialized
    }

    // Deletes local paths under [local] absent from [keep], deepest first. Only called after a
    // fully successful download. See ADR-0055.
    fun prune(
        local: Path,
        keep: Set<Path>,
    ) {
        val root = local.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) return
        val descendants = Files.walk(root).use { it.toList() }
        descendants
            .sortedByDescending { it.nameCount }
            .filter { it != root && root.relativize(it) !in keep }
            .forEach { Files.deleteIfExists(it) }
    }

    // [visited]/[depth] bound the walk: a symlink resolving back inside the upload root would loop forever
    // (ADR-0047 rule 4, CWE-835). A real directory is always packed; only a symlink to a visited one skips.
    private fun addDirContents(
        out: TarArchiveOutputStream,
        dir: Path,
        prefix: String,
        excludes: List<String>,
        root: Path,
        omitDirEntries: Set<String>,
        visited: MutableSet<Path>,
        depth: Int,
        viaLink: Boolean,
    ) {
        // Below the root the walk only ever descends on a checked realpath.
        val realDir = if (depth == 0) root.toRealPath() else dir
        walkEntries(dir, prefix, excludes)
            .forEach { (entry, entryName) ->
                // Pack the resolved target, not the link: addFile must read the checked path, so
                // re-pointing the link changes nothing. Escaping links warn+skip, never fail.
                val source = containedSource(entry, root)
                if (source == null) {
                    logger.warn("Skipping symlink '{}' escaping the upload root", entry)
                    return@forEach
                }
                when {
                    source.isDirectory() -> {
                        addDirEntry(out, entry, source, entryName, excludes, root, omitDirEntries, visited, depth, viaLink, realDir)
                    }

                    source.isRegularFile() -> {
                        if (PathSafety.isHardLinked(source)) {
                            // A hardlink's other name may be outside the root and containment cannot be
                            // decided, so warn+skip (pnpm/Nix dedupe legitimate ones in real workspaces).
                            logger.warn("Skipping '{}': hard-linked file, its other name may be outside the upload root", entry)
                            return@forEach
                        }
                        addFile(out, source, entryName)
                    }
                }
            }
    }

    /** A directory entry of [addDirContents]' walk: its tar entry, then its contents within the walk's bounds. */
    private fun addDirEntry(
        out: TarArchiveOutputStream,
        entry: Path,
        source: Path,
        entryName: String,
        excludes: List<String>,
        root: Path,
        omitDirEntries: Set<String>,
        visited: MutableSet<Path>,
        depth: Int,
        viaLink: Boolean,
        realDir: Path,
    ) {
        // Descend on the realpath checked here, not the entry's name: a link repointed after its
        // check would lead out of the root (CWE-59). See ADR-0047's 2026-09-23 amendment.
        val real = realPathInside(source, root)
        if (real == null) {
            logger.warn("Skipping '{}': does not resolve inside the upload root", entry)
            return
        }
        if (entryName !in omitDirEntries) {
            out.putArchiveEntry(TarArchiveEntry("$entryName/"))
            out.closeArchiveEntry()
        }
        val throughLink = viaLink || real != realDir.resolve(entry.name)
        when {
            depth >= MAX_UPLOAD_DEPTH -> {
                logger.warn("Skipping '{}': upload nesting exceeds {} levels", entry, MAX_UPLOAD_DEPTH)
            }

            // Always add: a real directory's realpath must stop a later link to it.
            !visited.add(real) && throughLink -> {
                logger.warn("Skipping '{}': already packed as another path", entry)
            }

            else -> {
                addDirContents(out, real, entryName, excludes, root, omitDirEntries, visited, depth + 1, throughLink)
            }
        }
    }

    /** [path]'s realpath when it resolves inside [root]; null when it resolves outside or not at all. */
    private fun realPathInside(
        path: Path,
        root: Path,
    ): Path? = runCatching { path.toRealPath() }.getOrNull()?.takeIf { it.startsWith(root.toRealPath()) }

    // Opens [file] NOFOLLOW_LINKS before reading size or bytes, so one pinned handle answers both and a swapped-in
    // symlink is refused (ADR-0047 rule 2, CWE-367). An ancestor-dir swap residual is accepted (ADR-0047).
    private fun addFile(
        out: TarArchiveOutputStream,
        file: Path,
        entryName: String,
    ) {
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val entry = TarArchiveEntry(entryName)
            entry.size = channel.size()
            runCatching { entry.mode = posixToMode(file.getPosixFilePermissions(LinkOption.NOFOLLOW_LINKS)) }
            out.putArchiveEntry(entry)
            Channels.newInputStream(channel).copyTo(out)
            out.closeArchiveEntry()
        }
    }

    private val POSIX_MODE_BITS =
        linkedMapOf(
            PosixFilePermission.OWNER_READ to 256,
            PosixFilePermission.OWNER_WRITE to 128,
            PosixFilePermission.OWNER_EXECUTE to OWNER_EXECUTE_BIT,
            PosixFilePermission.GROUP_READ to 32,
            PosixFilePermission.GROUP_WRITE to 16,
            PosixFilePermission.GROUP_EXECUTE to 8,
            PosixFilePermission.OTHERS_READ to 4,
            PosixFilePermission.OTHERS_WRITE to 2,
            PosixFilePermission.OTHERS_EXECUTE to 1,
        )

    /** The numeric mode (e.g. `0755`) of [perms]; shared with the ssh `WorkspaceSync` upload. */
    internal fun posixToMode(perms: Set<PosixFilePermission>): Int {
        var mode = 0
        POSIX_MODE_BITS.forEach { (perm, bit) -> if (perm in perms) mode = mode or bit }
        return mode
    }

    // The entries of [dir] an upload walk visits, each with its [prefix]-joined name: real ones
    // first, then symlinks, each group by name. Shared with the ssh `WorkspaceSync` upload.
    internal fun walkEntries(
        dir: Path,
        prefix: String,
        excludes: List<String>,
    ): List<Pair<Path, String>> =
        dir
            .listDirectoryEntries()
            // The symlink flag is read once so a flip mid-sort cannot break the comparator; it pins
            // only the order, and handling re-stats each entry safely either way.
            .map { it to Files.isSymbolicLink(it) }
            .sortedWith(compareBy({ it.second }, { it.first.name }))
            .map { (entry, _) -> entry to if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}" }
            .filterNot { (entry, entryName) -> isExcluded(excludes, entryName, entry.name) }

    // Whether the walk skips [name] at [entryName]: a `/`-prefixed exclude matches that
    // root-anchored path, any other the bare name. Shared with the ssh upload.
    internal fun isExcluded(
        excludes: List<String>,
        entryName: String,
        name: String,
    ): Boolean =
        excludes.any {
            if (it.startsWith("/")) {
                it.length == entryName.length + 1 && it.regionMatches(1, entryName, 0, entryName.length)
            } else {
                it == name
            }
        }

    // What the walk reads for [entry]: the entry, or a symlink's target when it stays inside
    // [root]; null when the link escapes or cannot be resolved. Shared with the ssh upload.
    internal fun containedSource(
        entry: Path,
        root: Path,
    ): Path? {
        if (!Files.isSymbolicLink(entry)) return entry
        val real = runCatching { entry.toRealPath() }.getOrNull()
        val contained = real != null && runCatching { real.startsWith(root.toRealPath()) }.getOrDefault(false)
        return if (contained) real else null
    }

    // Resolve a remote-supplied entry name against [base], rejecting anything not strictly
    // inside it (absolute names, `..`).
    internal fun containedEntryPath(
        base: Path,
        entryName: String,
    ): Path {
        // Char.MIN_VALUE is the NUL codepoint; using the named constant here
        // (rather than a char literal) keeps this source file plain text.
        if (entryName.isEmpty() || entryName.startsWith("/") || Char.MIN_VALUE in entryName) {
            throw SecurityException("Tar entry name '$entryName' is not a safe relative path")
        }
        val root = base.toAbsolutePath().normalize()
        val resolved = root.resolve(entryName).normalize()
        if (resolved != root && !resolved.startsWith(root)) {
            throw SecurityException("Tar entry name '$entryName' resolves outside '$base'")
        }
        if (entryName.split('/').any { it == ".." }) {
            throw SecurityException("Tar entry name '$entryName' contains '..'")
        }
        return resolved
    }
}

// Fails with [RunnerTransferTooLargeException] once more than [limit] bytes were read; one copy
// for both sides of a transfer (ADR-0047 rule 4, CWE-400).
internal class BoundedInputStream(
    private val inner: InputStream,
    private val limit: Long,
) : InputStream() {
    private var count = 0L

    override fun read(): Int = inner.read().also { if (it >= 0) account(1) }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = inner.read(b, off, len).also { if (it > 0) account(it) }

    private fun account(n: Int) {
        count += n
        if (count > limit) throw RunnerTransferTooLargeException(limit)
    }

    override fun close() = inner.close()
}
