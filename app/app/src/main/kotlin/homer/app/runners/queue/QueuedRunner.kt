package homer.app.runners.queue

import homer.app.models.RunnerAffinity
import homer.app.models.RunnerFilter
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import kotlin.time.Duration

/**
 * The Runner every run sees (ADR-0066): openSession() waits in the claim queue for a consumer matching [filter]. A tags
 * filter derives its facets from every candidate (remote if any is, secret names unioned, runnerEnv via the session).
 * [affinity] is the agent's `runnerAffinity` (ADR-0099); a `runnerCmd` step's runner has none.
 */
class QueuedRunner(
    private val filter: RunnerFilter,
    private val queue: RunnerClaimQueue,
    candidates: List<Runner>,
    val claimTimeout: Duration,
    private val affinity: RunnerAffinity? = null,
) : Runner {
    init {
        if (filter is RunnerFilter.Alias) require(candidates.size == 1) { "alias filter needs exactly one candidate" }
    }

    override val alias: String =
        when (filter) {
            is RunnerFilter.Alias -> filter.alias
            is RunnerFilter.Tags -> "tags:" + filter.tags.sorted().joinToString(",")
        }

    override val isRemote: Boolean = candidates.any { it.isRemote }

    override val runnerEnv: Map<String, String> =
        if (filter is RunnerFilter.Alias) candidates.single().runnerEnv else emptyMap()

    override val sysEnvBackedEnvNames: Set<String> = candidates.flatMapTo(mutableSetOf()) { it.sysEnvBackedEnvNames }

    override suspend fun openSession(params: RunnerSessionParams): RunnerSession = queue.awaitClaim(params, filter, claimTimeout, affinity)
}
