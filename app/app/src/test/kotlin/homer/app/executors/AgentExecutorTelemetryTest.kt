package homer.app.executors

import homer.app.exceptions.CommandExecutionException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.verify
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** [AgentExecutor] tests for the per-command spans. */
class AgentExecutorTelemetryTest : AgentExecutorTestBase() {
    @Test
    fun `execute stamps session metadata onto command spans`() =
        runTest {
            every { mockSession.metadata } returns mapOf("pod.name" to "homer-123-abc")

            executor().execute()

            verify { span.setAttribute("pod.name", "homer-123-abc") }
        }

    @Test
    fun `execute should create span for each command`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands

            val executor = executor(agent = agent)

            executor.execute()

            verify(exactly = 3) { mockTelemetryService.tracer.spanBuilder(any()) }
            verify(exactly = 3) { span.end() }
            // The span is named after the command's INDEX, so the step's own name has to ride
            // along as an attribute — for a script step it is the only place it appears at all.
            verify { span.setAttribute("command.label", "cmd") }
            verify { span.setAttribute("command.label", "script") }
            verify { span.setAttribute("command.label", "claude") }
        }

    @Test
    fun `execute should set command type attribute`() =
        runTest {
            val executor = executor()

            executor.execute()

            verify { span.setAttribute("cmd.type", "Cmd") }
        }

    @Test
    fun `execute should set exit code attribute`() =
        runTest {
            val executor = executor()

            executor.execute()

            verify { span.setAttribute("output.exitCode", "0") }
        }

    @Test
    fun `execute should set span status to OK on success`() =
        runTest {
            val executor = executor()

            executor.execute()

            verify { span.setStatus(StatusCode.OK) }
        }

    @Test
    fun `execute should set span status to ERROR on failure`() =
        runTest {
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor = executor()

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            verify { span.setStatus(StatusCode.ERROR, any()) }
        }

    @Test
    fun `execute should record exception in span`() =
        runTest {
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor = executor()

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            verify { span.recordException(any<CommandExecutionException>()) }
        }

    @Test
    fun `execute should end span after each command`() =
        runTest {
            val executor = executor()

            executor.execute()

            verify { span.end() }
        }

    @Test
    fun `execute should set output env span attribute`() =
        runTest {
            val executor = executor()

            executor.execute()

            // Verify output.env attribute is set
            verify { span.setAttribute("output.env", any<String>()) }
        }
}
