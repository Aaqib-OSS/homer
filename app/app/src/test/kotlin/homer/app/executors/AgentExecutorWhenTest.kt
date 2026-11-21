package homer.app.executors

import homer.app.exceptions.CommandExecutionException
import homer.app.fixtures.TestFixtures
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ExecutionInfo
import homer.app.models.When
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.StatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [AgentExecutor] tests for `when:` predicates. */
class AgentExecutorWhenTest : AgentExecutorTestBase() {
    // ========== When-predicate Tests ==========

    @Test
    fun `execute skips command when 'when' predicate evaluates false`() =
        runTest {
            val agent =
                Agent(
                    name = "skip_test",
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

            // Predicate evaluates false (exit 1)
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 1, stdOut = arg(1), stdErr = arg(2))
            }

            val executor = executor(agent = agent)

            executor.execute()

            // Only command 1 (the un-gated one) was executed
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            // Two execution infos recorded (skipped + ran)
            assertEquals(2, executionInfos.size)
            val skippedInfo = executionInfos[0]
            assertTrue(skippedInfo.skipped)
            assertEquals(-1, skippedInfo.resultCode)
            assertTrue(
                skippedInfo.errMsg!!.contains("when.all"),
                "expected skip reason to mention 'when.all', got: ${skippedInfo.errMsg}",
            )
            assertEquals("cmd", skippedInfo.kind)
            verify { span.setAttribute("cmd.skipped", true) }
        }

    @Test
    fun `execute redacts the skipped when predicate before it reaches errMsg`() =
        runTest {
            // CWE-532: the Skip reason interpolates the raw expression, so a credential in a `when`
            // expression must not survive into the skipped step's ExecutionInfo.errMsg.
            val secretToken = "sup3r-secret-token-value"
            val agent =
                Agent(
                    name = "when_skip_redaction_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("curl -H 'Authorization: $secretToken'")),
                            ),
                        ),
                    env = emptyMap(),
                )
            val workspaceWithSecret =
                TestFixtures.mockWorkspace(
                    config =
                        TestFixtures.mockWorkspaceConfig(
                            env = mapOf("SECRET_VAR" to secretToken),
                            secretEnvNames = setOf("SECRET_VAR"),
                        ),
                )

            // Predicate evaluates false (exit 1) → Skip
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 1, stdOut = arg(1), stdErr = arg(2))
            }

            val executor = executor(agent = agent, workspace = workspaceWithSecret)

            executor.execute()

            assertEquals(1, executionInfos.size)
            val skippedInfo = executionInfos[0]
            assertTrue(skippedInfo.skipped)
            assertTrue(
                skippedInfo.errMsg!!.contains("when.all"),
                "expected skip reason to mention 'when.all', got: ${skippedInfo.errMsg}",
            )
            assertTrue(
                !skippedInfo.errMsg.contains(secretToken),
                "the credential in the `when` expression must be redacted from the skip reason, got: ${skippedInfo.errMsg}",
            )
        }

    @Test
    fun `execute throws CommandExecutionException when 'when' predicate eval errors`() =
        runTest {
            val agent =
                Agent(
                    name = "eval_error_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("syntax-error-expr")),
                            ),
                            Command.Cmd(cmd = listOf("echo", "second"), timeout = 60),
                        ),
                    env = emptyMap(),
                )

            // Predicate eval error (exit 2)
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 2, stdOut = arg(1), stdErr = arg(2))
            }

            val executor = executor(agent = agent)

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertEquals(2, exception.exitCode)
            assertTrue(
                exception.message!!.contains("when-predicate evaluation failed"),
                "expected eval-error message, got: ${exception.message}",
            )
            // Neither command's executor ran
            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }
            verify { span.setStatus(StatusCode.ERROR, any()) }
            verify { span.recordException(exception) }
        }

    @Test
    fun `execute records an ExecutionInfo with the timeout when a 'when' predicate times out`() =
        runTest {
            val agent =
                Agent(
                    name = "when_timeout_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("sleep 6")),
                            ),
                            Command.Cmd(cmd = listOf("echo", "second"), timeout = 60),
                        ),
                    env = emptyMap(),
                )

            // The shell executors signal their own timeout with a CancellationException.
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("Timeout reached. 5s.")

            val executor = executor(agent = agent)

            val exception =
                assertThrows<CommandExecutionException> {
                    executor.execute()
                }

            assertTrue(
                exception.message!!.contains("when-predicate evaluation failed"),
                "expected eval-error message, got: ${exception.message}",
            )
            assertTrue(
                exception.message!!.contains("timed out"),
                "expected the timeout summary, got: ${exception.message}",
            )
            // The timed-out step is no longer invisible: it has its own ExecutionInfo.
            assertEquals(1, executionInfos.size)
            assertTrue(
                executionInfos[0].errMsg!!.contains("when-predicate evaluation failed"),
                "expected the timeout message on the step's ExecutionInfo, got: ${executionInfos[0].errMsg}",
            )
            assertTrue(
                executionInfos[0].errMsg!!.contains("timed out"),
                "expected the timeout summary on the step's ExecutionInfo, got: ${executionInfos[0].errMsg}",
            )
            verify { span.setStatus(StatusCode.ERROR, any()) }
            coVerify { mockProcessManager.completeStep(testProcessId, any(), null, null) }
            // The next command never runs.
            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }
        }

    @Test
    fun `execute redacts the timed-out when predicate before it reaches errMsg`() =
        runTest {
            // CWE-532: a credential in a `when` expression (`curl -H "Authorization: @SYSENV:..."`) reaches
            // errMsg through the exception message, which the status API serves raw; redact before persisting.
            val secretToken = "sup3r-secret-token-value"
            val agent =
                Agent(
                    name = "when_timeout_redaction_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("curl -H 'Authorization: $secretToken' && sleep 6")),
                            ),
                        ),
                    env = emptyMap(),
                )
            val workspaceWithSecret =
                TestFixtures.mockWorkspace(
                    config =
                        TestFixtures.mockWorkspaceConfig(
                            env = mapOf("SECRET_VAR" to secretToken),
                            secretEnvNames = setOf("SECRET_VAR"),
                        ),
                )

            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } throws
                CancellationException("Timeout reached. 5s.")

            val executor = executor(agent = agent, workspace = workspaceWithSecret)

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            assertEquals(1, executionInfos.size)
            val errMsg = executionInfos[0].errMsg ?: ""
            assertTrue(errMsg.contains("timed out"), "expected the timeout summary, got: $errMsg")
            assertTrue(
                !errMsg.contains(secretToken),
                "the credential in the `when` expression must be redacted from errMsg, got: $errMsg",
            )
        }

    @Test
    fun `execute propagates cancellation when the run is killed during a 'when' predicate`() =
        runTest {
            val agent =
                Agent(
                    name = "when_kill_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("sleep 6")),
                            ),
                        ),
                    env = emptyMap(),
                )

            val started = CompletableDeferred<Unit>()
            // A runner-enforced timeout and a kill raise the same exception class; only the
            // caller's coroutine state tells them apart.
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = arg(1), stdErr = arg(2))
            }

            val executor = executor(agent = agent)

            var caught: Throwable? = null
            val job =
                launch {
                    try {
                        executor.execute()
                    } catch (e: Throwable) {
                        caught = e
                    }
                }
            started.await()
            job.cancelAndJoin()

            assertTrue(
                caught is CancellationException,
                "expected the kill's CancellationException to propagate, got: $caught",
            )
            assertTrue(
                caught !is CommandExecutionException,
                "a kill must not be turned into an eval error, got: $caught",
            )
            // No step-level eval-error bookkeeping for the killed predicate.
            coVerify(exactly = 0) { mockProcessManager.completeStep(any(), any(), any(), any()) }
        }

    @Test
    fun `execute passes workspace env to 'when' predicate`() =
        runTest {
            val workspaceWithEnv =
                TestFixtures.mockWorkspace(
                    config = TestFixtures.mockWorkspaceConfig(env = mapOf("FOO" to "bar")),
                )
            val agent =
                Agent(
                    name = "env_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("[ \"\$FOO\" = \"bar\" ]")),
                            ),
                        ),
                    env = emptyMap(),
                )

            val capturedEnv = slot<Map<String, String>>()
            coEvery {
                mockShell.cmd(any(), any(), any(), any(), env = capture(capturedEnv), timeout = any())
            } answers {
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = arg(1), stdErr = arg(2))
            }

            val executor = executor(agent = agent, workspace = workspaceWithEnv)

            executor.execute()

            // Predicate evaluated true → command ran
            coVerify(exactly = 1) { mockExecutor.execute(any(), any(), any()) }
            assertEquals("bar", capturedEnv.captured["FOO"])
        }

    @Test
    fun `execute records eval-error ExecutionInfo with predicate exit code and error message`() =
        runTest {
            val agent =
                Agent(
                    name = "eval_error_info_test",
                    commands =
                        listOf(
                            Command.Cmd(
                                cmd = listOf("echo", "first"),
                                timeout = 60,
                                whenClause = When(When.Operator.ALL, listOf("syntax-error-expr")),
                            ),
                        ),
                    env = emptyMap(),
                )

            // Predicate eval error (exit 2)
            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 2, stdOut = arg(1), stdErr = arg(2))
            }

            val executor = executor(agent = agent)

            assertThrows<CommandExecutionException> {
                executor.execute()
            }

            // The eval-error path flows through the standard try/catch/finally, so an
            // ExecutionInfo is recorded with the predicate's exit code and error message.
            assertEquals(1, executionInfos.size)
            assertEquals(2, executionInfos[0].resultCode)
            assertEquals(false, executionInfos[0].outputTruncated)
            assertEquals(false, executionInfos[0].skipped)
            assertTrue(
                executionInfos[0].errMsg!!.contains("when-predicate evaluation failed"),
                "expected eval-error errMsg, got: ${executionInfos[0].errMsg}",
            )
        }
}
