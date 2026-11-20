package homer.app.runners.queue

import homer.app.models.RunnerFilter
import kotlin.time.Duration

/**
 * Both renderings of a claim's expiry reason: with aliases for the log, and run-visible naming no instance (ADR-0077).
 * A preference that held the whole wait is the cause, as matching runners passed the run over on purpose (ADR-0099).
 */
internal fun claimExpiryReasons(
    consumers: List<ClaimConsumerInfo>,
    filter: RunnerFilter,
    affinity: ClaimAffinity?,
    timeout: Duration,
): Pair<String, String> {
    // The preference is the cause only while its instance is live and matches the filter: else it blocked no one.
    val preferenceHeld =
        affinity != null &&
            affinity.heldWholeWait(timeout.inWholeMilliseconds) &&
            consumers.any {
                it.affinityInstance == affinity.preferredInstance && !it.draining && it.matchesFilter(filter)
            }
    if (preferenceHeld) {
        val cause = "its preferred runner did not take it"
        return "$cause; ${matchingStates(consumers, filter)}" to cause
    }
    return expiryReason(consumers, filter, withAliases = true) to expiryReason(consumers, filter, withAliases = false)
}

/** The per-runner states of every consumer matching [filter], oldest claim first. */
private fun matchingStates(
    consumers: List<ClaimConsumerInfo>,
    filter: RunnerFilter,
): String =
    consumers
        .filter { it.matches(filter) }
        .joinToString(", ") { "runner '${it.alias}' at concurrency ${it.inUse}/${it.concurrency}" }

private fun expiryReason(
    consumers: List<ClaimConsumerInfo>,
    filter: RunnerFilter,
    withAliases: Boolean,
): String {
    val matching = consumers.filter { it.matches(filter) }
    if (matching.isEmpty()) {
        return when (filter) {
            is RunnerFilter.Alias -> {
                if (withAliases) {
                    "runner '${filter.alias}' has no registered instances"
                } else {
                    "no registered runner matches it"
                }
            }

            is RunnerFilter.Tags -> {
                if (withAliases) {
                    "no registered runner matches ${filter.describe()}"
                } else {
                    "no registered runner matches those tags"
                }
            }
        }
    }
    // Run-visible, so it names no runner alias (ADR-0077); both renderings read one snapshot and cannot disagree.
    if (!withAliases) return "every matching runner was at its concurrency limit"
    return matchingStates(consumers, filter)
}
