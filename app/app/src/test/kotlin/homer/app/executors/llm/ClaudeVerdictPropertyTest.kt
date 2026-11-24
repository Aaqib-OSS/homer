package homer.app.executors.llm

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

    @Test
    fun `a stream succeeds only when every one of its turn ends succeeds`() =
        runTest {
            checkAll(Arb.list(turnStatus, 0..6)) { statuses ->
                val verdict = harness.inspect(statuses.map { LlmEvent.TurnEnd(it) }, emptyList(), command, exitCode = 0)

                if (statuses.isNotEmpty() && statuses.all { it == TurnStatus.Success }) {
                    assertIs<Inspection.Success>(verdict)
                } else {
                    assertTrue(verdict !is Inspection.Success, "a non-success turn end was hidden: $statuses")
                }
            }
        }
}
