package homer.app.runners

import homer.app.EnvConstants

/**
 * The env overrides that make a workspace synced to [remoteProjectRoot] usable remotely (env-only, ADR-0032): every
 * [serverProjectRoot] in an env value becomes [remoteProjectRoot], HMR_AGENT_DIR becomes [remoteAgentDir] and HMR_API_URL
 * [apiUrl] when set. Returns only the changed or added keys, for [homer.app.infra.Workspace.appendToEnv].
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
