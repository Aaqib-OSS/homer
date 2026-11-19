package homer.app.executors.llm

import homer.app.infra.OutputLineFilter
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.Translation
import homer.app.models.TurnStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One LLM coding-harness CLI, as seen by [LlmHarnessExecutor]. Adapters are stateless;
 * per-read state lives only in a [translator]. See ADR-0070.
 */
interface LlmHarness<C> where C : Command, C : Command.LlmCommand {
    /** Equals the command's [homer.app.models.kind] (`claude`, `codex`, `opencode`). */
    val id: String

    /** Whether the CLI reports a USD cost per run. False makes an unpriced attempt record CostSource.NONE. */
    val reportsCost: Boolean

    /** True when each distinct message id is one model request; false settles session spend on a stable figure. See ADR-0070. */
    val countsModelCalls: Boolean

    fun argv(
        command: C,
        env: Map<String, String>,
    ): List<String>

    /** Extra process env beyond the workspace env and the trace parent the executor adds. */
    fun processEnv(command: C): Map<String, String> = emptyMap()

    fun stdOutFilter(command: C): LlmStdOutFilter? = null

    /** True only for a harness that opted into a terminal stop; only then does the executor inspect a non-zero exit's stdout, and only [Inspection.Terminal] is honoured. See ADR-0070. */
    fun terminalStopConfigured(command: C): Boolean = false

    /** [command] as one escalation tier runs it: its model, and its raw CLI options when the tier sets them. See ADR-0079. */
    fun escalated(
        command: C,
        tier: LlmEscalation,
    ): C

    /** A fresh translation for one read of one attempt's stdout. Total: never throws; one line may yield several events. */
    fun translator(): (JsonObject) -> Translation

    /** Judges an attempt; [raw] carries the checks the canonical model does not. Consulted on exit 0, and on a non-zero exit where only [Inspection.Terminal] is honoured. */
    fun inspect(
        events: List<LlmEvent>,
        raw: List<JsonObject>,
        command: C,
    ): Inspection
}

sealed interface Inspection {
    data object Success : Inspection

    data class Malformed(
        val reason: String,
    ) : Inspection

    /** A terminal, recoverable stop the agent opted into; returned with this exit code, never retried. */
    data class Terminal(
        val exitCode: Int,
    ) : Inspection
}

/** A stdout filter that can report what it removed, for the attempt's stderr summary. */
interface LlmStdOutFilter : OutputLineFilter {
    fun dropped(): List<DroppedSubtype>
}

/** The string at [key], or null when it is absent, not a primitive, or blank. */
internal fun JsonObject.nonBlankString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** The message of a CLI error line, from its `error` object or from the line itself. */
internal fun JsonObject.errorMessage(): String? = (this["error"] as? JsonObject)?.nonBlankString("message") ?: nonBlankString("message")

/** A CLI error line, as the transient-error event every harness reports it with. */
internal fun transientError(line: JsonObject): Translation =
    Translation.Events(listOf(LlmEvent.TransientError(line.errorMessage() ?: "no message")))

/** The verdict harnesses that end a turn share: a failed turn end fails the attempt, and a stream with no successful one cannot gate a zero exit. [failedReason]/[noSuccessReason] name the CLI's own events. */
internal fun inspectTurns(
    events: List<LlmEvent>,
    failedReason: String,
    noSuccessReason: String,
): Inspection {
    val failed = events.firstOrNull { it is LlmEvent.TurnEnd && it.status is TurnStatus.Failed } as? LlmEvent.TurnEnd
    if (failed != null) return Inspection.Malformed("$failedReason (${(failed.status as TurnStatus.Failed).reason})")
    if (events.none { it is LlmEvent.TurnEnd && it.status == TurnStatus.Success }) return Inspection.Malformed(noSuccessReason)
    return Inspection.Success
}
