package homer.app.executors.llm

import homer.app.infra.LlmOutputJson
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.LlmEvent
import homer.app.models.LlmPrompt
import homer.app.models.TurnStatus
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The verdict is judged over agent-written stdout (ADR-0047 rule 2), so a property mirrors the examples: no
 * ordering of turn ends lets a stream carrying a failed or stopped one succeed. See ADR-0070.
 */
class ClaudeVerdictPropertyTest {
    private val harness = ClaudeHarness()
    private val command = Command.Claude(prompt = LlmPrompt.Prompt("hi"), options = ClaudeCmdOptions())

    /** One turn end of each status the CLI can write. */
    private val turnStatus: Arb<TurnStatus> =
        Arb.int(0, 2).map {
            when (it) {
                0 -> TurnStatus.Success
                1 -> TurnStatus.Failed("error_during_execution")
                else -> TurnStatus.Stopped("error_max_turns")
            }
        }

    /** The raw `result` line a decoded turn end of [status] came from, so events and raw stay co-derived as in production. */
    private fun rawFor(status: TurnStatus): JsonObject =
        when (status) {
            is TurnStatus.Failed -> line("""{"type":"result","subtype":"${status.reason}"}""")
            is TurnStatus.Stopped -> line("""{"type":"result","subtype":"${status.reason}"}""")
            TurnStatus.Success -> line("""{"type":"result","subtype":"success","is_error":false}""")
        }

    private fun line(json: String) = LlmOutputJson.parseToJsonElement(json).jsonObject

    @Test
    fun `a stream succeeds only when every one of its turn ends succeeds`() =
        runTest {
            checkAll(Arb.list(turnStatus, 0..6)) { statuses ->
                val ends = statuses.map { LlmEvent.TurnEnd(it) }
                val raw = statuses.map { rawFor(it) }
                val verdict = harness.inspect(ends, raw, command, exitCode = 0)

                if (statuses.isNotEmpty() && statuses.all { it == TurnStatus.Success }) {
                    assertIs<Inspection.Success>(verdict)
                } else {
                    assertTrue(verdict !is Inspection.Success, "a non-success turn end was hidden: $statuses")
                }
            }
        }
}
