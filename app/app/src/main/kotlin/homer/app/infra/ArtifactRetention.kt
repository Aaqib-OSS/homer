package homer.app.infra

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

/**
 * Prunes a run's workspace to the files matching its `workspace.retain.artifacts` globs, which stay
 * in place for the artifact routes. See ADR-0053.
 */
internal object ArtifactRetention {
    const val MAX_PRUNE_WALK_ENTRIES = 10_000

    data class PruneResult(
        val keptFiles: Int,
        val truncated: Boolean,
    )

    fun prune(
        workspaceRoot: Path,
        artifactsDir: Path,
        globs: List<String>,
        maxBytes: Long,
        maxWalkEntries: Int = MAX_PRUNE_WALK_ENTRIES,
        beforeDeleteWalk: () -> Unit = {},
    ): PruneResult {
        // Normalize first: a redundant segment would defeat the fileName comparison in deleteSiblings.
        val dir = artifactsDir.normalize()
        val projectRoot = dir.parent ?: return PruneResult(0, false)
        PathSafety.assertContained(workspaceRoot, projectRoot)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            PathSafety.deleteContainedRecursively(workspaceRoot, projectRoot)
            return PruneResult(0, false)
        }
        // The walk over agent-written structure is bounded and counts directories too (ADR-0047 rule 4).
        val entries =
            Files.walk(dir).use { stream ->
                stream.limit(maxWalkEntries + 1L).toList()
            }
        if (entries.size > maxWalkEntries) {
            // Fail closed: retain nothing and flag the truncation (ADR-0053).
            PathSafety.deleteContainedRecursively(workspaceRoot, projectRoot)
            return PruneResult(0, true)
        }
        // NOFOLLOW and regular files only: a symlink must not pull a host file into the set (ADR-0053).
        val candidates = entries.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
        val (kept, truncated) = fitWithin(matchedFiles(dir, candidates, globs), maxBytes)
        if (kept.isEmpty()) {
            PathSafety.deleteContainedRecursively(workspaceRoot, projectRoot)
            return PruneResult(0, truncated)
        }
        deleteSiblings(workspaceRoot, projectRoot, dir)
        beforeDeleteWalk()
        if (!deleteUnkept(dir, kept, maxWalkEntries)) {
            PathSafety.deleteContainedRecursively(workspaceRoot, projectRoot)
            return PruneResult(0, true)
        }
        return PruneResult(kept.size, truncated)
    }

    /** The [candidates] matching [globs] relative to [dir], in declared-glob order, each glob's matches sorted. */
    private fun matchedFiles(
        dir: Path,
        candidates: List<Path>,
        globs: List<String>,
    ): Set<Path> {
        val matched = LinkedHashSet<Path>()
        for (glob in globs) {
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
            candidates
                .filter { matcher.matches(dir.relativize(it)) }
                .sorted()
                .forEach { matched.add(it) }
        }
        return matched
    }

    /** The prefix-in-order subset of [matched] that fits [maxBytes], and whether any file did not fit. */
    private fun fitWithin(
        matched: Set<Path>,
        maxBytes: Long,
    ): Pair<Set<Path>, Boolean> {
        var budget = maxBytes
        val kept = LinkedHashSet<Path>()
        var truncated = false
        for (file in matched) {
            val size = Files.size(file)
            if (size <= budget) {
                kept.add(file)
                budget -= size
            } else {
                truncated = true
            }
        }
        return kept to truncated
    }

    /** Stream-deletes the artifacts dir's siblings, which hold no retained files, without walking them. */
    private fun deleteSiblings(
        workspaceRoot: Path,
        projectRoot: Path,
        dir: Path,
    ) {
        Files.newDirectoryStream(projectRoot).use { children ->
            children.forEach { child ->
                // File names, not paths: a structural mismatch would stream-delete the retained set itself.
                if (child.fileName != dir.fileName) {
                    PathSafety.deleteContainedRecursively(workspaceRoot, child)
                }
            }
        }
    }

    /**
     * Deletes everything under [dir] but the [kept] files and the directories holding them; false, deleting
     * nothing, when the tree has grown past [maxWalkEntries] entries since the candidate walk.
     */
    internal fun deleteUnkept(
        dir: Path,
        kept: Set<Path>,
        maxWalkEntries: Int,
    ): Boolean {
        val keepDirs =
            kept
                .flatMap { file ->
                    generateSequence(file.parent) { it.parent }.takeWhile { it.startsWith(dir) }.toList()
                }.toSet() + dir
        val entries = Files.walk(dir).use { stream -> stream.limit(maxWalkEntries + 1L).toList() }
        if (entries.size > maxWalkEntries) return false
        // Reverse order deletes children before parents.
        entries.sortedDescending().forEach { path ->
            if (path in keepDirs) return@forEach
            // The artifacts dir is agent-writable: a kept file may since have been swapped for a
            // symlink, so keep it only while it is still a NOFOLLOW regular file.
            val stillKept = path in kept && path.isRegularFile(LinkOption.NOFOLLOW_LINKS)
            if (!stillKept) {
                Files.deleteIfExists(path)
            }
        }
        return true
    }
}
