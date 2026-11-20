package homer.app.runners

import homer.app.infra.PathSafety
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * One entry of [TarStreams.extract]: the containment, link-refusal and mode-normalization
 * rules that treat the far side of a transfer as a trust boundary (ADR-0043, ADR-0047,
 * ADR-0055). Logs under [TarStreams], the API it implements.
 */
internal object TarExtraction {
    private val logger = LoggerFactory.getLogger(TarStreams::class.java)

    private val NOFOLLOW_WRITE =
        arrayOf<OpenOption>(
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS,
        )

    /** Extracts [entry] of [tar] under [local]; the contract is [TarStreams.extract]'s. */
    fun extractEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        remoteIsDir: Boolean,
        local: Path,
        fileName: String,
        mode: DownloadMode,
        materialized: MutableSet<Path>?,
        sourceLabel: String,
        posixViewLookup: ((Path, Boolean) -> PosixFileAttributeView?)?,
    ) {
        // The far side is a trust boundary: no entry name escapes the target, and no link entry is materialized (ADR-0047).
        if (!entry.isDirectoryOrRegularFile) {
            logger.warn("Skipping non-regular tar entry '{}' from {}", entry.name, sourceLabel)
            return
        }
        val root = local.toAbsolutePath().normalize()
        val target = entryTarget(entry, remoteIsDir, local, root, fileName, materialized)
        if (entry.isDirectory) {
            Files.createDirectories(target)
            recordAncestors(root, target.parent, materialized)
            return
        }
        if (mode == DownloadMode.OVERLAY && Files.exists(target)) return
        target.parent?.let { Files.createDirectories(it) }
        recordAncestors(root, target.parent, materialized)
        // NOFOLLOW_LINKS on the single-file branch only: the directory branch must follow a contained link, which a
        // live workspace legitimately holds; assertContained guards it (ADR-0047, amendment of 2026-09-30).
        val options = if (remoteIsDir) emptyArray() else NOFOLLOW_WRITE
        Files.newOutputStream(target, *options).use { tar.copyTo(it) }
        // The entry mode is attacker-controlled: only 0755/0644 is applied, and any failed chmod refuses the entry; a
        // null view is a non-POSIX target, written bytes-only (ADR-0047, amendments of 2026-10-06; CWE-732).
        val view = posixViewFor(target, remoteIsDir, posixViewLookup)
        if (view != null) normalizeModeOrRefuse(view, target, entry, sourceLabel)
    }

    // isFile() is also true for symlink and hardlink entries, hence the explicit link checks.
    private val TarArchiveEntry.isDirectoryOrRegularFile get() = isDirectory || (isFile && !isSymbolicLink && !isLink)

    /** Where [entry] lands: a contained path under [local] (recorded), or [local] itself for a file download. */
    private fun entryTarget(
        entry: TarArchiveEntry,
        remoteIsDir: Boolean,
        local: Path,
        root: Path,
        fileName: String,
        materialized: MutableSet<Path>?,
    ): Path =
        if (remoteIsDir) {
            val lexicalTarget = TarStreams.containedEntryPath(local, entry.name)
            // Recorded lexically, pre-realpath: the MIRROR prune walk is lexical too, and both must agree (ADR-0055).
            materialized?.add(root.relativize(lexicalTarget))
            // containedEntryPath is lexical only; assertContained adds the realpath check, so a symlink already in the
            // destination cannot steer the write outside the workspace.
            PathSafety.assertContained(local, lexicalTarget).also {
                // The write below follows links, so a dangling one must lead inside too.
                PathSafety.assertDanglingLinkContained(local, lexicalTarget)
            }
        } else {
            // The only expected entry is fileName. Strip a literal "./" (tar's bare-filename prefix), not every
            // leading dot, or a dotfile such as ".env" is refused.
            if (entry.name.removePrefix("./") != fileName) {
                throw SecurityException("Unexpected tar entry '${entry.name}' for single-file download")
            }
            // `local` is the file, so its own directory is the containment root: a live or dangling symlink planted
            // at `local` must not steer the write at a host file (ADR-0047, amendment of 2026-09-30).
            val parent =
                root.parent ?: throw SecurityException("Single-file download target '$local' has no parent directory")
            PathSafety.assertContained(parent, local)
            PathSafety.assertNoSymlinkComponentsBelow(parent, local)
            local
        }

    /** Normalizes [target]'s mode; on any failure removes what was written and refuses [entry]. See [TarStreams.extract]. */
    private fun normalizeModeOrRefuse(
        view: PosixFileAttributeView,
        target: Path,
        entry: TarArchiveEntry,
        sourceLabel: String,
    ) {
        try {
            view.setPermissions(normalizedPermissions(entry.mode))
        } catch (e: Exception) {
            // A directory-branch write may have followed a contained dangling link: delete where the bytes landed,
            // then the link, and say in the denial when either is left (ADR-0047, amendments of 2026-10-06; CWE-59).
            val landing = PathSafety.resolveDanglingLinkTarget(target)
            val landingRemoved = runCatching { if (landing != target) Files.deleteIfExists(landing) }
            val linkRemoved = runCatching { Files.deleteIfExists(target) }
            landingRemoved.onFailure {
                logger.warn("Could not remove the landing path '{}' of '{}' after a failed mode normalization", landing, target, it)
            }
            linkRemoved.onFailure {
                logger.warn("Could not remove '{}' after a failed mode normalization", target, it)
            }
            val removed = if (landingRemoved.isFailure) landingRemoved else linkRemoved
            val leftBehind =
                if (removed.isSuccess) {
                    ""
                } else if (landingRemoved.isFailure) {
                    "; content was left behind at the link's destination"
                } else {
                    "; the target could not be removed"
                }
            throw SecurityException(
                "Could not normalize the mode of tar entry '${entry.name}' from $sourceLabel$leftBehind",
                e,
            )
        }
    }

    /**
     * The POSIX view for an extracted [target], following a link on both branches: a `NOFOLLOW_LINKS` view sets the link
     * target's mode anyway (ADR-0047, amendment of 2026-11-05). Null on a non-POSIX target. [override] is the test seam
     * from [TarStreams.extract]; null means the real lookup.
     */
    private fun posixViewFor(
        target: Path,
        remoteIsDir: Boolean,
        override: ((Path, Boolean) -> PosixFileAttributeView?)?,
    ): PosixFileAttributeView? {
        if (override != null) return override(target, remoteIsDir)
        return Files.getFileAttributeView(target, PosixFileAttributeView::class.java)
    }

    /**
     * Records every ancestor of [from] below [root] into [materialized]. An untrusted stream may omit an entry's parent
     * directory entries, and the MIRROR prune would then try to remove a kept, non-empty directory as an extra.
     */
    private fun recordAncestors(
        root: Path,
        from: Path?,
        materialized: MutableSet<Path>?,
    ) {
        if (materialized == null) return
        var ancestor = from
        while (ancestor != null && ancestor != root && ancestor.startsWith(root)) {
            materialized.add(root.relativize(ancestor))
            ancestor = ancestor.parent
        }
    }

    private val EXECUTABLE_PERMISSIONS: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rwxr-xr-x")

    private val NON_EXECUTABLE_PERMISSIONS: Set<PosixFilePermission> =
        PosixFilePermissions.fromString("rw-r--r--")

    /**
     * The permission set to apply to an extracted file: 0755 when the entry carries the
     * owner-exec bit, 0644 otherwise. Never the entry's own bits — the tar is
     * attacker-controlled (ADR-0047 rule 2, CWE-732).
     */
    private fun normalizedPermissions(mode: Int): Set<PosixFilePermission> =
        if (mode and TarStreams.OWNER_EXECUTE_BIT != 0) EXECUTABLE_PERMISSIONS else NON_EXECUTABLE_PERMISSIONS
}
