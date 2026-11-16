package homer.app.models

import kotlinx.serialization.Serializable

/**
 * The `data:` payloads of the SSE frames `routes/StreamingRoutes.kt` sends, as `@Serializable` classes so
 * the `dto-contract` check can compare them with their mirrors in `console/types/homer.ts` (ADR-0082).
 * Fields are declared in wire order and encoded with [LlmEventJson] — compact, explicit nulls — so a
 * nullable field is always present as `null` and the bytes are identical to the `buildJsonObject` form.
 */
@Serializable
data class ProcessStatusFrame(
    val processId: Int,
    val status: String,
    val agentName: String,
)

/** `process.cmd`: the command that started (`startedAt`) or, with both null, that nothing runs now. */
@Serializable
data class ProcessCmdFrame(
    val processId: Int,
    val label: String?,
    val startedAt: Long?,
)

@Serializable
data class QuestionAskedFrame(
    val processId: Int,
    val questionId: String,
    val agentName: String,
)

/** `question.answered` and `question.expired`: both carry the question and the process it belongs to. */
@Serializable
data class QuestionFrame(
    val processId: Int,
    val questionId: String,
)

/** `message.queued`, `message.delivered` and `message.undelivered`. */
@Serializable
data class RunMessageFrame(
    val processId: Int,
    val messageId: String,
)

/** `log.chunk`: the bytes since the previous chunk; the end offset rides the frame's `id:`. */
@Serializable
data class LogChunkFrame(
    val content: String,
)

/** `llm.event`: one translated harness event, its cursor riding the frame's `id:`. */
@Serializable
data class LlmStreamFrame(
    val processId: Int,
    val cmdIndex: Int,
    val attemptIndex: Int,
    val event: LlmEvent,
)
