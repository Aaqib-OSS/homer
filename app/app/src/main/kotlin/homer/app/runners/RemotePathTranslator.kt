package homer.app.runners

import homer.app.EnvConstants

/**
 * Produces the env overrides that make a workspace synced to [remoteProjectRoot] usable on the remote host / pod.
 * Translation is env-only (materialized files embed no absolute local paths): every occurrence of [serverProjectRoot]
 * in an env value becomes [remoteProjectRoot] (fixing HMR_WORK_DIR / HMR_ARTIFACTS_DIR / HMR_MCP_CONFIG and PATH's
 * .homer/bin); HMR_AGENT_DIR is overridden to [remoteAgentDir] — it is copied there and lives outside projectRoot, so
 * the prefix swap misses it — and HMR_API_URL to [apiUrl] when set, so remote homer-ask / MCP callbacks reach Homer
 * rather than 127.0.0.1. Returns ONLY changed/added keys, merged onto the env by [homer.app.infra.Workspace.appendToEnv].
 */
class RemotePathTranslator(
    private val serverProjectRoot: String,
    private val remoteProjectRoot: String,
    private val remoteAgentDir: String,
    private val apiUrl: String?,
) {
    fun overrides(env: Map<String, String>): Map<String, String> =
        buildMap {
            env.forEach { (key, value) ->
                if (value.contains(serverProjectRoot)) {
                    put(key, value.replace(serverProjectRoot, remoteProjectRoot))
                }
            }
            put(EnvConstants.AGENT_DIR, remoteAgentDir)
            apiUrl?.takeIf { it.isNotBlank() }?.let { put(EnvConstants.API_URL, it) }
        }
}
