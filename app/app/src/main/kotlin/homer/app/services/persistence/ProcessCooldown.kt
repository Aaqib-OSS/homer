package homer.app.services.persistence

import homer.app.models.ProcessStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.select

// The cooldown lookups `obtainProcessId` runs inside the INSERT's transaction (ADR-0051), over the
// one subject filter a keyed and a keyless start share (ADR-0106).

/** The newest matching run's `created_at`, read in the caller's transaction, never a nested `query { }`. */
internal fun ProcessRepository.latestRunStartedAtTx(
    agentName: String,
    cooldownKey: String?,
): Long? =
    Processes
        .select(Processes.createdAt)
        .where { cooldownPredicate(agentName, cooldownKey) }
        .orderBy(Processes.id, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.get(Processes.createdAt)

/**
 * The subject filter both cooldown lookups share: the agent, plus the rendered key when the start carries
 * one. A null key adds nothing, so a keyless cooldown counts every row of the agent, keyed ones included.
 * The key is compared with `eq`, never `LIKE`, so a `%` or `_` in it matches only itself. ADR-0106.
 */
private fun ProcessRepository.cooldownPredicate(
    agentName: String,
    cooldownKey: String?,
): Op<Boolean> =
    listOfNotNull(
        Processes.agentName eq agentName,
        cooldownKey?.let { Processes.cooldownKey eq it },
    ).reduce { a, b -> a and b }

/**
 * The latest `ended_at` of a matching run that failed while executing, by `ended_at`, never by `id`: overlapping
 * runs fail out of INSERT order. A run refused before it started carries a `failure_reason` and never arms the
 * window. ADR-0051.
 */
internal fun ProcessRepository.latestFailureEndedAtTx(
    agentName: String,
    cooldownKey: String?,
): Long? =
    Processes
        .select(Processes.endedAt)
        .where {
            cooldownPredicate(agentName, cooldownKey) and
                (Processes.status eq ProcessStatus.FAILED.name) and
                Processes.failureReason.isNull()
        }
        // Ordered by ended_at, not id. SQLite sorts NULLs last under DESC, so a
        // pre-v14 row without a recorded terminal time never masks a real one.
        .orderBy(Processes.endedAt, SortOrder.DESC)
        .limit(1)
        .singleOrNull()
        ?.get(Processes.endedAt)
