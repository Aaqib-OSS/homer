package homer.app.executors.llm

import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmUsage
import homer.app.models.Translation
import homer.app.models.TurnStatus
import homer.app.models.gateSessionId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** opencode CLI (`opencode run --format json`). Every accessor is untyped and total. */
class OpencodeHarness(
    textCompiler: TextCompiler = DefaultTextCompiler(),
) : LlmHarness<Command.Opencode> {
    override val id: String = "opencode"

    // `step_finish` carries the step's own USD cost.
    override val reportsCost: Boolean = true

    // One step can carry text and a tool call, so a message id is not one model request.
    override val countsModelCalls: Boolean = false
    private val argvBuilder = OpencodeArgvBuilder(textCompiler)

    override fun argv(
        command: Command.Opencode,
        env: Map<String, String>,
    ): List<String> = argvBuilder.build(command.cmd, command.options, env)

    override fun escalated(
        command: Command.Opencode,
        tier: LlmEscalation,
    ): Command.Opencode =
        command.copy(options = command.options.copy(model = tier.model, options = tier.options ?: command.options.options))

    // Each read keeps its running cost total.
    override val translatorKeepsState: Boolean = true

    override fun translator(): (JsonObject) -> Translation = StdoutRead()::translate

    override fun inspect(
        events: List<LlmEvent>,
        raw: List<JsonObject>,
        command: Command.Opencode,
        exitCode: Int,
    ): Inspection =
        inspectTurns(
            events,
            failedReason = "stream contains a failed 'step_finish' event",
            noSuccessReason = "stdout contains no successful 'step_finish' event",
        )

    /**
     * One read of one attempt's stdout. `step_finish` carries its own step's cost, so `TurnEnd.costUsd` is the read's
     * running total: the whole process's cost, summed over every session the stdout carries (ADR-0074).
     */
    private inner class StdoutRead {
        private var total = 0.0

        fun translate(line: JsonObject): Translation =
            when (line.nonBlankString("type")) {
                "step_start" -> {
                    Translation.Events(listOf(LlmEvent.SessionStart(gateSessionId(line.nonBlankString("sessionID")), model = null)))
                }

                "text" -> {
                    text(line)
                }

                "tool_use" -> {
                    toolUse(line)
                }

                "step_finish" -> {
                    stepFinish(line)
                }

                "error" -> {
                    transientError(line)
                }

                else -> {
                    Translation.Ignored
                }
            }

        private fun stepFinish(line: JsonObject): Translation {
            val part = line["part"] as? JsonObject ?: return Translation.Malformed("step_finish without a part object")
            val reason = part.nonBlankString("reason") ?: return Translation.Malformed("step_finish without a reason")
            part.nonBlankString("id") ?: return Translation.Malformed("step_finish without a part id")
            // CWE-682: a negative or non-finite `cost` in agent-written stdout is no cost at all — an
            // over-large literal parses to Infinity and would poison the running total for good.
            val stepCost = (part["cost"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            // CWE-682: a sum that overflows to Infinity leaves the total where it was.
            total = (total + stepCost).takeIf { it.isFinite() } ?: total
            return Translation.Events(listOf(LlmEvent.TurnEnd(status(reason), usage(part["tokens"] as? JsonObject), costUsd = total)))
        }
    }

    private fun text(line: JsonObject): Translation {
        val part = line["part"] as? JsonObject ?: return Translation.Malformed("text without a part object")
        val body = part.nonBlankString("text") ?: return Translation.Malformed("text part without text")
        return Translation.Events(listOf(LlmEvent.AssistantMessage(part.nonBlankString("messageID"), model = null, text = body)))
    }

    private fun toolUse(line: JsonObject): Translation {
        val part = line["part"] as? JsonObject ?: return Translation.Malformed("tool_use without a part object")
        // The tool part's own id is `partID`; `id` is the tool call's.
        val callId = part.nonBlankString("id") ?: return Translation.Malformed("tool part without a call id")
        val name = part.nonBlankString("tool") ?: return Translation.Malformed("tool part without a tool name")
        val state = part["state"] as? JsonObject ?: return Translation.Malformed("tool part without a state object")
        val call = LlmEvent.ToolCall(callId, name, state["input"] ?: JsonPrimitive(""), part.nonBlankString("messageID"))
        return toolResult(state, call)
    }

    /** The tool part's state decides whether the call already has a result to pair it with. */
    private fun toolResult(
        state: JsonObject,
        call: LlmEvent.ToolCall,
    ): Translation =
        when (val status = state.nonBlankString("status")) {
            "completed" -> {
                Translation.Events(listOf(call, LlmEvent.ToolResult(call.id, state["output"] ?: JsonPrimitive(""), isError = false)))
            }

            "error" -> {
                Translation.Events(listOf(call, LlmEvent.ToolResult(call.id, state["error"] ?: JsonPrimitive(""), isError = true)))
            }

            // The call is known, its result is not yet.
            "pending", "running", "streaming" -> {
                Translation.Events(listOf(call))
            }

            else -> {
                Translation.Malformed("tool part with an unknown state '${status ?: ""}'")
            }
        }

    private fun status(reason: String): TurnStatus =
        when (reason) {
            // opencode's own FinishReason set: the run ended normally, or on a budget the agent asked for.
            "stop", "length", "tool-calls", "unknown" -> TurnStatus.Success

            // A provider refusal, and any reason a future CLI adds: fail closed (ADR-0047 rule 3).
            else -> TurnStatus.Failed(reason)
        }

    private fun usage(tokens: JsonObject?): LlmUsage {
        val cache = tokens?.get("cache") as? JsonObject
        return LlmUsage(
            input = tokens.count("input"),
            output = tokens.count("output"),
            cacheRead = cache.count("read"),
            cacheWrite = cache.count("write"),
            reasoning = tokens.count("reasoning"),
        )
    }

    // CWE-345: a negative or unparseable token count in agent-written stdout counts as none.
    private fun JsonObject?.count(key: String): Long = ((this?.get(key) as? JsonPrimitive)?.longOrNull ?: 0L).coerceAtLeast(0L)
}
