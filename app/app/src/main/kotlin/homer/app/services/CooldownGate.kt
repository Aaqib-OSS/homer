package homer.app.services

import homer.app.exceptions.CooldownActiveException
import homer.app.models.Agent
import homer.app.models.Cooldown
import homer.app.services.persistence.ProcessRepository

/** Enforces an agent's `cooldown`: the minimum spacing between its runs. State comes from the process table, so a
 * cooldown survives a restart but not the record sweep (`workspace.retain.recordFor`, default `purgeAfter`). See ADR-0051. */
internal class CooldownGate(
    private val repository: ProcessRepository,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    /** Throws [CooldownActiveException] when [agent]'s cooldown has not elapsed; no-op when unset. [cooldownKey] is
     * the run's rendered `cooldown.key`, narrowing both lookups to one subject's rows. Pre-flight only, not the
     * bound — two concurrent starts can both pass; the authoritative check is in `obtainProcessId`'s INSERT. See ADR-0051, ADR-0106. */
    suspend fun check(
        agent: Agent,
        cooldownKey: String? = null,
    ) {
        val cooldown = agent.cooldown ?: return
        CooldownDecision
            .evaluate(
                agentName = agent.name,
                cooldown = cooldown,
                nowSec = nowSec(),
                // Read only what the configured windows need.
                latestRunStartedAt = cooldown.minIntervalSeconds?.let { repository.latestRunStartedAt(agent.name, cooldownKey) },
                latestFailureEndedAt = cooldown.afterFailureSeconds?.let { repository.latestFailureEndedAt(agent.name, cooldownKey) },
            )?.let { throw it }
    }
}

/** [agent] as a manual-recovery start sees it: cooldown with `afterFailure` cleared, so a person unblocking a
 * failed run is not held back. `minInterval` (and its `key`) survives; an unset one drops the cooldown. See ADR-0051, ADR-0106. */
internal fun Agent.withoutFailureBackoff(): Agent =
    cooldown
        ?.let { cd -> cd.minInterval?.let { cd.copy(afterFailure = null) } }
        ?.let { copy(cooldown = it) }
        ?: copy(cooldown = null)

/** The cooldown decision as a pure function of the windows and the two timestamps. Extracted so [CooldownGate]
 * and `obtainProcessId`'s transaction share one implementation, which cannot call the suspending gate. See ADR-0106. */
internal object CooldownDecision {
    /** The refusal to throw, or null when the start is allowed. */
    fun evaluate(
        agentName: String,
        cooldown: Cooldown,
        nowSec: Long,
        latestRunStartedAt: Long?,
        latestFailureEndedAt: Long?,
    ): CooldownActiveException? {
        cooldown.afterFailureSeconds?.let { window ->
            latestFailureEndedAt?.let { failedAt ->
                val remaining = failedAt + window - nowSec
                if (remaining > 0) {
                    return CooldownActiveException(agentName, remaining, "last run failed ${nowSec - failedAt}s ago")
                }
            }
        }
        cooldown.minIntervalSeconds?.let { window ->
            latestRunStartedAt?.let { startedAt ->
                val remaining = startedAt + window - nowSec
                if (remaining > 0) {
                    return CooldownActiveException(agentName, remaining, "last run started ${nowSec - startedAt}s ago")
                }
            }
        }
        return null
    }
}
