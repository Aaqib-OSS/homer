package homer.app.runners

import homer.app.infra.WorkspaceConfig
import io.opentelemetry.context.Context

/**
 * Execution backend that produces per-run sessions.
 * Implementations map agents to concrete execution environments
 * (local process, Kubernetes pod, ...).
 */
interface Runner {
    val alias: String

    /**
     * Whether commands run on a machine other than Homer's own host. A remote runner's session env, and a
     * cross-runner `runnerCmd` step's env on it, carry no `parentEnvAllowlist` host-env keys (ADR-0065).
     */
    val isRemote: Boolean

    /**
     * Operator-declared per-runner env (from the runner's `config.env`),
     * layered between `globalEnv` and agent `env` when the workspace config
     * is composed. See SERVER.md, "Remote runners".
     */
    val runnerEnv: Map<String, String>

    /**
     * Names of [runnerEnv] keys whose raw config values used `@SYSENV:`. [RunnerCmdExecutor] adds them to the
     * telemetry redactor when `command.runner` is not the agent's own runner (ADR-0047 rule 7, CWE-200).
     */
    val sysEnvBackedEnvNames: Set<String>

    suspend fun openSession(params: RunnerSessionParams): RunnerSession
}

data class RunnerSessionParams(
    val processId: Int,
    val agentName: String,
    val workspace: WorkspaceConfig,
    val env: Map<String, String>,
    val parentContext: Context,
    /**
     * The flow's first run id, Homer-stamped (ADR-0058) — never agent-writable.
     * `runnerAffinity` keys its memory on it. Null for a run with no flow root.
     */
    val rootProcessId: Int? = null,
)
