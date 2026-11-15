package homer.app.executors

import homer.app.EnvConstants
import homer.app.exceptions.CommandExecutionException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.ShellExecutor
import homer.app.infra.Workspace
import homer.app.models.Agent
import homer.app.models.ExtraUploadSpec
import homer.app.models.PodPhase
import homer.app.models.RunnerState
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.DownloadMode
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** [AgentExecutor] tests for the runner session lifecycle, active-session bookkeeping and runner state. */
class AgentExecutorSessionTest : AgentExecutorTestBase() {
    @TempDir
    lateinit var tmp: Path

    // ========== Orchestration Tests ==========

    private fun runWith(
        session: RecordingSession,
        workspace: Workspace,
        agent: Agent = AgentFixtures.simpleAgent(),
    ) = runTest {
        coEvery { mockRunner.openSession(any()) } returns session

        executor(agent = agent, workspace = workspace).execute()
    }

    @Test
    fun `execute orchestrates full lifecycle when workspace is cleared`() {
        val session = RecordingSession(mockShell, overrides = mapOf("HMR_WORK_DIR" to "/remote/w"))
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(
                    env = mapOf(EnvConstants.AGENT_DIR to "/tmp/test/agent-dir"),
                    clearAfterFinish = true,
                ),
            )
        runWith(session, workspace)
        assertEquals(
            listOf("createWorkspace", "upload:.", "upload:.homer/agent", "download:artifacts:OVERLAY", "cleanWorkspace", "close"),
            session.calls,
        )
        verify { workspace.appendToEnv(mapOf("HMR_WORK_DIR" to "/remote/w")) }
    }

    @Test
    fun `extraUploads are uploaded after the workspace and agent dir`() {
        val session =
            RecordingSession(
                mockShell,
                overrides = mapOf("HMR_WORK_DIR" to "/remote/w"),
                extraUploads = listOf(ExtraUploadSpec("/app/scripts", ".homer/scripts", env = "SCRIPTS_DIR")),
            )
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(
                    env = mapOf(EnvConstants.AGENT_DIR to "/tmp/test/agent-dir"),
                    clearAfterFinish = true,
                ),
            )
        runWith(session, workspace)
        assertEquals(
            listOf(
                "createWorkspace",
                "upload:.",
                "upload:.homer/agent",
                "upload:.homer/scripts",
                "download:artifacts:OVERLAY",
                "cleanWorkspace",
                "close",
            ),
            session.calls,
        )
    }

    @Test
    fun `operator-configured upload sources are resolved so a symlinked agent dir still uploads`() {
        val realAgentDir = tmp.resolve("real-agent").createDirectories()
        val agentDirLink = Files.createSymbolicLink(tmp.resolve("agent-link"), realAgentDir)
        val realScripts = tmp.resolve("real-scripts").createDirectories()
        val scriptsLink = Files.createSymbolicLink(tmp.resolve("scripts-link"), realScripts)
        val session =
            RecordingSession(
                mockShell,
                extraUploads = listOf(ExtraUploadSpec(scriptsLink.toString(), ".homer/scripts", env = "SCRIPTS_DIR")),
            )
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(env = mapOf(EnvConstants.AGENT_DIR to agentDirLink.toString())),
            )

        runWith(session, workspace)

        assertEquals(
            listOf(realAgentDir.toRealPath(), realScripts.toRealPath()),
            session.uploadLocals.drop(1),
        )
    }

    @Test
    fun `a parent agent's upload excludes its nested agent directories`() {
        val agentDir = tmp.resolve("parent-agent").createDirectories()
        Files.write(agentDir.resolve("agent.yaml"), "name: parent\ncommands: []\n".toByteArray())
        val nested = agentDir.resolve("nodes/analysis").createDirectories()
        Files.write(nested.resolve("agent.yaml"), "name: analysis\ncommands: []\n".toByteArray())
        val sibling = agentDir.resolve("scripts").createDirectories()
        Files.write(sibling.resolve("run.sh"), "echo hi\n".toByteArray())
        val session = RecordingSession(mockShell)
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(env = mapOf(EnvConstants.AGENT_DIR to agentDir.toString())),
            )

        runWith(session, workspace)

        assertEquals(
            listOf("/nodes/analysis"),
            session.uploadExcludes[".homer/agent"],
            "a parent's HMR_AGENT_DIR upload must carry none of its nested agents",
        )
        // The prefix anchors the exclude to the upload root: a bare name would match
        // a same-named directory at any depth.
        assertTrue(session.uploadExcludes[".homer/agent"]!!.all { it.startsWith("/") })
    }

    @Test
    fun `a childless agent uploads with no excludes`() {
        val agentDir = tmp.resolve("solo-agent").createDirectories()
        Files.write(agentDir.resolve("agent.yaml"), "name: solo\ncommands: []\n".toByteArray())
        Files.write(agentDir.resolve("README.md"), "x".toByteArray())
        val session = RecordingSession(mockShell)
        val workspace =
            TestFixtures.mockWorkspace(
                TestFixtures.mockWorkspaceConfig(env = mapOf(EnvConstants.AGENT_DIR to agentDir.toString())),
            )

        runWith(session, workspace)

        assertEquals(emptyList<String>(), session.uploadExcludes[".homer/agent"])
    }

    @Test
    fun `execute downloads workdir and skips clean when workspace is kept`() {
        val session = RecordingSession(mockShell)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = false))
        runWith(session, workspace)
        assertEquals(
            listOf("createWorkspace", "upload:.", "download:artifacts:OVERLAY", "download:workdir:MIRROR", "close"),
            session.calls,
        )
    }

    @Test
    fun `execute still downloads artifacts and closes when a command fails`() {
        val session = RecordingSession(mockShell)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)
        coEvery { mockRunner.openSession(any()) } returns session

        assertThrows<CommandExecutionException> {
            runTest {
                executor(workspace = workspace).execute()
            }
        }
        assertEquals(
            listOf("createWorkspace", "upload:.", "markFailed", "download:artifacts:OVERLAY", "cleanWorkspace", "close"),
            session.calls,
        )
    }

    @Test
    fun `execute marks the session failed before close when a command fails`() {
        val session = RecordingSession(mockShell)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)
        coEvery { mockRunner.openSession(any()) } returns session

        assertThrows<CommandExecutionException> {
            runTest {
                executor(workspace = workspace).execute()
            }
        }
        assertTrue("markFailed" in session.calls)
        assertTrue(session.calls.indexOf("markFailed") < session.calls.indexOf("close"))
    }

    @Test
    fun `execute marks the session failed when a command throws a plain CancellationException without our job being cancelled`() {
        val session = RecordingSession(mockShell)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        // Simulates a runner-enforced timeout (e.g. KubernetesShellExecutor's TTL): a plain
        // CancellationException surfaces from a command without the executor's own coroutine
        // job ever being cancelled.
        coEvery { mockExecutor.execute(any(), any(), any()) } throws CancellationException("ttl exceeded")
        coEvery { mockRunner.openSession(any()) } returns session

        assertThrows<CancellationException> {
            runTest {
                executor(workspace = workspace).execute()
            }
        }
        assertTrue("markFailed" in session.calls)
    }

    @Test
    fun `execute does not mark the session failed when the run's own job is cancelled`() =
        runTest {
            val session = RecordingSession(mockShell)
            val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
            val started = CompletableDeferred<Unit>()
            coEvery { mockExecutor.execute(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
                TestFixtures.mockCommandResult(exitCode = 0)
            }
            coEvery { mockRunner.openSession(any()) } returns session

            val executor = executor(workspace = workspace)
            val job = launch { runCatching { executor.execute() } }
            started.await()
            job.cancelAndJoin()

            assertTrue("markFailed" !in session.calls)
        }

    @Test
    fun `execute closes session when createWorkspace fails`() {
        val session = RecordingSession(mockShell, failCreateWorkspace = true)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        coEvery { mockRunner.openSession(any()) } returns session

        assertThrows<IllegalStateException> {
            runTest {
                executor(workspace = workspace).execute()
            }
        }
        assertTrue("upload:." !in session.calls)
        assertTrue("close" in session.calls)
    }

    @Test
    fun `execute succeeds even when sync-down and cleanup fail`() {
        val session = RecordingSession(mockShell, failDownload = true, failCleanWorkspace = true)
        val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig(clearAfterFinish = true))
        runWith(session, workspace) // must not throw — sync-down/cleanup are best-effort
        assertEquals(
            listOf("createWorkspace", "upload:.", "download:artifacts:OVERLAY", "cleanWorkspace", "close"),
            session.calls,
        )
    }

    // ========== Active-session bookkeeping (ADR-0033) ==========

    @Test
    fun `a run holds its session in the registry while a command runs and releases it after success`() =
        runTest {
            val session = RecordingSession(mockShell)
            val activeSessions = ActiveSessionsRegistry()
            val sizesWhileRunning = mutableListOf<Int>()
            coEvery { mockRunner.openSession(any()) } returns session
            coEvery { mockExecutor.execute(any(), any(), any()) } coAnswers {
                sizesWhileRunning += activeSessions.size()
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            executor(activeSessions = activeSessions).execute()

            assertEquals(listOf(1), sizesWhileRunning, "the session must be registered before its command runs")
            assertEquals(0, activeSessions.size(), "the session must be released when the run ends")
        }

    @Test
    fun `a failed run releases its session from the registry`() =
        runTest {
            val session = RecordingSession(mockShell)
            val activeSessions = ActiveSessionsRegistry()
            coEvery { mockRunner.openSession(any()) } returns session
            coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 1)

            assertThrows<CommandExecutionException> { executor(activeSessions = activeSessions).execute() }

            assertEquals(0, activeSessions.size())
        }

    @Test
    fun `a killed run releases its session from the registry`() =
        runTest {
            val session = RecordingSession(mockShell)
            val activeSessions = ActiveSessionsRegistry()
            val started = CompletableDeferred<Unit>()
            coEvery { mockRunner.openSession(any()) } returns session
            coEvery { mockExecutor.execute(any(), any(), any()) } coAnswers {
                started.complete(Unit)
                delay(Long.MAX_VALUE)
                TestFixtures.mockCommandResult(exitCode = 0)
            }

            val job = launch { runCatching { executor(activeSessions = activeSessions).execute() } }
            // Bounded waits, and the job is cancelled even when an assertion fails: a leaked
            // child would keep `runTest` waiting out its whole dispatch timeout first.
            try {
                withTimeout(10.seconds) { started.await() }
                assertEquals(1, activeSessions.size())
            } finally {
                job.cancelAndJoin()
            }

            assertEquals(0, activeSessions.size())
        }

    @Test
    fun `a session env is appended to the workspace env before the first command runs`() =
        runTest {
            val sessionEnv = mapOf("SESSION_VAR" to "s1")
            val session = RecordingSession(mockShell, env = sessionEnv)
            val appended = mutableListOf<Map<String, String>>()
            every { mockWorkspace.appendToEnv(capture(appended)) } just Runs
            coEvery { mockRunner.openSession(any()) } returns session

            executor().execute()

            // The session's own env comes first, so the command sees it; then the step's
            // AGENT_NAME, then the command's result env (empty without a resultEnvParam).
            assertEquals(
                listOf(sessionEnv, mapOf(EnvConstants.AGENT_NAME to "test_agent"), emptyMap()),
                appended,
            )
        }

    @Test
    fun `a session with an empty env appends no session env of its own`() =
        runTest {
            val session = RecordingSession(mockShell)
            val appended = mutableListOf<Map<String, String>>()
            every { mockWorkspace.appendToEnv(capture(appended)) } just Runs
            coEvery { mockRunner.openSession(any()) } returns session

            executor().execute()

            // No session-env append at all: the first call is the step's own AGENT_NAME one.
            assertEquals(
                listOf(mapOf(EnvConstants.AGENT_NAME to "test_agent"), emptyMap()),
                appended,
            )
        }

    // ========== Runner State Tests ==========

    @Test
    fun `execute records the serving backend alias as runner state`() {
        val session = RecordingSession(mockShell, runnerAlias = "local")
        val updates = mutableListOf<(RunnerState?) -> RunnerState>()
        coEvery { mockProcessManager.setRunnerState(any(), capture(updates)) } just Runs

        runWith(session, TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig()))

        assertEquals(
            RunnerState(alias = "local"),
            updates.single().invoke(null),
        )
    }

    @Test
    fun `execute records the serving runner's alias, not the queued tag filter alias`() {
        // A `runnerTags` run is queued under the filter alias "tags:..."; the
        // serving backend is only known once the session is open.
        every { mockRunner.alias } returns "tags:gpu,linux"
        val session = RecordingSession(mockShell, runnerAlias = "ssh-prod")
        val updates = mutableListOf<(RunnerState?) -> RunnerState>()
        coEvery { mockProcessManager.setRunnerState(any(), capture(updates)) } just Runs

        runWith(session, TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig()))

        assertEquals(
            RunnerState(alias = "ssh-prod"),
            updates.single().invoke(null),
        )
    }

    @Test
    fun `the runner-state alias write preserves the Kubernetes pod fields`() {
        val session = RecordingSession(mockShell, runnerAlias = "k8s-dev")
        val updates = mutableListOf<(RunnerState?) -> RunnerState>()
        coEvery { mockProcessManager.setRunnerState(any(), capture(updates)) } just Runs

        runWith(session, TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig()))

        // The k8s runner emits this during openSession, before the alias write.
        val podState =
            RunnerState(
                alias = "stale-alias",
                podName = "homer-123-abc",
                podPhase = PodPhase.READY,
                createdAt = 1_700_000_000,
                readyAt = 1_700_000_005,
            )
        assertEquals(
            podState.copy(alias = "k8s-dev"),
            updates.single().invoke(podState),
        )
    }

    @Test
    fun `the alias merge keeps the pod fields when a pod write lands during the merge`() {
        // The Kubernetes runner's pod transitions and this write both go through
        // the repository's single BEGIN IMMEDIATE transaction, so they serialize.
        // The merge's read sees the last committed pod state, and the merged value
        // must carry its pod fields — a replace-form lambda
        // (`{ prev -> RunnerState(alias = …) }`) drops them and fails here.
        val session = RecordingSession(mockShell, runnerAlias = "k8s-dev")
        var persisted: RunnerState? = null
        // The pod state as the merge's read sees it (the k8s runner wrote it during
        // openSession).
        val podState =
            RunnerState(
                alias = "stale-alias",
                podName = "homer-123-abc",
                podPhase = PodPhase.RUNNING,
                createdAt = 1_700_000_000,
            )
        coEvery {
            mockProcessManager.setRunnerState(
                any(),
                captureLambda<
                    (
                        RunnerState?,
                    ) -> RunnerState,
                >(),
            )
        } coAnswers
            {
                val merge = lambda<(RunnerState?) -> RunnerState>().captured
                // The merge reads the last pod state and the merged value is what
                // the alias write persists.
                persisted = merge(podState)
            }

        runWith(session, TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig()))

        assertEquals(
            podState.copy(alias = "k8s-dev"),
            persisted,
        )
    }

    @Test
    fun `execute records no runner state when the session never opens`() =
        runTest {
            val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig())
            coEvery { mockRunner.openSession(any()) } throws IllegalStateException("claim timed out")

            assertThrows<IllegalStateException> {
                executor(workspace = workspace).execute()
            }

            coVerify(exactly = 0) { mockProcessManager.setRunnerState(any(), any()) }
        }

    @Test
    fun `execute records no runner state when workspace creation fails after the session opens`() =
        runTest {
            val session = RecordingSession(mockShell, failCreateWorkspace = true)
            val workspace = TestFixtures.mockWorkspace(TestFixtures.mockWorkspaceConfig())
            coEvery { mockRunner.openSession(any()) } returns session

            assertThrows<IllegalStateException> {
                executor(workspace = workspace).execute()
            }

            // The alias write sits after createWorkspace(), so a run whose session
            // opened but whose workspace was never created reports runner: null.
            coVerify(exactly = 0) { mockProcessManager.setRunnerState(any(), any()) }
        }
}

private class RecordingSession(
    override val shell: ShellExecutor,
    private val overrides: Map<String, String> = emptyMap(),
    private val failCreateWorkspace: Boolean = false,
    private val failDownload: Boolean = false,
    private val failCleanWorkspace: Boolean = false,
    override val extraUploads: List<ExtraUploadSpec> = emptyList(),
    override val runnerAlias: String = "recording",
    override val env: Map<String, String> = emptyMap(),
) : RunnerSession {
    val calls = mutableListOf<String>()
    val uploadLocals = mutableListOf<java.nio.file.Path>()
    val uploadExcludes = mutableMapOf<String, List<String>>()

    override suspend fun createWorkspace(): Map<String, String> {
        calls += "createWorkspace"
        if (failCreateWorkspace) error("mkdir failed")
        return overrides
    }

    override suspend fun upload(
        local: java.nio.file.Path,
        remoteRelative: String,
        excludes: List<String>,
    ) {
        calls += "upload:$remoteRelative"
        uploadLocals.add(local)
        uploadExcludes[remoteRelative] = excludes
    }

    override suspend fun download(
        remoteRelative: String,
        local: java.nio.file.Path,
        mode: DownloadMode,
    ) {
        calls += "download:$remoteRelative:$mode"
        if (failDownload) throw RuntimeException("network gone")
    }

    override suspend fun cleanWorkspace() {
        calls += "cleanWorkspace"
        if (failCleanWorkspace) throw RuntimeException("sweep failed")
    }

    override fun close() {
        calls += "close"
    }

    override fun markFailed() {
        calls.add("markFailed")
    }
}
