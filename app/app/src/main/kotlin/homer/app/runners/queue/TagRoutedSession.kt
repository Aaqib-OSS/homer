package homer.app.runners.queue

import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession

/**
 * The session a claim of [filter] is delivered as: a tag-filtered run gets the serving runner's `env` through the
 * session, as its workspace env was composed before its runner was known; an alias-filtered one is untouched (ADR-0066).
 */
internal fun RunnerSession.deliveredFor(
    filter: RunnerFilter,
    runnerEnv: Map<String, String>,
): RunnerSession = if (filter is RunnerFilter.Tags && runnerEnv.isNotEmpty()) TagRoutedSession(this, runnerEnv) else this

/**
 * Session handed to a tag-filtered run, adding the serving runner's `env` to RunnerSession.env (ADR-0066).
 * Session-contributed values (e.g. HMR_API_URL) win over the runner's config env.
 */
internal class TagRoutedSession(
    private val delegate: RunnerSession,
    runnerEnv: Map<String, String>,
) : RunnerSession by delegate {
    override val env: Map<String, String> = runnerEnv + delegate.env
}
