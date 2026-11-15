package homer.app.models

/**
 * What every start path asks of an agent run, shared by the `!<agent>` sub-run port
 * (`homer.app.executors.SubRunStarter`) and `homer.app.services.AgentRunStarter`.
 */
data class RunStartRequest(
    val agent: Agent,
    val invocationChain: List<String>,
    val source: String,
    val ancestorLockKeys: List<String> = emptyList(),
    val owner: String? = null,
    // The run that caused this one, already validated by the caller (a live
    // RunAgent/dispatch/continuation origin, or an external caller's visible
    // parent). Stored with the row and exported as HMR_PARENT_PROCESS_ID. ADR-0058.
    val parentProcessId: Int? = null,
    // The flow's first run: `parent.rootProcessId ?: parent.id`, derived from
    // the same already-validated parent. Stored with the row and exported as
    // HMR_ROOT_PROCESS_ID. Null for a run with no parent. ADR-0058.
    val rootProcessId: Int? = null,
    val resolveInputs: suspend () -> Map<String, String>,
)
