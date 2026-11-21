package homer.app.infra

import homer.app.EnvConstants.ARTIFACTS_DIR
import homer.app.EnvConstants.CALLBACK_TOKEN
import homer.app.EnvConstants.INVOCATION_CHAIN
import homer.app.EnvConstants.PARENT_PROCESS_ID
import homer.app.EnvConstants.PROCESS_ID
import homer.app.EnvConstants.ROOT_PROCESS_ID
import homer.app.EnvConstants.WORK_DIR
import homer.app.exceptions.UnresolvedLockKeyPlaceholderException
import homer.app.exceptions.WorkspaceException
import java.nio.file.Path
import kotlin.io.path.Path
import homer.app.models.Workspace as WorkspaceAgentConfig

class WorkspaceConfigFactory(
    private val workspaceRoot: String,
    private val globalEnv: Map<String, String>,
    private val textCompiler: TextCompiler,
    private val parentEnvAllowlist: List<String> = emptyList(),
    // Injected for testability; production wiring passes `System::getenv`.
    private val hostEnvSupplier: () -> Map<String, String> = { emptyMap() },
    private val maxOutputBytes: Long = Long.MAX_VALUE,
    // `globalEnv` keys sourced from the host env via `@SYSENV:`: that declares them credentials, so every run
    // redacts them.
    private val sysEnvBackedGlobalEnvNames: Set<String> = emptySet(),
    // Same contract, per runner alias: names behind a runner's `env` whose raw
    // value referenced `@SYSENV:`. Resolved by alias in createWorkspaceConfig.
    private val sysEnvBackedRunnerEnvNames: Map<String, Set<String>> = emptyMap(),
) {
    fun createWorkspaceConfig(
        processId: Int,
        agentEnv: Map<String, String>,
        lockKeyTemplate: String,
        queryParams: Map<String, String>,
        workspaceAgentConfig: WorkspaceAgentConfig,
        invocationChain: List<String> = emptyList(),
        ancestorLockKeys: List<String> = emptyList(),
        secretEnvNames: Set<String> = emptySet(),
        parentProcessId: Int? = null,
        rootProcessId: Int? = null,
        // Per-runner env, layered between globalEnv and agentEnv; remote runners carry their host's values (ADR-0065).
        runnerEnv: Map<String, String> = emptyMap(),
        // False for remote runners: allowlisted host env keys describe Homer's host, not theirs (ADR-0065).
        includeParentEnv: Boolean = true,
        // Resolves [sysEnvBackedRunnerEnvNames]; the factory holds the alias-keyed
        // map because the `@SYSENV:` placeholders are resolved before it is built.
        runnerAlias: String = "",
    ): WorkspaceConfig {
        // A blank alias would silently drop every @SYSENV:-backed runner env name from the redacted set.
        require(runnerAlias.isNotBlank() || sysEnvBackedRunnerEnvNames.isEmpty()) {
            "runnerAlias must not be blank when sysEnvBackedRunnerEnvNames is non-empty"
        }
        val hostEnv = hostEnvSupplier()
        val env =
            composeEnv(
                hostEnv,
                includeParentEnv,
                runnerEnv,
                agentEnv,
                queryParams,
                processId,
                invocationChain,
                parentProcessId,
                rootProcessId,
            )

        val serverRoot = Path(textCompiler.replaceArguments(workspaceRoot, globalEnv))
        val requestedRoot =
            serverRoot.resolve(textCompiler.replaceArguments(workspaceAgentConfig.path, env))
        val projectRoot = resolveProjectRoot(serverRoot, requestedRoot, workspaceAgentConfig.path)
        val artifacts: Path = projectRoot.resolve("artifacts")
        val workdir: Path = projectRoot.resolve("workdir")
        return WorkspaceConfig(
            serverRoot = serverRoot,
            projectRoot = projectRoot,
            artifacts = artifacts,
            workdir = workdir,
            env =
                env +
                    buildMap {
                        put(ARTIFACTS_DIR, artifacts.toString())
                        put(WORK_DIR, workdir.toString())
                    },
            clearAfterFinish = workspaceAgentConfig.cleanAfterFinish,
            lockKey = renderTemplatedKey(lockKeyTemplate, env, textCompiler),
            ancestorLockKeys = ancestorLockKeys,
            maxOutputBytes = maxOutputBytes,
            parentEnvLayerNames = if (includeParentEnv) parentEnvLayerNames(hostEnv, env) else emptySet(),
            // Union of declared secrets, host-sourced globals and the callback token Homer appends to env after this
            // call; the redactor resolves names at redact time, so the name still hits.
            secretEnvNames =
                secretEnvNames +
                    sysEnvBackedGlobalEnvNames +
                    (sysEnvBackedRunnerEnvNames[runnerAlias] ?: emptySet()) +
                    CALLBACK_TOKEN,
        )
    }

    /**
     * The run's env, lowest precedence first: the allowlisted host env (only when
     * [includeParentEnv]), globalEnv, [runnerEnv], [agentEnv], [queryParams], then the
     * `HMR_*` ids Homer sets itself.
     */
    private fun composeEnv(
        hostEnv: Map<String, String>,
        includeParentEnv: Boolean,
        runnerEnv: Map<String, String>,
        agentEnv: Map<String, String>,
        queryParams: Map<String, String>,
        processId: Int,
        invocationChain: List<String>,
        parentProcessId: Int?,
        rootProcessId: Int?,
    ): Map<String, String> =
        buildMap {
            // Lowest precedence: only the operator-allowlisted host env keys.
            if (includeParentEnv) {
                parentEnvAllowlist.forEach { key ->
                    hostEnv[key]?.let { put(key, it) }
                }
            }
            putAll(globalEnv)
            putAll(runnerEnv)
            putAll(agentEnv)
            putAll(queryParams)
            put(PROCESS_ID, processId.toString())
            put(INVOCATION_CHAIN, invocationChain.joinToString(","))
            // Cleared at the root, not just skipped: an agent `env:` entry or an
            // allowlisted host variable under this name must not pose as a parent.
            if (parentProcessId != null) put(PARENT_PROCESS_ID, parentProcessId.toString()) else remove(PARENT_PROCESS_ID)
            // Same removal rule as the parent id: absent — not empty — at the root.
            if (rootProcessId != null) put(ROOT_PROCESS_ID, rootProcessId.toString()) else remove(ROOT_PROCESS_ID)
        }

    /**
     * The per-run directory [requestedRoot] names (from the agent's [workspacePath]
     * template), refused unless it is a real, symlink-free subdirectory of [serverRoot].
     */
    private fun resolveProjectRoot(
        serverRoot: Path,
        requestedRoot: Path,
        workspacePath: String,
    ): Path {
        val projectRoot: Path =
            try {
                PathSafety.assertContained(serverRoot, requestedRoot)
            } catch (e: SecurityException) {
                throw WorkspaceException(
                    "workspace path '$workspacePath' resolves outside the workspace root",
                    e,
                )
            }
        // Strict descent: a template normalizing to the server root would let a purge wipe every run (ADR-0053).
        // serverRoot is never canonicalized, so behind a symlinked root the canonical comparison alone misses.
        val normalizedServerRoot = serverRoot.toAbsolutePath().normalize()
        if (projectRoot == normalizedServerRoot || requestedRoot.toAbsolutePath().normalize() == normalizedServerRoot) {
            throw WorkspaceException(
                "workspace path '$workspacePath' must resolve to a subdirectory of the workspace root, not the root itself",
            )
        }
        // A per-run path through a symlink component would alias two runs onto one directory (ADR-0053); the lexical
        // requestedRoot is passed so the walk lstat's each real component below serverRoot.
        try {
            PathSafety.assertNoSymlinkComponentsBelow(serverRoot, requestedRoot)
        } catch (e: SecurityException) {
            throw WorkspaceException(
                "workspace path '$workspacePath' resolves through a symlink; " +
                    "per-run workspace paths must not traverse symlinks",
                e,
            )
        }
        return projectRoot
    }

    /**
     * Allowlist keys still holding the host value they were layered with. A cross-runner step strips exactly these
     * for a remote target; a key a higher layer re-set is the operator's own and survives (ADR-0065).
     */
    private fun parentEnvLayerNames(
        hostEnv: Map<String, String>,
        env: Map<String, String>,
    ): Set<String> = parentEnvAllowlist.filterTo(mutableSetOf()) { key -> hostEnv[key] != null && env[key] == hostEnv[key] }
}

/**
 * Renders a templated key over [env], refusing one whose `{{NAME}}` placeholders the env cannot resolve (ADR-0098).
 * Only the template's placeholder names are checked, never the rendered text: a param value holding `{{X}}` is data.
 */
internal fun renderTemplatedKey(
    template: String,
    env: Map<String, String>,
    compiler: TextCompiler,
): String {
    val unresolved = placeholderNames(template).filterNot { it in env }
    if (unresolved.isNotEmpty()) throw UnresolvedLockKeyPlaceholderException(unresolved)
    return compiler.replaceArguments(template, env)
}

data class WorkspaceConfig(
    val serverRoot: Path,
    val projectRoot: Path,
    val artifacts: Path,
    val workdir: Path,
    val env: Map<String, String>,
    val clearAfterFinish: Boolean,
    val lockKey: String,
    // Lock keys held by ancestors in this run's invocation chain; used for
    // acceptParentLock pass-through and forwarded to descendants by RunAgentExecutor.
    val ancestorLockKeys: List<String> = emptyList(),
    val maxOutputBytes: Long = Long.MAX_VALUE,
    // Env var names whose values should be redacted from telemetry (see
    // LlmHarnessExecutor's SecretRedactor use); populated from `Param.secret` inputs.
    val secretEnvNames: Set<String> = emptySet(),
    // Allowlisted host-env keys still carrying the host value they were layered with; RunnerCmdExecutor strips
    // exactly these for a remote runnerCmd target (ADR-0065).
    val parentEnvLayerNames: Set<String> = emptySet(),
)
