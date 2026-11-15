package homer.app.executors

import homer.app.exceptions.CommandExecutionException
import homer.app.exceptions.MalformedLlmOutputException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ExecutionInfo
import homer.app.models.When
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.verify
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.io.path.relativeTo
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [AgentExecutor] tests for each command's stdout/stderr artifact paths and the artifactNameTemplate. */
class AgentExecutorArtifactsTest : AgentExecutorTestBase() {
    @Test
    fun `command start registers its attempt-0 artifacts with the command's kind`() =
        runTest {
            executor().execute()

            coVerify {
                mockProcessManager.setCommandArtifacts(
                    testProcessId,
                    0,
                    match { it.kind == "cmd" && it.attemptIndex == 0 && it.stdOut.fileName.toString() == "cmd_0.out" },
                )
            }
        }

    @Test
    fun `execute should create ExecutionContext for each command with correct stdout stderr paths`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands
            val artifactsPath = mockWorkspace.config.artifacts

            val executor = executor(agent = agent)

            executor.execute()

            // Verify ExecutionContext is created with correct paths for each command
            coVerifyOrder {
                mockExecutor.execute(
                    any(),
                    match {
                        it.workspace == mockWorkspace &&
                            it.stdOut == artifactsPath.resolve("cmd_0.out") &&
                            it.stdErr == artifactsPath.resolve("cmd_0.err")
                    },
                    any(),
                )
                mockExecutor.execute(
                    any(),
                    match {
                        it.workspace == mockWorkspace &&
                            it.stdOut == artifactsPath.resolve("cmd_1.out") &&
                            it.stdErr == artifactsPath.resolve("cmd_1.err")
                    },
                    any(),
                )
                mockExecutor.execute(
                    any(),
                    match {
                        it.workspace == mockWorkspace &&
                            it.stdOut == artifactsPath.resolve("cmd_2.out") &&
                            it.stdErr == artifactsPath.resolve("cmd_2.err")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `execute should pass stdout and stderr paths with correct pattern to ExecutionContext`() =
        runTest {
            val artifactsPath = mockWorkspace.config.artifacts

            val executor = executor()

            executor.execute()

            // Verify ExecutionContext receives correct stdout/stderr paths
            coVerify {
                mockExecutor.execute(
                    any(),
                    match {
                        it.stdOut == artifactsPath.resolve("cmd_0.out") &&
                            it.stdErr == artifactsPath.resolve("cmd_0.err")
                    },
                    any(),
                )
            }
        }

    @Test
    fun `execute should create sequential stdout stderr paths for multiple commands`() =
        runTest {
            val agent = AgentFixtures.multiCommandAgent() // 3 commands
            val artifactsPath = mockWorkspace.config.artifacts

            val executor = executor(agent = agent)

            executor.execute()

            // Verify sequential path patterns for all commands
            coVerifyOrder {
                mockExecutor.execute(
                    any(),
                    match {
                        it.stdOut.fileName.toString() == "cmd_0.out" &&
                            it.stdErr.fileName.toString() == "cmd_0.err"
                    },
                    any(),
                )
                mockExecutor.execute(
                    any(),
                    match {
                        it.stdOut.fileName.toString() == "cmd_1.out" &&
                            it.stdErr.fileName.toString() == "cmd_1.err"
                    },
                    any(),
                )
                mockExecutor.execute(
                    any(),
                    match {
                        it.stdOut.fileName.toString() == "cmd_2.out" &&
                            it.stdErr.fileName.toString() == "cmd_2.err"
                    },
                    any(),
                )
            }
        }

    @Test
    fun `execute records the executor-returned result's actual paths in ExecutionInfo`() =
        runTest {
            // Simulates a Claude retry whose winning attempt wrote to a different file
            // than the attempt-0 path AgentExecutor built the ExecutionContext with
            // (per-attempt artifactNameTemplate) — ExecutionInfo must reflect where the
            // output actually landed, not the attempt-0 path.
            val artifactsPath = mockWorkspace.config.artifacts
            val winningStdOut = artifactsPath.resolve("cmd_0_a1.out")
            val winningStdErr = artifactsPath.resolve("cmd_0_a1.err")

            coEvery { mockExecutor.execute(any(), any(), any()) } returns
                TestFixtures.mockCommandResult(exitCode = 0, stdOut = winningStdOut, stdErr = winningStdErr)

            val executor = executor()

            executor.execute()

            assertEquals(1, executionInfos.size)
            assertEquals(winningStdOut.relativeTo(artifactsPath), executionInfos[0].stdOut)
            assertEquals(winningStdErr.relativeTo(artifactsPath), executionInfos[0].stdErr)
        }

    @Test
    fun `execute records the attempt-0 paths in ExecutionInfo when no CommandResult exists`() =
        runTest {
            // An EvalError throws before executor.execute ever runs — no CommandResult
            // exists, so ExecutionInfo must fall back to the attempt-0 paths.
            val agent =
                Agent(
                    name = "eval_error_paths_test",
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
            val artifactsPath = mockWorkspace.config.artifacts

            coEvery { mockShell.cmd(any(), any(), any(), any(), any(), any()) } answers {
                TestFixtures.mockCommandResult(exitCode = 2, stdOut = arg(1), stdErr = arg(2))
            }

            val executor = executor(agent = agent)

            assertThrows<CommandExecutionException> { executor.execute() }

            assertEquals(1, executionInfos.size)
            assertEquals(artifactsPath.resolve("cmd_0.out").relativeTo(artifactsPath), executionInfos[0].stdOut)
            assertEquals(artifactsPath.resolve("cmd_0.err").relativeTo(artifactsPath), executionInfos[0].stdErr)
        }

    @Test
    fun `execute records the tracked per-attempt paths in ExecutionInfo when the executor throws without returning a result`() =
        runTest {
            // Simulates a MalformedLlmOutputException on the final retry attempt: the
            // command executor (e.g. LlmHarnessExecutor) throws instead of
            // returning a CommandResult, so executedResult stays null — but its retry
            // loop already updated the tracked per-attempt artifacts via
            // processManager.setCommandArtifacts before throwing. ExecutionInfo must
            // record that last-attempt path, not the attempt-0 path AgentExecutor built
            // the ExecutionContext with.
            val artifactsPath = mockWorkspace.config.artifacts
            val lastAttemptOut = artifactsPath.resolve("cmd_0_a2.out")
            val lastAttemptErr = artifactsPath.resolve("cmd_0_a2.err")

            coEvery { mockExecutor.execute(any(), any(), any()) } throws MalformedLlmOutputException("still garbage")
            coEvery { mockProcessManager.getCommandArtifacts(testProcessId, 0) } returns
                CommandArtifactPaths(lastAttemptOut, lastAttemptErr)

            val executor = executor()

            assertThrows<MalformedLlmOutputException> { executor.execute() }

            assertEquals(1, executionInfos.size)
            assertEquals(lastAttemptOut.relativeTo(artifactsPath), executionInfos[0].stdOut)
            assertEquals(lastAttemptErr.relativeTo(artifactsPath), executionInfos[0].stdErr)
        }

    @Test
    fun `execute records ExecutionInfo and ends the span when artifactNameTemplate resolution fails`() =
        runTest {
            // §12 denial path: a rendered-stem validation failure must flow through the
            // same per-command bookkeeping as any other command failure — an
            // ExecutionInfo row, the span ended, currentCommand cleared — not bypass it
            // via the outer session-lifecycle catch/finally.
            val agent = AgentFixtures.simpleAgent().copy(artifactNameTemplate = "{{MISSING}}")

            val executor = executor(agent = agent)

            val exception = assertThrows<CommandExecutionException> { executor.execute() }
            assertTrue(exception.message!!.contains("artifactNameTemplate"))

            // The command executor itself must never be reached.
            coVerify(exactly = 0) { mockExecutorFactory.getExecutor<Command>(any()) }
            coVerify(exactly = 0) { mockExecutor.execute(any(), any(), any()) }

            assertEquals(1, executionInfos.size)
            assertEquals(-1, executionInfos[0].resultCode)
            assertTrue(executionInfos[0].errMsg!!.contains("artifactNameTemplate"))
            assertEquals(false, executionInfos[0].skipped)
            verify { span.end() }
            verify { span.setStatus(StatusCode.ERROR, any()) }
            coVerify { mockProcessManager.completeStep(testProcessId, any(), null, null) }
        }

    @Test
    fun `execute rejects an artifactNameTemplate referencing a secret-classified env var without leaking its value`() =
        runTest {
            // The rendered stem becomes a server-local file name, so a
            // template referencing a secret-classified var must fail closed (§12) — and
            // neither the exception nor the recorded ExecutionInfo may carry the value.
            val agent = AgentFixtures.simpleAgent().copy(artifactNameTemplate = "{{SECRET_VAR}}")
            val workspaceWithSecret =
                TestFixtures.mockWorkspace(
                    config =
                        TestFixtures.mockWorkspaceConfig(
                            env = mapOf("SECRET_VAR" to "s3cr3t-value"),
                            secretEnvNames = setOf("SECRET_VAR"),
                        ),
                )

            val executor = executor(agent = agent, workspace = workspaceWithSecret)

            val exception = assertThrows<CommandExecutionException> { executor.execute() }
            assertTrue(exception.message!!.contains("artifactNameTemplate"))
            assertTrue(!exception.message!!.contains("s3cr3t-value"))

            coVerify(exactly = 0) { mockExecutorFactory.getExecutor<Command>(any()) }

            assertEquals(1, executionInfos.size)
            assertTrue(!executionInfos[0].errMsg!!.contains("s3cr3t-value"))
        }

    @Test
    fun `execute rejects an artifactNameTemplate whose stem embeds a derived secret value reaching a non-classified var`() =
        runTest {
            // Layer 1 (name exclusion) does not fire here: STEM_VAR is not itself
            // secret-classified, so its value renders through unfiltered. Only layer 2
            // — the SecretRedactor built from workspace.config.secretEnvNames and wired
            // into ArtifactNameResolver at the AgentExecutor call site — can catch this.
            // If that wiring is ever dropped, this is the test that fails, not the
            // sibling above (whose secret var is both in env AND secretEnvNames, so
            // layer 1 alone would still pass it).
            val secretValue = "s3cr3t-value-long-enough"
            val agent = AgentFixtures.simpleAgent().copy(artifactNameTemplate = "{{STEM_VAR}}")
            val workspaceWithDerivedSecret =
                TestFixtures.mockWorkspace(
                    config =
                        TestFixtures.mockWorkspaceConfig(
                            env = mapOf("REAL_SECRET" to secretValue, "STEM_VAR" to secretValue),
                            secretEnvNames = setOf("REAL_SECRET"),
                        ),
                )

            val executor = executor(agent = agent, workspace = workspaceWithDerivedSecret)

            val exception = assertThrows<CommandExecutionException> { executor.execute() }
            assertTrue(exception.message!!.contains("artifactNameTemplate"))
            assertTrue(!exception.message!!.contains(secretValue))

            coVerify(exactly = 0) { mockExecutorFactory.getExecutor<Command>(any()) }

            assertEquals(1, executionInfos.size)
            assertTrue(!executionInfos[0].errMsg!!.contains(secretValue))
        }
}
