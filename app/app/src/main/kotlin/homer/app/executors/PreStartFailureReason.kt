package homer.app.executors

import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.MaxLockWaitersExceededException
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.exceptions.UnresolvedLockKeyPlaceholderException
import homer.app.exceptions.WorkspacePendingDeleteException
import homer.app.exceptions.placeholderSentence
import homer.app.models.RunnerFilter
import homer.app.models.truncateFailureReason

/**
 * Why a run ended FAILED before any command started: [reason] for a reader of the run, and
 * [holderRunId], the run that blocked it, kept out of [reason] so the API can withhold it from a
 * caller who may not see that run. See ADR-0098, ADR-0058.
 */
data class PreStartFailure(
    val reason: String,
    val holderRunId: Int? = null,
)

/**
 * Maps the typed pre-start causes to a stored reason, any other exception to null. Built from typed
 * fields, never the message, which can carry a runner alias (ADR-0077), a lock key or an input value.
 * See ADR-0098, ADR-0047 rule 7.
 */
object PreStartFailureReason {
    fun of(e: Exception): PreStartFailure? = describe(e)?.let { it.copy(reason = sanitize(it.reason)) }

    private fun describe(e: Exception): PreStartFailure? =
        when (e) {
            is AnotherProcessStillActive -> {
                PreStartFailure(
                    "Lock skip (lockStrategy.onCollision: SKIP_CURRENT): another run still holds this agent's lock, " +
                        "so this run never started.",
                    holderRunId = e.activeProcessId,
                )
            }

            is RunnerClaimTimeoutException -> {
                PreStartFailure(
                    "No runner claimed this run within ${e.timeout.inWholeSeconds} s (${describeFilter(e.filter)}): " +
                        "${e.publicReason}.",
                )
            }

            is UnresolvedLockKeyPlaceholderException -> {
                PreStartFailure(
                    "Lock key ${placeholderSentence(e.placeholders)} (lockStrategy.key), so this run never started.",
                )
            }

            is WorkspacePendingDeleteException -> {
                PreStartFailure(
                    "Workspace directory was still being deleted for an evicted run, so this run never started.",
                )
            }

            else -> {
                boundRefusal(e)
            }
        }

    /** The refusals whose reason names the bound that was already full, split off `describe` (ADR-0081). */
    private fun boundRefusal(e: Exception): PreStartFailure? =
        when (e) {
            is MaxParallelRunsExceededException -> {
                PreStartFailure(
                    "Parallel-run cap (maxParallelRuns: ${e.maxParallelRuns}, onMaxParallelRuns: REJECT): agent " +
                        "'${e.agentName}' was already at its limit, so this run never started.",
                )
            }

            is MaxQueuedRunsExceededException -> {
                PreStartFailure(
                    "Parallel-run wait queue full (maxQueuedRuns: ${e.maxQueuedRuns}): agent '${e.agentName}' " +
                        "already had that many runs waiting for a slot, so this run never started.",
                )
            }

            is MaxLockWaitersExceededException -> {
                PreStartFailure(
                    "Lock wait queue full (maxParallelRuns: ${e.bound}): agent '${e.agentName}' " +
                        "already had that many runs waiting for a lock, so this run never started.",
                )
            }

            is ClaimQueueFullException -> {
                PreStartFailure(
                    "Runner claim queue full (maxPendingRunnerClaims: ${e.maxPending}): that many runs were " +
                        "already waiting to be claimed, so this run never started.",
                )
            }

            else -> {
                null
            }
        }

    /**
     * The run's runner filter without its alias: `describe()` names one, and a runner alias is
     * admin-only on the status API (ADR-0077) while this text is served to every caller who can
     * see the run. The tags of a tag filter are already public on the run (ADR-0060).
     */
    private fun describeFilter(filter: RunnerFilter): String =
        when (filter) {
            is RunnerFilter.Alias -> "pinned runner filter"
            is RunnerFilter.Tags -> "tags ${filter.tags.sorted()}"
        }

    /**
     * [text] with control characters removed and the length cap applied: the reason reaches a
     * console and a JSON field, so it carries one line and stays bounded whatever a filter's
     * tags or an agent name hold. The repository write applies the cap again. See ADR-0047.
     */
    fun sanitize(text: String): String = truncateFailureReason(text.filterNot { it.isISOControl() })
}
