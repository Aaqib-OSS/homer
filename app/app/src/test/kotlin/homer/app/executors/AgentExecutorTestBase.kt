package homer.app.executors

import homer.app.config.ExecutionConfig
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.ShellExecutor
import homer.app.infra.TelemetryService
import homer.app.infra.Workspace
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ExecutionInfo
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.Runner
import homer.app.runners.RunnerSession
import homer.app.services.ProcessManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.unmockkAll
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * The mocks, default stubs and [executor] builder the `AgentExecutor*Test` classes share. By default
 * every command span is [span], every command runs on [mockExecutor] and exits 0, and every step
 * record written through `completeStep` lands in [executionInfos]; a test overrides what it varies.
 */
abstract class AgentExecutorTestBase {
    protected lateinit var mockWorkspace: Workspace
    protected lateinit var mockExecutorFactory: CommandExecutorFactory
    protected lateinit var mockTelemetryService: TelemetryService
    protected lateinit var mockExecutor: CommandExecutor<Command>
    protected lateinit var mockProcessManager: ProcessManager
    protected lateinit var mockRunner: Runner
    protected lateinit var mockSession: RunnerSession
    protected lateinit var mockShell: ShellExecutor
    protected lateinit var config: ExecutionConfig
    protected lateinit var span: Span
    protected val executionInfos = mutableListOf<ExecutionInfo>()
    protected val testProcessId = 123

    @BeforeEach
    fun setup() {
        mockWorkspace = TestFixtures.mockWorkspace()
        mockExecutorFactory = mockk(relaxed = true)
        span = TestFixtures.mockSpan()
        mockTelemetryService = TestFixtures.mockTelemetryService()
        // A chained stub, not mockTelemetryService(span): it is what lets a test verify the chain
        // (`verify { mockTelemetryService.tracer.spanBuilder(any()) }`).
        every {
            mockTelemetryService.tracer
                .spanBuilder(any())
                .setParent(any())
                .startSpan()
        } returns span
        mockExecutor = mockk(relaxed = true)
        every { mockExecutorFactory.getExecutor<Command>(any()) } returns mockExecutor
        coEvery { mockExecutor.execute(any(), any(), any()) } returns TestFixtures.mockCommandResult(exitCode = 0)
        mockProcessManager = mockk(relaxed = true)
        // Relaxed mocks fabricate a non-null child mock for unstubbed calls returning a
        // nullable type, which breaks Path.relativeTo() in the finally block's fallback
        // (F1). Tests that care about a tracked path override this per-call.
        coEvery { mockProcessManager.getCommandArtifacts(any(), any()) } returns null
        coEvery { mockProcessManager.completeStep(any(), capture(executionInfos), any(), any()) } just Runs
        mockShell = mockk(relaxed = true)
        mockSession =
            mockk(relaxed = true) {
                every { shell } returns mockShell
                // Same trap as getCommandArtifacts above: a relaxed mock fabricates a
                // non-null RunDeadline whose soft bound is 0, which would end every run
                // at its first step. A session with no deadline is the default.
                every { runDeadline } returns null
            }
        mockRunner =
            mockk(relaxed = true) {
                coEvery { openSession(any()) } returns mockSession
            }
        config = ExecutionConfig()
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    /** An [AgentExecutor] over this class's mocks; a test passes only what it varies. */
    protected fun executor(
        agent: Agent = AgentFixtures.simpleAgent(),
        workspace: Workspace = mockWorkspace,
        executorFactory: CommandExecutorFactory = mockExecutorFactory,
        telemetryService: TelemetryService = mockTelemetryService,
        config: ExecutionConfig = this.config,
        parentContext: Context = Context.current(),
        stepRecords: StepRecords = mockProcessManager,
        processId: Int = testProcessId,
        runner: Runner = mockRunner,
        activeSessions: ActiveSessionsRegistry = ActiveSessionsRegistry(),
        commandIndices: List<Int>? = null,
        owner: String? = null,
        rootProcessId: Int? = null,
    ) = AgentExecutor(
        agent = agent,
        workspace = workspace,
        executorFactory = executorFactory,
        telemetryService = telemetryService,
        config = config,
        parentContext = parentContext,
        stepRecords = stepRecords,
        processId = processId,
        runner = runner,
        activeSessions = activeSessions,
        commandIndices = commandIndices,
        owner = owner,
        rootProcessId = rootProcessId,
    )
}
