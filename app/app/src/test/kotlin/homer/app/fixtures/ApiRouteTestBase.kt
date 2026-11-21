package homer.app.fixtures

import homer.app.apiJson
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.AgentInputResolver
import homer.app.infra.TelemetryService
import homer.app.models.ErrorResponse
import homer.app.routes.ApiDeps
import homer.app.routes.apiV1Routes
import homer.app.services.AgentsService
import homer.app.services.ProcessManager
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Shared setup for route tests that mount the real [apiV1Routes] over relaxed service mocks: [mockProcessManager]
 * (fixed start time and last process id), [mockAgentsService] (no agents), [mockTelemetryService], and the
 * helpers wiring them into [ApiDeps], which are `internal` because [ApiDeps] is.
 */
abstract class ApiRouteTestBase {
    protected lateinit var mockProcessManager: ProcessManager
    protected lateinit var mockAgentsService: AgentsService
    protected lateinit var mockTelemetryService: TelemetryService
    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    @BeforeEach
    fun setupServiceMocks() {
        mockProcessManager = mockk(relaxed = true)
        mockAgentsService = mockk(relaxed = true)
        mockTelemetryService = TestFixtures.mockTelemetryService()

        every { mockProcessManager.startTime } returns "1700000000"
        coEvery { mockProcessManager.lastProcessId() } returns 5
        every { mockAgentsService.agents } returns emptyMap()
    }

    @AfterEach
    fun teardownServiceMocks() {
        unmockkAll()
    }

    /** A real ProcessManager for the round-trip tests (no mocking of its reads). */
    protected fun realProcessManager(): ProcessManager = PersistenceTestSupport.processManager(logger = logger)

    /**
     * Mounts the real [apiV1Routes] wired to [mockProcessManager] and [mockAgentsService];
     * [stubDeps] wires any further dependency.
     */
    internal fun ApplicationTestBuilder.mountWithServiceMocks(stubDeps: ApiDeps.() -> Unit = {}) =
        mountApiV1Routes {
            every { processManager } returns mockProcessManager
            every { agentsService } returns mockAgentsService
            stubDeps()
        }

    /**
     * Wires the run-starting dependencies a retry or debug handler reaches: the given
     * factory and executor, [mockTelemetryService] and a real [AgentInputResolver].
     */
    internal fun ApiDeps.stubRunDeps(
        factory: ProcessFactory,
        executor: ProcessExecutor,
    ) {
        every { processFactory } returns factory
        every { processExecutor } returns executor
        every { telemetryService } returns mockTelemetryService
        every { agentInputResolver } returns AgentInputResolver()
    }

    /** The `msg` of an [ErrorResponse] body. */
    protected suspend fun HttpResponse.errorMsg(): String = apiJson.decodeFromString<ErrorResponse>(bodyAsText()).msg
}
