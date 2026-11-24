package homer.app.services

import homer.app.infra.PathSafety
import homer.app.services.persistence.HeldWorkspaceDelete
import homer.app.services.persistence.WorkspaceDeleteClaim
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import java.nio.file.Path
import java.util.UUID

/**
 * Deletes the workspace of a run evicted at capacity, whose row is already gone. The claim on the directory
 * is durable, so a delete that failed, or a crash interrupted, is retried by every workspace purge sweep.
 * See ADR-0048 (2026-11-24 amendment).
 */
internal class EvictedWorkspaceCleanup(
    private val workspaceRoot: Path,
    private val logger: Logger,
    private val claimDelete: suspend (
        artifactsDir: Path,
        evictedProcessId: Int,
        maxPending: Int,
        attemptToken: String,
    ) -> WorkspaceDeleteClaim,
    private val settleDelete: suspend (artifactsDir: Path, attemptToken: String, deleted: Boolean) -> Unit,
    private val heldDeletes: suspend (limit: Int) -> List<HeldWorkspaceDelete>,
    private val countDeletes: suspend () -> Int,
    private val deleteContained: suspend (root: Path, dir: Path) -> Unit = { root, dir ->
        PathSafety.deleteContainedRecursively(root, dir)
    },
) {
    /** Deletes still waiting for a retry, for tests that pin the bound. */
    suspend fun pendingCount(): Int = countDeletes()

    suspend fun delete(
        evictedId: Int,
        artifactsDir: Path,
    ) {
        when (val attempt = claimAndDelete(evictedId, artifactsDir)) {
            Attempt.Done -> {}

            Attempt.Shared -> {
                logger.info(
                    "Skipping workspace deletion for evicted process {}: {} is still referenced by another process",
                    evictedId,
                    artifactsDir.parent,
                )
            }

            Attempt.Full -> {
                logger.error(
                    "Workspace of evicted process {} is left on disk: {} deletes already await a retry",
                    evictedId,
                    MAX_PENDING,
                )
            }

            is Attempt.Failed -> {
                logger.warn(
                    "Failed to delete workspace of evicted process {}, retrying on every sweep: {}",
                    evictedId,
                    attempt.failure.message,
                )
            }
        }
    }

    /** Retries every held delete once; one that succeeds, or no longer applies, is dropped. */
    suspend fun retryPending() {
        for (held in heldDeletes(MAX_PENDING)) {
            val attempt = claimAndDelete(held.evictedProcessId, held.artifactsDir)
            if (attempt is Attempt.Failed) {
                logger.debug(
                    "Retried delete of evicted process {}'s workspace failed again: {}",
                    held.evictedProcessId,
                    attempt.failure.message,
                )
            }
        }
    }

    /** What one claim-and-delete did, so the first attempt and a retry can log it differently. */
    private sealed interface Attempt {
        data object Done : Attempt

        data class Failed(
            val failure: Throwable,
        ) : Attempt

        data object Shared : Attempt

        data object Full : Attempt
    }

    // Re-claiming is what makes a retry safe: the claim and the sharing check are one transaction, so a run
    // registering this path is refused for as long as the delete runs (CWE-367). See ADR-0048.
    private suspend fun claimAndDelete(
        evictedId: Int,
        artifactsDir: Path,
    ): Attempt {
        // One token per attempt: an attempt that takes this claim over stamps its own, so this one's settle
        // leaves the claim in flight while that attempt still deletes the directory (CWE-367).
        val attemptToken = UUID.randomUUID().toString()
        when (claimDelete(artifactsDir, evictedId, MAX_PENDING, attemptToken)) {
            WorkspaceDeleteClaim.SHARED -> return Attempt.Shared
            WorkspaceDeleteClaim.FULL -> return Attempt.Full
            WorkspaceDeleteClaim.CLAIMED -> Unit
        }
        val failure = deleteFailure(evictedId, artifactsDir)
        settleDelete(artifactsDir, attemptToken, failure == null)
        return if (failure == null) Attempt.Done else Attempt.Failed(failure)
    }

    /** Why the delete must be retried, or null when nothing is left to retry: deleted, or refused by containment. */
    private suspend fun deleteFailure(
        evictedId: Int,
        artifactsDir: Path,
    ): Throwable? {
        val workspaceDir = artifactsDir.parent ?: return null
        return try {
            deleteContained(workspaceRoot, workspaceDir)
            null
        } catch (e: SecurityException) {
            // A stored path outside the workspace root fails the same way on every attempt (CWE-22).
            logger.warn("Refused to delete workspace of evicted process {}: {}", evictedId, e.message)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
    }

    companion object {
        /** The bound on held deletes; each costs one delete attempt per sweep. */
        const val MAX_PENDING = 256
    }
}
