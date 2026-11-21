package homer.app.infra

/**
 * The host variable names an agent definition's `env` may reference with `@SYSENV:`: the operator's
 * `agentSysEnvAllowlist` plus the names `globalEnv` references. See ADR-0037.
 */
internal class AgentSysEnvAllowlist(
    private val names: Set<String>,
) {
    /** Why [env] is refused, naming each unlisted variable without reading it; null when all are listed. */
    fun refusal(env: Map<String, String>): String? {
        val unlisted = SysEnvResolver.referencedNames(env) - names
        if (unlisted.isEmpty()) return null
        return "env references host variable(s) ${unlisted.sorted().joinToString { "'$it'" }} with @SYSENV:, " +
            "which neither agentSysEnvAllowlist nor globalEnv names"
    }
}
