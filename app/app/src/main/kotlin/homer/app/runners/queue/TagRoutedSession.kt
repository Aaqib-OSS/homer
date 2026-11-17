package homer.app.runners.queue

import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession

/**
 * The session a claim of [filter] is delivered as, layering the serving runner's per-runner `env`
 * for a run that could not have it earlier — the one place the two filter kinds diverge. A
 * tag-filtered run does not know its runner when its workspace env is composed, so the runner's
 * env arrives through the session; an alias-filtered run's runner was known all along, so the
 * session passes through untouched. See ADR-0066, ADR-0065.
 */
internal fun RunnerSession.deliveredFor(
    filter: RunnerFilter,
    runnerEnv: Map<String, String>,
): RunnerSession = if (filter is RunnerFilter.Tags && runnerEnv.isNotEmpty()) TagRoutedSession(this, runnerEnv) else this

/**
 * Session handed to a tag-filtered run: the serving runner's per-runner `env`
 * arrives through RunnerSession.env because the run's workspace env was composed
 * before the runner was known. Session-contributed values (e.g. HMR_API_URL) win
 * over the runner's config env. Alias-filtered runs never get this wrapper: their
 * runner env was layered by WorkspaceConfigFactory already. See ADR-0066.
 */
internal class TagRoutedSession(
    private val delegate: RunnerSession,
    runnerEnv: Map<String, String>,
) : RunnerSession by delegate {
    override val env: Map<String, String> = runnerEnv + delegate.env
}
