package homer.app.executors.llm

import homer.app.infra.LlmOutputJson
import homer.app.infra.scanLlmAccounting
import homer.app.models.Command
import homer.app.models.LlmEscalation
import homer.app.models.LlmEvent
import homer.app.models.LlmPrompt
import homer.app.models.LlmUsage
import homer.app.models.OpencodeCmdOptions
import homer.app.models.Translation
import homer.app.models.TurnStatus
import homer.app.models.When
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpencodeHarnessTest {
    private val harness = OpencodeHarness()
    private val command = Command.Opencode(prompt = LlmPrompt.Prompt("hi"))

    private fun line(json: String) = LlmOutputJson.parseToJsonElement(json).jsonObject

    /** One line read on its own, as the first line of a fresh read. */
    private fun translate(line: JsonObject): Translation = harness.translator()(line)

    private fun fixture(name: String): List<LlmEvent> {
        val translate = harness.translator()
        return fixtureText(name)
            .lineSequence()
            .filter { it.isNotBlank() }
            .flatMap { (translate(line(it)) as? Translation.Events)?.events ?: emptyList() }
            .toList()
    }

    private fun stepFinish(
        stepId: String,
        cost: String,
        sessionId: String = "ses_1",
    ) = """{"type":"step_finish","sessionID":"$sessionId","part":{"id":"$stepId","reason":"stop","cost":$cost}}"""

    /** The cumulative cost one fresh read of [lines] charges. */
    private fun charged(
        adapter: OpencodeHarness,
        lines: List<String>,
    ): Double? = scanLlmAccounting(lines.asSequence(), adapter.translator()).cliCostUsd

    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/llm/opencode/$name"))
            .use { it.readBytes().decodeToString() }

    // ========== the adapter contract ==========

    @Test
    fun `the adapter reports cost but counts no model calls`() {
        // `step_finish` carries the step's own USD cost; one step can carry text and a tool
        // call, so a message id is not one model request (ADR-0070, ADR-0061).
        assertEquals("opencode", harness.id)
        assertTrue(harness.reportsCost)
        assertFalse(harness.countsModelCalls)
        assertTrue(harness.translatorKeepsState)
    }

    @Test
    fun `opencode configures no terminal stop, so a non-zero exit stays the failure it is`() {
        assertFalse(harness.terminalStopConfigured(command))
    }

    // ========== argv ==========

    private fun argv(
        options: OpencodeCmdOptions,
        env: Map<String, String> = emptyMap(),
    ): List<String> = harness.argv(Command.Opencode(prompt = LlmPrompt.Prompt("hi"), options = options), env)

    @Test
    fun `bare options produce only the run tail, with no positional prompt`() {
        // The prompt arrives on stdin; a positional message would be read as the prompt instead.
        assertEquals(listOf("opencode", "run", "--format", "json"), argv(OpencodeCmdOptions()))
    }

    @Test
    fun `every option maps to its flag`() {
        val argv =
            argv(
                OpencodeCmdOptions(
                    model = "anthropic/claude-sonnet-4-5",
                    agent = "build",
                    dir = "/work",
                    file = listOf("a.txt", "b.txt"),
                    auto = true,
                ),
            )
        assertEquals("anthropic/claude-sonnet-4-5", argv.argFor("--model"))
        assertEquals("build", argv.argFor("--agent"))
        assertEquals("/work", argv.argFor("--dir"))
        assertEquals(listOf("a.txt", "b.txt"), argv.argForEvery("--file"))
        assertTrue("--auto" in argv)
        assertEquals(listOf("--format", "json"), argv.takeLast(2))
    }

    @Test
    fun `auto stays off unless asked for, and raw options precede the tail`() {
        val argv = argv(OpencodeCmdOptions(options = listOf("--title", "nightly")))
        assertFalse("--auto" in argv)
        assertEquals(listOf("--title", "nightly", "--format", "json"), argv.takeLast(4))
    }

    @Test
    fun `argv resolves placeholders from the workspace env`() {
        val argv = argv(OpencodeCmdOptions(model = "{{M}}", dir = "{{DIR}}"), mapOf("M" to "openai/gpt-5", "DIR" to "/srv"))
        assertEquals("openai/gpt-5", argv.argFor("--model"))
        assertEquals("/srv", argv.argFor("--dir"))
    }

    @Test
    fun `escalated changes the model and replaces raw options only when the tier sets them`() {
        val base =
            Command.Opencode(
                prompt = LlmPrompt.Prompt("hi"),
                options = OpencodeCmdOptions(model = "cheap", agent = "build", options = listOf("--x")),
            )
        val always = When(When.Operator.ALL, listOf("true"))

        val kept = harness.argv(harness.escalated(base, LlmEscalation(model = "strong", whenClause = always)), emptyMap())
        assertEquals("strong", kept.argFor("--model"))
        assertEquals("build", kept.argFor("--agent"))
        assertTrue("--x" in kept)

        val replaced =
            harness.argv(
                harness.escalated(base, LlmEscalation(model = "strong", options = emptyList(), whenClause = always)),
                emptyMap(),
            )
        assertFalse("--x" in replaced)
    }

    // ========== translate ==========

    @Test
    fun `a success stream yields a session start, a tool pair, an assistant message and a successful turn end`() {
        val events = fixture("success.jsonl")

        val starts = events.filterIsInstance<LlmEvent.SessionStart>()
        assertTrue(starts.isNotEmpty())
        assertEquals("ses_3f9a1c2b4d5e6f70", starts.first().sessionId)

        val call = events.filterIsInstance<LlmEvent.ToolCall>().single()
        assertEquals("call_5b2f", call.id)
        assertEquals("bash", call.name)
        assertEquals(JsonPrimitive("ls -la"), (call.input as JsonObject)["command"])
        val result = events.filterIsInstance<LlmEvent.ToolResult>().single()
        assertEquals(call.id, result.id)
        assertFalse(result.isError)
        assertTrue((result.output as JsonPrimitive).content.contains("drwxr-xr-x"))

        assertEquals(2, events.filterIsInstance<LlmEvent.AssistantMessage>().size)

        val ends = events.filterIsInstance<LlmEvent.TurnEnd>()
        assertEquals(2, ends.size)
        assertEquals(TurnStatus.Success, ends.last().status)
        assertEquals(LlmUsage(input = 25080, output = 40, cacheRead = 25000, cacheWrite = 0, reasoning = 0), ends.last().usage)
    }

    @Test
    fun `per-step costs accumulate into the process-cumulative figure ADR-0074 requires`() {
        val ends = fixture("success.jsonl").filterIsInstance<LlmEvent.TurnEnd>()
        assertEquals(0.0121, ends[0].costUsd)
        assertEquals(0.0121 + 0.0034, ends[1].costUsd)
        // The scan keeps the highest figure it saw, so the attempt is charged the whole run.
        assertEquals(0.0121 + 0.0034, scanLlmAccounting(fixtureText("success.jsonl").lineSequence(), harness.translator()).cliCostUsd)
    }

    @Test
    fun `two reads of the same stdout report the same total`() {
        // The accounting scan and the telemetry hand-off each read the attempt's stdout once.
        val lines = fixtureText("success.jsonl").lines()
        assertEquals(0.0121 + 0.0034, charged(harness, lines))
        assertEquals(charged(harness, lines), charged(harness, lines))
    }

    @Test
    fun `a run resuming an opencode session is charged only its own steps`() {
        // One adapter serves every run on the server; both attempts write the same session id.
        val shared = OpencodeHarness()
        val first = listOf(stepFinish("p1", "0.25"), stepFinish("p2", "0.5"))
        val resumed = listOf(stepFinish("p3", "0.125"))
        assertEquals(0.75, charged(shared, first))
        assertEquals(0.125, charged(shared, resumed))
        assertEquals(0.75, charged(shared, first))
    }

    @Test
    fun `a step whose cost would overflow the running total leaves it finite, and the next step still adds`() {
        val translate = harness.translator()
        val totals =
            listOf("1e308", "1e308", "5e307").mapIndexed { i, cost ->
                val events = assertIs<Translation.Events>(translate(line(stepFinish("p$i", cost)))).events
                assertIs<LlmEvent.TurnEnd>(events.single()).costUsd
            }
        assertEquals(listOf(1e308, 1e308, 1.5e308), totals)
    }

    @Test
    fun `a read counts every step, past any former step cap`() {
        val lines = (1..5_000).map { stepFinish("p$it", "1") }
        assertEquals(5_000.0, charged(harness, lines))
    }

    @Test
    fun `the running total is the sum of each step's cost, a negative or non-finite one counting as zero`() =
        runTest {
            val counted = Arb.double(0.0, 1e6, includeNaNs = false).map { it.toString() to it }
            val uncounted = Arb.element("-1", "-0.5", "1e999", "-1e999", "\"free\"", "null").map { it to 0.0 }
            checkAll(Arb.list(Arb.choice(counted, uncounted), 0..40)) { steps ->
                val translate = harness.translator()
                val totals =
                    steps.mapIndexed { i, (cost, _) ->
                        val events = assertIs<Translation.Events>(translate(line(stepFinish("p$i", cost)))).events
                        assertIs<LlmEvent.TurnEnd>(events.single()).costUsd
                    }
                val expected = steps.runningFold(0.0) { total, (_, value) -> total + value }.drop(1)
                assertEquals(expected, totals)
            }
        }

    @Test
    fun `step_start and unrecognised event types are ignored, and a run without one still translates`() {
        assertIs<Translation.Ignored>(translate(line("""{"type":"reasoning","part":{"text":"hmm"}}""")))
        assertIs<Translation.Ignored>(translate(line("""{"type":"session.idle"}""")))
        assertIs<Translation.Ignored>(translate(line("""{"type":""}""")))
        assertIs<Translation.Ignored>(translate(line("""{}""")))
    }

    @Test
    fun `a failed step finish fails inspection`() {
        val events = fixture("step-failed.jsonl")
        assertIs<LlmEvent.TurnEnd>(events.filterIsInstance<LlmEvent.TurnEnd>().single())
        assertIs<TurnStatus.Failed>(events.filterIsInstance<LlmEvent.TurnEnd>().single().status)
        assertIs<Inspection.Malformed>(harness.inspect(events, emptyList(), command))
    }

    @Test
    fun `transient error events do not fail inspection`() {
        val events =
            fixture("success.jsonl") +
                listOf(LlmEvent.TransientError("provider hiccup"))
        assertEquals(Inspection.Success, harness.inspect(events, emptyList(), command))
    }

    @Test
    fun `inspect rejects a stream with no successful step finish`() {
        assertIs<Inspection.Malformed>(harness.inspect(listOf(LlmEvent.SessionStart("s", null)), emptyList(), command))
        assertIs<Inspection.Malformed>(harness.inspect(emptyList(), emptyList(), command))
    }

    // ========== denial paths ==========

    @Test
    fun `a session id with path or shell characters is gated out`() {
        listOf("../../etc/passwd", "ses_1; rm -rf /", "a b", "", "x".repeat(129)).forEach { raw ->
            val events = (translate(line("""{"type":"step_start","sessionID":"$raw"}""")) as Translation.Events).events
            assertNull((events.single() as LlmEvent.SessionStart).sessionId, "id '$raw' should not survive the gate")
        }
        val ok = (translate(line("""{"type":"step_start","sessionID":"ses_3f9a-1C2b"}""")) as Translation.Events).events
        assertEquals("ses_3f9a-1C2b", (ok.single() as LlmEvent.SessionStart).sessionId)
    }

    @Test
    fun `negative and non-numeric usage and cost coerce to zero`() {
        // Only the four `step_finish` lines with a step id translate; the one without is Malformed.
        val text = fixtureText("malformed.jsonl")
        val events = fixture("malformed.jsonl").filterIsInstance<LlmEvent.TurnEnd>()
        assertEquals(4, events.size)
        val unparseable = events.first()
        assertEquals(LlmUsage(output = 5), unparseable.usage)
        // A negative cost is no cost at all, so it leaves the running total where it was.
        assertEquals(0.0, unparseable.costUsd)
        assertEquals(LlmUsage(input = 10, output = 5), events[1].usage)
        assertEquals(0.002, events[1].costUsd)
        assertEquals(0.0027, events[2].costUsd)
        // CWE-682: `1e999` parses to Infinity, which is not a cost either — the running total
        // stays where it was, and so does the figure the accounting scan charges the run.
        assertEquals(0.0027, events[3].costUsd)
        assertEquals(0.0027, scanLlmAccounting(text.lineSequence(), harness.translator()).cliCostUsd)
    }

    @Test
    fun `lines with a missing or wrong-typed field are malformed, never a throw`() {
        // No part object, no text, no call id, no tool name, no state, an unknown state, no
        // reason, no step id: each is a Malformed line, and none of them escapes as an exception.
        listOf(
            """{"type":"text","sessionID":"ses_1"}""",
            """{"type":"text","part":{"messageID":"m"}}""",
            """{"type":"tool_use","part":{"tool":"bash","state":{"status":"completed"}}}""",
            """{"type":"tool_use","part":{"id":"c","state":{"status":"completed"}}}""",
            """{"type":"tool_use","part":{"id":"c","tool":"bash"}}""",
            """{"type":"tool_use","part":{"id":"c","tool":"bash","state":{"status":"weird"}}}""",
            """{"type":"step_finish"}""",
            """{"type":"step_finish","part":{"id":"p","cost":1}}""",
            """{"type":"step_finish","part":{"reason":"stop"}}""",
            """{"type":"step_finish","part":"not an object"}""",
        ).forEach { json ->
            assertIs<Translation.Malformed>(translate(line(json)), json)
        }
    }

    @Test
    fun `a tool part still in flight reports its call and no result`() {
        val expected = listOf(LlmEvent.ToolCall("c", "bash", JsonPrimitive("x")))
        listOf("pending", "running", "streaming").forEach { status ->
            val json = """{"type":"tool_use","part":{"id":"c","tool":"bash","state":{"status":"$status","input":"x"}}}"""
            assertEquals(expected, assertIs<Translation.Events>(translate(line(json))).events)
        }
    }

    @Test
    fun `a failed tool part reports its error as an error result`() {
        val json = """{"type":"tool_use","part":{"id":"c","tool":"bash","state":{"status":"error","error":"boom"}}}"""
        val events = assertIs<Translation.Events>(translate(line(json))).events
        val result = events[1] as LlmEvent.ToolResult
        assertEquals(JsonPrimitive("boom"), result.output)
        assertTrue(result.isError)
    }

    @Test
    fun `any JSON object translates without throwing`() =
        runTest {
            val keys = listOf("type", "sessionID", "part", "cost", "tokens", "reason", "id", "text", "state", "status", "messageID", "tool")
            val values = listOf("", "step_finish", "text", "tool_use", "step_start", "error", "stop", "-1", "1e999", "null")
            checkAll(Arb.list(Arb.element(keys), 0..8), Arb.list(Arb.element(values), 0..8), Arb.string(0..12)) { ks, vs, text ->
                val obj = buildJsonObject { ks.zip(vs).forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
                // `translate` is total by contract: a shape it cannot read is Malformed or Ignored.
                translate(obj)
                translate(line("""{"type":"text","part":{"text":${JsonPrimitive(text)}}}"""))
            }
        }

    // ========== helpers ==========

    private fun List<String>.argFor(flag: String): String {
        val i = indexOf(flag)
        assertTrue(i >= 0, "flag $flag not present; argv=$this")
        return this[i + 1]
    }

    private fun List<String>.argForEvery(flag: String): List<String> = indices.filter { this[it] == flag }.map { this[it + 1] }
}
