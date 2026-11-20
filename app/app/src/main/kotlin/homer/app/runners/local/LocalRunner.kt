package homer.app.runners.local

import homer.app.EnvConstants
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams

/**
 * Built-in runner, always under the alias "local", that executes commands on Homer's own host via LocalShellExecutor.
 * Its sessions add [defaultApiUrl], when set, as `HMR_API_URL` unless the workspace env already sets one.
 */
class LocalRunner(
    private val defaultApiUrl: String? = null,
) : Runner {
    override val alias: String = ALIAS

    override val isRemote: Boolean = false

    override val runnerEnv: Map<String, String> = emptyMap()

    override val sysEnvBackedEnvNames: Set<String> = emptySet()

    override suspend fun openSession(params: RunnerSessionParams): RunnerSession =
        LocalRunnerSession(
            workdir = params.workspace.workdir,
            maxOutputBytes = params.workspace.maxOutputBytes,
            runnerAlias = alias,
            env =
                if (defaultApiUrl != null && EnvConstants.API_URL !in params.env) {
                    mapOf(EnvConstants.API_URL to defaultApiUrl)
                } else {
                    emptyMap()
                },
        )

    companion object {
        const val ALIAS = "local"
    }
}
