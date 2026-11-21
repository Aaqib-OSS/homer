package homer.app.routes

import homer.app.apiJson
import homer.app.exceptions.QueryParamValidationException
import homer.app.executors.Process
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.ApiRouteTestBase
import homer.app.fixtures.mountApiV1Routes
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ErrorResponse
import homer.app.models.ExecuteResponse
import homer.app.models.LangGraphRunIdentity
import homer.app.models.MaintenanceConfig
import homer.app.models.Param
import homer.app.models.ParamType
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.AgentRunStarter
import homer.app.services.Authorizer
import homer.app.services.LangGraphRunStatusService
import homer.app.services.MaintenanceModeService
import homer.app.services.ProcessManager
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `RunRoutes` endpoints: `POST /agent/{agentName}` and `POST /processes/{id}/retry` through the
 * real [apiV1Routes], including the shutdown drain's `503` (ADR-0102), and [langGraphThreadToResume],
 * the LangGraph preconditions of `POST /api/v1/processes/{id}/resume`.
 */
class RunRoutesTest : ApiRouteTestBase() {
    private val source = ProcessInfo(id = 7, status = ProcessStatus.FAILED, agentName = "graph")
    private val langGraphAgent =
        AgentFixtures.simpleAgent(name = "graph").copy(commands = listOf(Command.LangGraph(module = "my_graphs.flow")))
    private val service = mockk<LangGraphRunStatusService>()

    @Test
    fun `the thread of the run's LangGraph identity is resumed`() {
        every { service.identity(source) } returns LangGraphRunIdentity(threadId = "thread-1", module = "my_graphs.flow")

        assertEquals("thread-1", langGraphThreadToResume(service, 7, source, langGraphAgent))
    }

    @Test
    fun `an agent without a LangGraph command is a 400`() {
        val e =
            assertFailsWith<QueryParamValidationException> {
                langGraphThreadToResume(service, 7, source, AgentFixtures.simpleAgent(name = "graph"))
            }
        assertEquals("Agent 'graph' no longer defines a LangGraph command", e.message)
    }

    @Test
    fun `a run without a LangGraph thread is a 400`() {
        every { service.identity(source) } returns null

        val e = assertFailsWith<QueryParamValidationException> { langGraphThreadToResume(service, 7, source, langGraphAgent) }
        assertEquals("Process 7 has no LangGraph thread to resume", e.message)
    }

    // ========== the shutdown drain (ADR-0102) ==========

    /**
     * A real [AgentRunStarter] over [processExecutor] whose gate is a real [MaintenanceModeService]
     * reading the production drain flag, exactly as `ServicesWiring` wires it — so the test exercises
     * the route → starter → gate → `StatusPages` chain, not a stubbed exception.
     */
    private fun drainingRunStarter(processExecutor: ProcessExecutor): AgentRunStarter =
        AgentRunStarter(
            serverId = "test-server",
            processExecutor = processExecutor,
            processFactory = mockk(relaxed = true),
            telemetryService = mockTelemetryService,
            costMeter = mockk(relaxed = true),
            maxInvocationDepth = 5,
            cooldownGate = mockk(relaxed = true),
            maintenanceGate =
                MaintenanceModeService(
                    repository = mockk(relaxed = true),
                    config = MaintenanceConfig(),
                    initialEnabled = false,
                    logger = logger,
                    isDraining = homer.app.isDraining::get,
                ),
            processReads = mockk(relaxed = true),
        )

    /** A real gate reading the production drain flag, exactly as `ServicesWiring` wires it (see [drainingRunStarter]). */
    private fun drainingGate(): MaintenanceModeService =
        MaintenanceModeService(
            repository = mockk(relaxed = true),
            config = MaintenanceConfig(),
            initialEnabled = false,
            logger = logger,
            isDraining = homer.app.isDraining::get,
        )

    @Test
    fun `POST agent answers 503 while the drain is running`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "drain_agent")
            val executor = mockk<ProcessExecutor>(relaxed = true)
            homer.app.isDraining.set(true)
            try {
                mountApiV1Routes {
                    every { agentsService.agents } returns mapOf("drain_agent" to agent)
                    every { agentRunStarter } returns drainingRunStarter(executor)
                    every { isDraining } returns homer.app.isDraining
                }

                val response = client.post("/api/v1/agent/drain_agent")

                // The process is going away, so a start it cannot serve is shed rather than
                // queued: a parked start could only end UNKNOWN at the next boot.
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                coVerify(exactly = 0) { executor.execute(any(), any()) }
            } finally {
                // The flag is JVM-global; leaking it would refuse every later test's starts.
                homer.app.isDraining.set(false)
            }
        }

    @Test
    fun `POST agent still answers 404 to a caller without a run grant while the drain is running`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "drain_agent")
            val executor = mockk<ProcessExecutor>(relaxed = true)
            homer.app.isDraining.set(true)
            try {
                mountApiV1Routes {
                    every { agentsService.agents } returns mapOf("drain_agent" to agent)
                    every { agentRunStarter } returns drainingRunStarter(executor)
                    every { isDraining } returns homer.app.isDraining
                    // No grants at all: the handler's own gate runs before the start, so the
                    // refusal is the denial, not the drain — and no row is probed either way.
                    every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
                }

                val response = client.post("/api/v1/agent/drain_agent")

                assertEquals(HttpStatusCode.NotFound, response.status)
                coVerify(exactly = 0) { executor.execute(any(), any()) }
            } finally {
                homer.app.isDraining.set(false)
            }
        }

    @Test
    fun `POST retry answers 503 while the drain is running`() =
        testApplication {
            // Retry takes the gate in `respondStartedUnderRootSpan`, not in `AgentRunStarter`, so the drain refusal
            // needs its own HTTP proof here: the README row promises the 503 (ADR-0102).
            val finished =
                ProcessInfo(id = 42, status = ProcessStatus.FINISHED, agentName = "deploy", inputParams = emptyMap())
            val executor = mockk<ProcessExecutor>(relaxed = true)
            homer.app.isDraining.set(true)
            try {
                mountApiV1Routes {
                    every { processManager } returns mockProcessManager
                    every { agentsService } returns mockAgentsService
                    coEvery { mockProcessManager.getProcessInfo(42) } returns finished
                    every { mockAgentsService.agents } returns mapOf("deploy" to AgentFixtures.simpleAgent(name = "deploy"))
                    every { maintenanceModeService } returns drainingGate()
                    stubRunDeps(mockk(relaxed = true), executor)
                }

                val response = client.post("/api/v1/processes/42/retry")

                assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                coVerify(exactly = 0) { executor.execute(any(), any()) }
            } finally {
                // The flag is JVM-global; leaking it would refuse every later test's starts.
                homer.app.isDraining.set(false)
            }
        }

    // ========== GET /agent/{agentName} Tests ==========

    @Test
    fun `GET agent by name returns 404 when agent not found`() =
        testApplication {
            every { mockAgentsService.agents } returns emptyMap()
            mountWithServiceMocks()

            val response = client.get("/api/v1/agent/nonexistent")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Agent not found: nonexistent", apiJson.decodeFromString<ErrorResponse>(response.bodyAsText()).msg)
        }

    // ========== POST /processes/{id}/retry Tests ==========

    @Test
    fun `POST retry should return 400 for non-integer id`() =
        testApplication {
            mountWithServiceMocks()

            val response = client.post("/api/v1/processes/not-a-number/retry")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid process id", response.errorMsg())
        }

    @Test
    fun `POST retry should return 404 when process not found`() =
        testApplication {
            coEvery { mockProcessManager.getProcessInfo(999) } returns null
            mountWithServiceMocks()

            val response = client.post("/api/v1/processes/999/retry")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Process 999 not found", response.errorMsg())
        }

    @Test
    fun `POST retry should return 409 when process still active`() =
        testApplication {
            val active =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.WORKING,
                    agentName = "deploy",
                    inputParams = mapOf("X" to "y"),
                )
            coEvery { mockProcessManager.getProcessInfo(42) } returns active
            mountWithServiceMocks()

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("Cannot retry a non-terminal process (status: WORKING)", response.errorMsg())
        }

    @Test
    fun `POST retry should return 404 when agent no longer exists`() =
        testApplication {
            val finished =
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "ghost",
                    inputParams = emptyMap(),
                )
            coEvery { mockProcessManager.getProcessInfo(7) } returns finished
            every { mockAgentsService.agents } returns emptyMap()
            mountWithServiceMocks()

            val response = client.post("/api/v1/processes/7/retry")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Agent 'ghost' no longer exists", response.errorMsg())
        }

    @Test
    fun `POST retry should replay inputParams and return new processId on success`() =
        testApplication {
            val mockProcessFactory = mockk<ProcessFactory>()
            val mockProcessExecutor = mockk<ProcessExecutor>()
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "deploy",
                    inputParams = mapOf("X" to "y"),
                )
            // The agent still declares the stored input, so retry re-validation replays
            // it unchanged (a value the current policy still accepts).
            val fakeAgent =
                Agent(
                    name = "deploy",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs = listOf(Param(paramName = "x", envName = "X", type = ParamType.STR)),
                )
            val fakeProcess = mockk<Process>(relaxed = true)
            val creationResult = ProcessCreationResult(processId = 99)

            coEvery { mockProcessManager.getProcessInfo(42) } returns sourceProcess
            every { mockAgentsService.agents } returns mapOf("deploy" to fakeAgent)
            every {
                mockProcessFactory.create(any<Agent>(), any<Map<String, String>>(), any(), any())
            } returns fakeProcess
            coEvery { mockProcessExecutor.execute(any<Process>(), any()) } returns creationResult
            mountWithServiceMocks { stubRunDeps(mockProcessFactory, mockProcessExecutor) }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(99, apiJson.decodeFromString<ExecuteResponse>(response.bodyAsText()).processId)
            verify {
                mockProcessFactory.create(
                    fakeAgent,
                    mapOf("X" to "y"),
                    any(),
                    any(),
                )
            }
            coVerify { mockProcessExecutor.execute(fakeProcess, any()) }
        }
}
