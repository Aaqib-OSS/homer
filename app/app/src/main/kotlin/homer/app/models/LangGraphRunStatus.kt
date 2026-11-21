package homer.app.models

import kotlinx.serialization.Serializable

/**
 * Run-identity artifact (`langgraph.json`) the Python runtime writes to the artifacts directory before any graph
 * code runs. It survives a crash, so it is the durable source of a run's thread id. See ADR-0044.
 */
@Serializable
data class LangGraphRunIdentity(
    val threadId: String,
    val module: String,
    val segment: String? = null,
)

/**
 * Per-run LangGraph status, as returned by `GET /api/v1/processes/{id}/langgraph`. The graph fields are
 * populated only when the caller requested the live graph; [mermaid] is deprecated (ADR-0073). See ADR-0044.
 */
@Serializable
data class LangGraphRunStatus(
    val label: String,
    val module: String,
    val threadId: String,
    val segment: String? = null,
    val topology: LangGraphTopology? = null,
    // Edges taken, limited to [topology]. See ADR-0075.
    val traversed: List<LangGraphEdgeRef>? = null,
    val mermaid: String? = null,
    val nodes: Map<String, String>? = null,
    val errors: Map<String, String>? = null,
    val error: String? = null,
    // Sub-run progress behind each pending interrupt id of a parked fan-out node, keyed by node; sparse,
    // null when unreported or enrichment fails.
    val subs: Map<String, List<LangGraphSubProgress>>? = null,
    // Nodes whose interrupt history hit the per-node cap.
    val subsTruncated: List<String>? = null,
)

/**
 * Sub-run progress behind one pending interrupt id of a parked fan-out node,
 * resolved by matching the id against Homer's PENDING continuations. Null
 * fields mean the sub run is not yet started/known. See ADR-0044.
 */
@Serializable
data class LangGraphSubProgress(
    val interruptId: String,
    val processId: Int? = null,
    val agentName: String? = null,
    val status: String? = null,
    // The sub run's per-command progress: one entry per finished execution plus the running command;
    // null when the sub's process is unknown (e.g. purged).
    val commands: List<LangGraphSubCommand>? = null,
)

/**
 * One command of a sub run, for the console's compact per-command indicator
 * row. [state] is `ok` / `failed` / `skipped` for finished executions and
 * `running` for the command currently executing. See ADR-0044.
 */
@Serializable
data class LangGraphSubCommand(
    val label: String,
    val state: String,
)
