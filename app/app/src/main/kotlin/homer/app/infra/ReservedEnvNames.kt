package homer.app.infra

import homer.app.EnvConstants
import homer.app.exceptions.ValidationException
import io.ktor.http.Parameters

/** Env names a caller-supplied `Param.envName` cannot target: Homer's `HMR_*` namespace (identity spoofing) and
 * host-controlled names like `PATH` / `LD_*` / `BASH_ENV` (a command-execution primitive). Matched case-sensitively. */
object ReservedEnvNames {
    private val exact =
        setOf(
            "PATH",
            "HOME",
            "USER",
            "SHELL",
            "LANG",
            "TZ",
            "PWD",
            "OLDPWD",
            "IFS",
            "BASH_ENV",
            "ENV",
        )
    private val prefixes = listOf("HMR_", "LD_", "JAVA_", "LC_")

    fun isReserved(name: String): Boolean {
        if (name in exact) return true
        return prefixes.any { name.startsWith(it) }
    }
}

/** LangGraph control markers a resume merges back over the resolved declared inputs; an explicit allowlist, not
 * `isReserved`, so a callback-API caller cannot set `PATH` / `LD_*` / `HMR_*`. See ADR-0023. */
private val LANGGRAPH_CONTROL_PARAMS =
    setOf(EnvConstants.LG_THREAD, EnvConstants.LG_RESUME, EnvConstants.LG_INTERRUPT)

/** `HMR_HOOK_*` params a hook fire merges over the target's resolved inputs; an explicit allowlist, like
 * [LANGGRAPH_CONTROL_PARAMS]. See ADR-0107. */
private val HOOK_CONTROL_PARAMS =
    setOf(
        EnvConstants.HOOK_PROCESS_ID,
        EnvConstants.HOOK_AGENT_NAME,
        EnvConstants.HOOK_STATUS,
        EnvConstants.HOOK_FAILURE_REASON,
        EnvConstants.HOOK_TAGS,
        EnvConstants.HOOK_DEPTH,
    )

internal fun hookControlParams(params: Map<String, String>): Map<String, String> = params.filterKeys { it in HOOK_CONTROL_PARAMS }

internal fun reservedControlParams(params: Parameters): Map<String, String> =
    params
        .entries()
        .filter { it.key in LANGGRAPH_CONTROL_PARAMS }
        .associate { (key, values) -> key to values.last() }

internal fun reservedControlParams(params: Map<String, String>): Map<String, String> = params.filterKeys { it in LANGGRAPH_CONTROL_PARAMS }

internal fun isNonControlReserved(name: String): Boolean = ReservedEnvNames.isReserved(name) && name !in LANGGRAPH_CONTROL_PARAMS

/** Rejects [name] as the payload param for a dispatch/continuation ([field] names it): blank, already a key in
 * [params], or a reserved name other than a control marker. Rejected at registration — only control markers survive the merge. */
internal fun validatePayloadParam(
    name: String,
    field: String,
    params: Map<String, String>,
) {
    if (name.isBlank()) throw ValidationException("$field must not be blank", field)
    if (name in params) throw ValidationException("$field '$name' must not be a key in params", field)
    if (isNonControlReserved(name)) {
        throw ValidationException("$field '$name' must not target a reserved environment name", field)
    }
}
