package homer.app.services

import homer.app.infra.PathSafety
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Deletes the workspace of a run evicted at capacity, whose row is already gone. A failed delete is held in
 * memory, at most [MAX_PENDING], and retried by every workspace purge sweep until it succeeds. See ADR-0048.
 */
internal class EvictedWorkspaceCleanup(
    private val workspaceRoot: Path,
    private val logger: Logger,
    private val isArtifactsDirShared: suspend (artifactsDir: Path, evictedId: Int) -> Boolean,
    private val deleteContained: (root: Path, dir: Path) -> Unit = PathSafety::deleteContainedRecursively,
) {
    private val pending = ConcurrentHashMap<Int, Path>()

    /** Deletes still waiting for a retry, for tests that pin the bound. */
    val pendingCount: Int get() = pending.size

    suspend fun delete(
        evictedId: Int,
        artifactsDir: Path,
    ) {
        if (tryDelete(evictedId, artifactsDir)) return
        if (pending.size < MAX_PENDING) {
            pending[evictedId] = artifactsDir
        } else {
            logger.error("Workspace of evicted process {} is left on disk: {} deletes already await a retry", evictedId, MAX_PENDING)
        }
    }

    /** Retries every held delete once; one that succeeds, or no longer applies, is dropped. */
    suspend fun retryPending() {
        for ((evictedId, artifactsDir) in pending) {
            if (tryDelete(evictedId, artifactsDir)) pending.remove(evictedId)
        }
    }

    /** True when nothing is left to retry: deleted, still shared, or refused by the containment check. */
    private suspend fun tryDelete(
        evictedId: Int,
        artifactsDir: Path,
    ): Boolean {
        val workspaceDir = artifactsDir.parent ?: return true
        if (isArtifactsDirShared(artifactsDir, evictedId)) {
            logger.info(
                "Skipping workspace deletion for evicted process {}: {} is still referenced by another process",
                evictedId,
                workspaceDir,
            )
            return true
        }
        val failure = runCatching { deleteContained(workspaceRoot, workspaceDir) }.exceptionOrNull() ?: return true
        // A stored path outside the workspace root fails the same way on every attempt (CWE-22).
        if (failure is SecurityException) {
            logger.warn("Refused to delete workspace of evicted process {}: {}", evictedId, failure.message)
            return true
        }
        logger.warn("Failed to delete workspace of evicted process {}, retrying on the next sweep: {}", evictedId, failure.message)
        return false
    }

    companion object {
        /** The bound on held deletes; each costs one delete attempt per sweep. */
        const val MAX_PENDING = 256
    }
}
