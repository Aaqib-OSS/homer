package homer.app.executors

import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.OutputCapExceededException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.LlmAccounting
import homer.app.models.When
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [AgentExecutor] tests for the step records: one write per step boundary and the ExecutionInfo each step leaves. */
class AgentExecutorStepRecordsTest : AgentExecutorTestBase() {
    // ========== Step-boundary Tests (one write, one event per boundary) ==========

    @Test
    fun `each successful boundary makes exactly one completeStep call and one setCurrentCommand for the run`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands

            executor(agent = agent).execute()

            // One write per step boundary — not a split addExecution + clear pair.
            coVerify(exactly = 3) { mockProcessManager.completeStep(any(), any(), any(), any()) }
            // Only the run's first step is set up front; the rest ride a prior boundary.
            coVerify(exactly = 1) { mockProcessManager.setCurrentCommand(testProcessId, any(), any()) }
            assertEquals(listOf("cmd", "script", "claude"), executionInfos.map { it.label })
            // The last boundary clears the command; no step follows it.
            val last = slot<ExecutionInfo>()
            coVerify { mockProcessManager.completeStep(testProcessId, capture(last), null, null) }
            assertEquals("claude", last.captured.label)
        }

    @Test
    fun `a step boundary hands the next step's label and start to completeStep`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent()

            executor(agent = agent).execute()

            // The first boundary names the second step; its recorded start is the one
            // the second step's own ExecutionInfo carries (the same clock reading).
            val firstStart = slot<Long>()
            coVerify {
                mockProcessManager.completeStep(
                    testProcessId,
                    match { it.label == "cmd" },
                    nextLabel = "script",
                    nextStart = capture(firstStart),
                )
            }
            val secondStart = slot<ExecutionInfo>()
            coVerify { mockProcessManager.completeStep(testProcessId, capture(secondStart), "claude", any()) }
            assertEquals("script", secondStart.captured.label)
            assertEquals(firstStart.captured, secondStart.captured.start)
        }

    @Test
    fun `a step ending the run clears the current command at its boundary`() =
        runTest {
            // The default agent's single command ends the run: the boundary must clear,
            // never set a phantom next command.
            executor().execute()

            coVerify(exactly = 1) { mockProcessManager.completeStep(testProcessId, any(), null, null) }
        }

    @Test
    fun `a step whose command throws clears the current command at its boundary even with continueIfFail`() =
        runTest {
            // continueIfFail covers a non-zero exit code, which reaches the outcome as a
            // Success — not a command that throws. A CancellationException (a runner-enforced
            // timeout), an EvalError or an OutputCapExceededException is rethrown
            // unconditionally, so the run ends and the boundary must clear, never hand the
            // next step over as the current command.
            val agent = AgentFixtures.agentWithContinueIfFail() // 3 steps, all continueIfFail=true

            // The thrown CancellationException is not our job being cancelled, so it
            // propagates as a failure (ProcessExecutor's runner-enforced-timeout case).
            coEvery { mockExecutor.execute(any(), any(), any()) } throws CancellationException("ttl exceeded")

            assertThrows<CancellationException> {
                executor(agent = agent).execute()
            }

            // The failed step is recorded and its boundary clears; the second step
            // must not be announced as the current command.
            coVerify(exactly = 1) { mockProcessManager.completeStep(any(), any(), any(), any()) }
            coVerify { mockProcessManager.completeStep(testProcessId, capture(executionInfos), null, null) }
            assertTrue(!executionInfos[0].skipped, "the failed step is recorded, not skipped")
        }

    @Test
    fun `a finishExitCode boundary clears the current command instead of announcing the next step`() =
        runTest {
            // The first command exits with the agent's finishExitCode, so the run ends
            // there: the boundary must clear, never hand the second step over as the
            // current command of a run that is about to finish.
            val agent = AgentFixtures.agentWithFinishExitCode(finishExitCode = 128)

            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 128)

            executor(agent = agent).execute()

            // The second step never ran; regressing the isFinishCode stop to false
            // would announce it here and call completeStep twice.
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            coVerify(exactly = 1) { mockProcessManager.completeStep(any(), any(), any(), any()) }
            coVerify { mockProcessManager.completeStep(testProcessId, capture(executionInfos), null, null) }
            assertEquals("cmd", executionInfos[0].label)
        }

    @Test
    fun `a skipped step's boundary hands over to the next step`() =
        runTest {
            val agent =
                Agent(
                    name = "skip_handover_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("false-expr")),
                            ),
                            Command.Cmd(cmd = listOf("echo", "second"), timeout = 60),
                        ),
                    env = emptyMap(),
                )
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 1, stdOut = arg(1), stdErr = arg(2))
            }

            executor(agent = agent).execute()

            // The skipped step's boundary is a single completeStep that sets the second
            // command as current; the second (final) boundary clears.
            val skipStart = slot<Long>()
            coVerify {
                mockProcessManager.completeStep(
                    testProcessId,
                    match { it.skipped },
                    nextLabel = "cmd",
                    nextStart = capture(skipStart),
                )
            }
            coVerify(exactly = 2) { mockProcessManager.completeStep(any(), any(), any(), any()) }
            // The handover is the same one a success boundary computes: the start the
            // skipped boundary hands over is the one the second step's own ExecutionInfo
            // carries (the shared nextBoundary helper).
            val secondStart = slot<ExecutionInfo>()
            coVerify { mockProcessManager.completeStep(testProcessId, capture(secondStart), null, null) }
            assertEquals(skipStart.captured, secondStart.captured.start)
        }

    // ========== ExecutionInfo Tests ==========

    @Test
    fun `each row records its command's index in the agent's commands`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands

            executor(agent = agent).execute()

            assertEquals(listOf(0, 1, 2), executionInfos.map { it.commandIndex })
        }

    @Test
    fun `a run restricted to one command records that command's own index`() =
        runTest {
            // A debug run of the agent's third command: its only row is command 2's, so the
            // row's list position (0) must not stand in for the index the streams are asked for.
            val agent = AgentFixtures.multiCommandAgent()

            executor(agent = agent, commandIndices = listOf(2)).execute()

            assertEquals(listOf(2), executionInfos.map { it.commandIndex })
        }

    @Test
    fun `a skipped row records the index of the command it skipped`() =
        runTest {
            val agent =
                Agent(
                    name = "skip_index_test",
                    commands =
                        listOf(
                            Command.Cmd(cmd = listOf("echo", "first"), timeout = 60),
                            Command.Cmd(
                                cmd = listOf("echo", "second"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("false-expr")),
                            ),
                        ),
                    env = emptyMap(),
                )
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 1, stdOut = arg(1), stdErr = arg(2))
            }

            executor(agent = agent).execute()

            assertEquals(listOf(0, 1), executionInfos.map { it.commandIndex })
            assertTrue(executionInfos[1].skipped)
        }

    @Test
    fun `llmAccounting on the CommandResult lands on the ExecutionInfo`() =
        runTest {
            // Same arrangement as the "actual paths" test in AgentExecutorArtifactsTest, but the returned
            // CommandResult also carries llmAccounting, which must ride onto the
            // captured ExecutionInfo unchanged.
            val accounting = LlmAccounting(sessionId = "s1", costUsd = 0.2, source = CostSource.CLI, fallbackReason = "http 404")

            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0).copy(llmAccounting = accounting)

            val executor = executor()

            executor.execute()

            assertEquals(1, executionInfos.size)
            assertEquals(accounting, executionInfos[0].llmAccounting)
        }

    @Test
    fun `execute flags outputTruncated on ExecutionInfo when OutputCapExceededException is thrown`() =
        runTest {
            coEvery { mockExecutor.execute(any(), any(), any()) } throws
                OutputCapExceededException("Output cap exceeded: 1024 bytes.")

            val executor = executor()

            assertThrows<OutputCapExceededException> {
                executor.execute()
            }

            assertEquals(1, executionInfos.size)
            assertTrue(
                executionInfos[0].outputTruncated,
                "expected outputTruncated=true when OutputCapExceededException is thrown",
            )
            // OutputCapExceededException is not a CommandExecutionException, so the
            // recorded resultCode falls back to -1 and no skip flag is set.
            assertEquals(-1, executionInfos[0].resultCode)
            assertEquals(false, executionInfos[0].skipped)
        }

    @Test
    fun `execute records resultCode -1 when a non-Exception Throwable escapes the try`() =
        runTest {
            // An Error (not an Exception) skips the `catch (e: Exception)` block entirely.
            coEvery { mockExecutor.execute(any(), any(), any()) } throws NotImplementedError("boom")

            val executor = executor()

            assertThrows<NotImplementedError> {
                executor.execute()
            }

            // The Error must not record a success-looking ExecutionInfo: the default
            // outcome should carry resultCode -1, not 0.
            assertEquals(1, executionInfos.size)
            assertEquals(-1, executionInfos[0].resultCode)
            assertEquals(null, executionInfos[0].errMsg)
        }
}
