package homer.app.executors

import homer.app.exceptions.CommandExecutionException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.Command
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.verify
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [AgentExecutor] tests for the core run path: steps in order, failure handling, continueIfFail,
 * finishExitCode, env and command selection.
 */
class AgentExecutorTest : AgentExecutorTestBase() {
    // ========== Execution Flow Tests ==========

    @Test
    fun `execute should run single command successfully`() =
        runTest {
            val executor = executor()

            executor.execute()

            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            verify { span.setStatus(StatusCode.OK) }
            verify { span.end() }
            assertEquals("cmd", executionInfos.single().kind)
        }

    @Test
    fun `execute should run multiple commands sequentially`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent()

            val executor = executor(agent = agent)

            executor.execute()

            coVerify(exactly = 3) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `execute should stop on first command failure`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // Has 3 commands

            // First command succeeds, second fails
            coEvery { mockExecutor.execute(any(), any(), any()) } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 0),
                    TestFixtures.mockCommandResult(exitCode = 1),
                )

            val executor = executor(agent = agent)

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            // Should execute only 2 commands (first success, second failure, third not executed)
            coVerify(exactly = 2) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `execute should propagate exception on failure`() =
        runTest {
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor = executor()

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertEquals(1, exception.exitCode)
            verify { span.setStatus(StatusCode.ERROR, any()) }
            verify { span.recordException(exception) }
        }

    // ========== Environment Management Tests ==========

    @Test
    fun `execute should use workspace config env`() =
        runTest {
            val testEnv = mapOf("PARAM1" to "value1", "PARAM2" to "value2")
            val workspaceWithEnv =
                TestFixtures.mockWorkspace(
                    config = TestFixtures.mockWorkspaceConfig(env = testEnv),
                )

            val executor = executor(workspace = workspaceWithEnv)

            executor.execute()

            // Verify that executor was called (env is verified through workspace mock)
            coVerify(exactly = 1) {
                mockExecutor.execute(any(), any(), any())
            }
        }

    @Test
    fun `execute should pass workspace config env to each command`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent()
            val testEnv = mapOf("QUERY_PARAM" to "query_value")
            val workspaceWithEnv =
                TestFixtures.mockWorkspace(
                    config = TestFixtures.mockWorkspaceConfig(env = testEnv),
                )

            val executor = executor(agent = agent, workspace = workspaceWithEnv)

            executor.execute()

            // Verify that executor was called 3 times (env is verified through workspace mock)
            coVerify(exactly = 3) {
                mockExecutor.execute(any(), any(), any())
            }
        }

    @Test
    fun `execute forwards its owner into each command's ExecutionContext`() =
        runTest {
            val executor = executor(owner = "ci")

            executor.execute()

            coVerify { mockExecutor.execute(any(), match { it.owner == "ci" }, any()) }
        }

    // ========== Error Handling Tests ==========

    @Test
    fun `execute should throw CommandExecutionException on non-zero exit code`() =
        runTest {
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 42)

            val executor = executor()

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertTrue(exception.message!!.contains("Execution failed"))
        }

    @Test
    fun `execute should include exit code in exception`() =
        runTest {
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 127)

            val executor = executor()

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertEquals(127, exception.exitCode)
        }

    @Test
    fun `execute should include command details in exception`() =
        runTest {
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor = executor()

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertTrue(exception.command.contains("Cmd"))
        }

    // ========== Continue If Fail Tests ==========

    @Test
    fun `execute should continue on failure when continueIfFail is true`() =
        runTest {
            val agent = AgentFixtures.agentWithContinueIfFail() // 3 commands with continueIfFail=true

            // First command fails, but execution should continue
            coEvery { mockExecutor.execute(any(), any(), any()) } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 1),
                    TestFixtures.mockCommandResult(exitCode = 0),
                    TestFixtures.mockCommandResult(exitCode = 0),
                )

            val executor = executor(agent = agent)

            // Should NOT throw exception
            executor.execute()

            // All 3 commands should be executed
            coVerify(exactly = 3) { mockExecutor.execute(any(), any(), any()) }
            verify(exactly = 3) { span.setStatus(StatusCode.OK) }
        }

    @Test
    fun `execute should stop on failure when continueIfFail is false`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands with continueIfFail=false (default)

            // Second command fails
            coEvery { mockExecutor.execute(any(), any(), any()) } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 0),
                    TestFixtures.mockCommandResult(exitCode = 1),
                )

            val executor = executor(agent = agent)

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            // Only 2 commands should be executed (third not executed due to failure)
            coVerify(exactly = 2) { mockExecutor.execute(any(), any(), any()) }
        }

    // ========== finishExitCode Tests ==========

    @Test
    fun `execute should finish early when command exits with finishExitCode`() =
        runTest {
            val agent = AgentFixtures.agentWithFinishExitCode(finishExitCode = 128)

            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 128)

            val executor = executor(agent = agent)

            // Should NOT throw — job finishes cleanly
            executor.execute()

            // Only first command executed, second skipped
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            verify { span.setStatus(StatusCode.OK) }
        }

    @Test
    fun `execute should continue when finishExitCode matches but continueIfFail is true`() =
        runTest {
            val agent = AgentFixtures.agentWithFinishExitCodeAndContinueIfFail(finishExitCode = 128)

            coEvery { mockExecutor.execute(any(), any(), any()) } returnsMany
                listOf(
                    TestFixtures.mockCommandResult(exitCode = 128),
                    TestFixtures.mockCommandResult(exitCode = 0),
                )

            val executor = executor(agent = agent)

            executor.execute()

            // Both commands executed — continueIfFail bypasses early finish
            coVerify(exactly = 2) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `execute should fail when non-zero exit code does not match finishExitCode`() =
        runTest {
            val agent = AgentFixtures.agentWithFinishExitCode(finishExitCode = 128)

            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            val executor = executor(agent = agent)

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertEquals(1, exception.exitCode)
        }

    // ========== Result to Env Mapping Tests ==========

    @Test
    fun `execute should call workspace appendToEnv with mapped env vars`() =
        runTest {
            val executor = executor()

            executor.execute()

            // Verify workspace.appendToEnv is called (even if with empty map for commands without resultEnvParam)
            verify(atLeast = 1) { mockWorkspace.appendToEnv(any()) }
        }

    // ========== Command Selection Tests ==========

    @Test
    fun `execute runs only the selected command when commandIndices is set`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands: Cmd, Script, Claude

            val executor = executor(agent = agent, commandIndices = listOf(1))

            executor.execute()

            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            // The only executor invocation should be for the Script command at index 1
            coVerify(exactly = 1) {
                mockExecutor.execute(
                    match { it is Command.Script },
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `execute runs nothing when commandIndices is empty`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent()

            val executor = executor(agent = agent, commandIndices = emptyList())

            executor.execute()

            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }
        }
}
