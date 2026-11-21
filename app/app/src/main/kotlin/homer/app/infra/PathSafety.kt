package homer.app.infra

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

// Contained only when both the lexical and the realpath view agree, else SecurityException. See ADR-0047.
internal object PathSafety {
    fun assertContained(
        root: Path,
        candidate: Path,
    ): Path {
        val rootExists = Files.exists(root)
        val candidateExists = Files.exists(candidate)

        val lexicalRoot = root.toAbsolutePath().normalize()
        val lexicalCandidate = candidate.toAbsolutePath().normalize()

        // Not-yet-existing paths fall back to canonicalizing the longest existing ancestor, so both
        // sides stay consistent (e.g. /tmp -> /private/tmp on macOS) and prefix symlink-escapes are still caught.
        val realRoot = if (rootExists) root.toRealPath() else resolveLongestExistingPrefix(lexicalRoot)
        val realCandidate =
            if (candidateExists) {
                candidate.toRealPath()
            } else {
                resolveLongestExistingPrefix(lexicalCandidate)
            }

        val lexicalContained = lexicalCandidate.startsWith(lexicalRoot)
        val realContained = realCandidate.startsWith(realRoot)

        if (!lexicalContained || !realContained) {
            throw SecurityException(
                "Path '$candidate' resolves outside root '$root' (resolved to '$realCandidate')",
            )
        }
        // Existing candidate returns its realpath; a missing one the lexical absolute path.
        return if (candidateExists) realCandidate else lexicalCandidate
    }

    // Deletes [dir] only after re-asserting containment under [root]: stored paths are untrusted. See ADR-0047.
    @OptIn(ExperimentalPathApi::class)
    fun deleteContainedRecursively(
        root: Path,
        dir: Path,
    ) {
        val resolved = assertContained(root, dir)
        val normalizedRoot = root.toAbsolutePath().normalize()
        // assertContained accepts dir == root, but a persisted per-run projectRoot equal to the root
        // must not turn a cleanup into a wipe of the whole workspace root.
        if (resolved == normalizedRoot || dir.toAbsolutePath().normalize() == normalizedRoot) {
            throw SecurityException("Refusing to recursively delete the workspace root itself: $dir")
        }
        dir.deleteRecursively()
    }

    // Rejects a symlink at any component below [root]; call after assertContained. See ADR-0053, ADR-0047.
    fun assertNoSymlinkComponentsBelow(
        root: Path,
        candidate: Path,
    ) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        if (normalizedCandidate == normalizedRoot) return
        // relativize is safe: assertContained already proved lexical containment.
        var current = normalizedRoot
        for (segment in normalizedRoot.relativize(normalizedCandidate)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) {
                throw SecurityException(
                    "workspace path component '$current' is a symlink; a per-run workspace path must not traverse symlinks",
                )
            }
        }
    }

    // Requires every hop of a dangling symlink chain to resolve inside [root], by realpath, never
    // normalized. See ADR-0047.
    fun assertDanglingLinkContained(
        root: Path,
        candidate: Path,
    ) {
        val realRoot = resolveLongestExistingPrefix(root.toAbsolutePath())
        var current = candidate.toAbsolutePath()
        var hops = 0
        while (Files.isSymbolicLink(current) && !Files.exists(current)) {
            if (++hops > MAX_LINK_HOPS) throw SecurityException("symlink chain at '$candidate' is too long")
            current = current.resolveSibling(Files.readSymbolicLink(current))
            if (!resolveLongestExistingPrefix(current).startsWith(realRoot)) {
                throw SecurityException("dangling symlink at '$candidate' leads outside '$root'")
            }
        }
    }

    // True when [path] exists but is not a single-name regular file; a read of a run's file refuses it. See AR-12.
    fun isLinkedOrSpecial(path: Path): Boolean {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return true
        return isHardLinked(path)
    }

    // True when [path] has more than one link, so containment cannot be decided. See AR-12.
    fun isHardLinked(path: Path): Boolean {
        // `as? Number`: some views report nlink as a Long, which `as? Int` would read as unlinked.
        val nlink =
            runCatching {
                Files.readAttributes(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)["nlink"] as? Number
            }.getOrNull()
        return nlink != null && nlink.toLong() > 1
    }

    /** The kernel's own ELOOP bound on Linux. */
    private const val MAX_LINK_HOPS = 40

    // Where a write to [candidate] would land: its own dangling-symlink chain followed and its
    // intermediate symlink components resolved. Companion to [assertDanglingLinkContained].
    fun resolveDanglingLinkTarget(candidate: Path): Path {
        var current = candidate.toAbsolutePath()
        var hops = 0
        while (Files.isSymbolicLink(current) && !Files.exists(current)) {
            if (++hops > MAX_LINK_HOPS) break
            current = current.resolveSibling(Files.readSymbolicLink(current))
        }
        return resolveLongestExistingPrefix(current.toAbsolutePath().normalize())
    }

    private fun resolveLongestExistingPrefix(path: Path): Path {
        var existing: Path? = path
        while (existing != null && !Files.exists(existing)) {
            existing = existing.parent
        }
        if (existing == null) return path
        val real = existing.toRealPath()
        val remainder = existing.relativize(path)
        return if (remainder.toString().isEmpty()) real else real.resolve(remainder).normalize()
    }
}
