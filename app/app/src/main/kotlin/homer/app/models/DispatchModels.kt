package homer.app.models

import kotlinx.serialization.Serializable

/** Body of `POST /api/v1/callback/dispatches`. */
@Serializable
data class CreateDispatchRequest(
    val text: String,
    val options: List<String> = emptyList(),
    val agent: String,
    val answerParam: String = "answer",
    val params: Map<String, String> = emptyMap(),
    val ttlSec: Int? = null,
)

@Serializable
data class CreateDispatchResponse(
    val dispatchId: Int,
    val questionId: String,
)

/**
 * Body of `POST /api/v1/callback/dispatch-groups`: N questions registered as one dispatch, the target started
 * once after the last, carrying every answer in question order. `questions` is capped by
 * `server.dispatch.maxGroupSize`; the other fields mean what they do for a single dispatch. See ADR-0109.
 */
@Serializable
data class CreateDispatchGroupRequest(
    val questions: List<AskQuestionRequest>,
    val agent: String,
    val answerParam: String = "answer",
    val params: Map<String, String> = emptyMap(),
    val ttlSec: Int? = null,
)

/** Response of `POST /api/v1/callback/dispatch-groups`: the group id and its question ids, in order. */
@Serializable
data class CreateDispatchGroupResponse(
    val dispatchId: Int,
    val questionIds: List<String>,
)

/**
 * Body of `POST /api/v1/callback/runs`: fire-and-forget dispatch; an optional `continueAgent` registers a
 * completion continuation (ADR-0023) that resumes the parent when the sub-process ends. `params` go to the
 * sub-agent, `continueParams` to the resumed parent (e.g. the LangGraph interrupt id); ignored without `continueAgent`.
 */
@Serializable
data class CreateRunRequest(
    val agent: String,
    val params: Map<String, String> = emptyMap(),
    val continueAgent: String? = null,
    val continueThread: String? = null,
    val continueParams: Map<String, String> = emptyMap(),
    val resumeParam: String = "HMR_LG_RESUME",
    val resultJsonPath: String? = null,
    val ttlSec: Int? = null,
)

/** Response of `POST /api/v1/callback/runs`; `continuationId` is set only when a continuation was registered. */
@Serializable
data class CreateRunResponse(
    val processId: Int,
    val continuationId: Int? = null,
)

/**
 * Body of `POST /api/v1/callback/joins`: starts every sub in [subs]; one join continuation resumes [continueAgent]
 * after the last sub ends, with results keyed by interrupt id (ADR-0026). `continueParams` go to the resumed parent;
 * its comma-joined interrupt ids in `HMR_LG_INTERRUPT` make the runtime resume with the full result map.
 */
@Serializable
data class CreateJoinRequest(
    val subs: List<JoinSubSpec>,
    val continueAgent: String,
    val continueThread: String? = null,
    val continueParams: Map<String, String> = emptyMap(),
    val resumeParam: String = "HMR_LG_RESUME",
    val ttlSec: Int? = null,
)

/**
 * One sub of a [CreateJoinRequest]: the agent to start fire-and-forget, its params, an optional
 * JsonPath transform applied to its result, and the LangGraph interrupt id its result is keyed
 * under in the resume map.
 */
@Serializable
data class JoinSubSpec(
    val agent: String,
    val params: Map<String, String> = emptyMap(),
    val resultJsonPath: String? = null,
    val interruptId: String,
)

/** Response of `POST /api/v1/callback/joins`: the started sub process ids and the registered join id. */
@Serializable
data class CreateJoinResponse(
    val processIds: List<Int>,
    val continuationId: Int,
)

/**
 * Reference to a large continuation result that exceeded the inline cap. Carried
 * in the resume payload as `resultRef` (with `result` null); the parked parent
 * fetches the full result via `GET /api/v1/callback/results/{token}`. See ADR-0025.
 */
@Serializable
data class ResultRef(
    val processId: Int,
    val path: String,
    val token: String,
    val sizeBytes: Long,
)

@Serializable
data class DispatchResponse(
    val id: Int,
    val questionId: String,
    val status: String,
    val agentName: String,
    val answerParam: String,
    val params: Map<String, String>,
    val originProcessId: Int,
    val originAgentName: String,
    val expiresAt: Long? = null,
    val createdAt: Long,
    val dispatchedAt: Long? = null,
    val dispatchedProcessId: Int? = null,
    val error: String? = null,
    /** `SINGLE` or `GROUP`; see [CreateDispatchGroupRequest]. */
    val kind: String = "SINGLE",
    /** A GROUP's member question ids in answer-aggregation order; empty on a SINGLE dispatch. */
    val questionIds: List<String> = emptyList(),
)

@Serializable
data class DispatchListResponse(
    val dispatches: List<DispatchResponse>,
    val total: Int, // Visible rows matching the filters, before pagination
    val offset: Int,
    val limit: Int,
)

/** Compact dispatch info embedded in a question (console inbox annotation). */
@Serializable
data class DispatchSummary(
    val id: Int,
    val agentName: String,
    val status: String,
    val dispatchedProcessId: Int? = null,
    val error: String? = null,
)
