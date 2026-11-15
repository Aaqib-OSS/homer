package homer.app.routes

import homer.app.apiJson
import homer.app.configureStatusPages
import homer.app.exceptions.CostCapExceededException
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.ApiRouteTestBase
import homer.app.infra.AgentInputResolver
import homer.app.infra.InputSchemaValidator
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ErrorResponse
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.AgentRunStarter
import homer.app.services.Authorizer
import homer.app.services.CostMeter
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The run-start handlers of `RunHandlers.kt` — [handleAgentExecution], [handleRetry] and [handleResume]
 * — mounted on a bare route over a real [AgentRunStarter]: input resolution and schema validation,
 * the invocation-chain and lock-key headers, the cost-cap preflight and the request body limit.
 */
class RunHandlersTest : ApiRouteTestBase() {
    // ========== handleAgentExecution Tests (with the retry / resume cost-cap preflight) ==========

    @Test
    fun `POST agent with missing required query param returns 400 via StatusPages`() =
        testApplication {
            val agent =
                Agent(
                    name = "test_agent",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)),
                    queryParams =
                        listOf(
                            Param(
                                paramName = "required_param",
                                envName = "REQUIRED_PARAM",
                                type = ParamType.STRING,
                                required = true,
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("test_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/agent/test_agent")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(body.msg.contains("required_param"), "msg should mention the missing param name")
        }

    @Test
    fun `POST agent always starts a fresh invocation chain ignoring any inbound chain header`() =
        testApplication {
            val agent =
                Agent(
                    name = "ext_agent",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                )
            every { mockAgentsService.agents } returns mapOf("ext_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    // Depth 2 — a five-deep spoofed chain would otherwise blow past it.
                                    maxInvocationDepth = 2,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            // The HTTP route no longer trusts any inbound chain header (RunAgent
            // dispatch is in-process; see ADR-0030). An external caller fabricating a
            // five-entry chain is ignored: the run starts at chain = [ext_agent].
            val response =
                client.post("/api/v1/agent/ext_agent") {
                    header("X-Homer-Invocation-Chain", "a,b,c,d,e")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    agent = agent,
                    queryParams = any(),
                    parentContext = any(),
                    invocationChain = listOf("ext_agent"),
                )
            }
        }

    @Test
    fun `POST agent ignores any inbound ancestor-lock-keys header`() =
        testApplication {
            val agent =
                Agent(
                    name = "ext_agent",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                )
            every { mockAgentsService.agents } returns mapOf("ext_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 2)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            // The route ignores any inbound ancestor-lock-keys header, so an external
            // caller cannot make an agent skip its lock (see ADR-0030).
            val response =
                client.post("/api/v1/agent/ext_agent") {
                    header("X-Homer-Ancestor-Lock-Keys", """["shared-key"]""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    agent = agent,
                    queryParams = any(),
                    parentContext = any(),
                    invocationChain = any(),
                    ancestorLockKeys = emptyList(),
                )
            }
        }

    private fun deployAgentWithSchema(): Agent =
        Agent(
            name = "deploy",
            commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
            inputs = listOf(Param(paramName = "service", envName = "SERVICE", src = ParamSource.BODY, type = ParamType.STRING)),
            inputSchema =
                Json.parseToJsonElement(
                    """
                    {"type":"object","required":["service"],
                     "properties":{"service":{"type":"string"}},
                     "additionalProperties":false}
                    """.trimIndent(),
                ) as JsonObject,
        )

    @Test
    fun `POST agent with conforming payload returns 200`() =
        testApplication {
            val agent = deployAgentWithSchema()
            every { mockAgentsService.agents } returns mapOf("deploy" to agent)
            val agentInputResolver = AgentInputResolver(InputSchemaValidator())
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val resp =
                client.post("/api/v1/agent/deploy") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"service":"api"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `POST agent with schema-violating payload returns 422`() =
        testApplication {
            val agent = deployAgentWithSchema()
            every { mockAgentsService.agents } returns mapOf("deploy" to agent)
            val agentInputResolver = AgentInputResolver(InputSchemaValidator())
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 8)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockk(relaxed = true),
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val resp =
                client.post("/api/v1/agent/deploy") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"unexpected":true}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        }

    @Test
    fun `POST agent with schema set but empty body returns 400`() =
        testApplication {
            val agent = deployAgentWithSchema()
            every { mockAgentsService.agents } returns mapOf("deploy" to agent)
            val agentInputResolver = AgentInputResolver(InputSchemaValidator())
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 9)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockk(relaxed = true),
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val resp =
                client.post("/api/v1/agent/deploy") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }

    @Test
    fun `POST agent with only an optional body input accepts a bodyless request`() =
        testApplication {
            val agent =
                Agent(
                    name = "optional_body",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "prompt",
                                envName = "PROMPT",
                                src = ParamSource.BODY,
                                type = ParamType.STR,
                                required = false,
                                default = "",
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("optional_body" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 11)

            application {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockk(relaxed = true),
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            // No body and no Content-Type — the console/Export Curl shape.
            val resp = client.post("/api/v1/agent/optional_body")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertContains(resp.bodyAsText(), "processId")
        }

    @Test
    fun `POST agent rejects with 429 when cost cap exceeded for an agent with a Claude command`() =
        testApplication {
            val agent = AgentFixtures.claudeAgent("billed_agent")
            every { mockAgentsService.agents } returns mapOf("billed_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockCostMeter = mockk<CostMeter>()
            coEvery { mockCostMeter.checkCaps("billed_agent") } throws
                CostCapExceededException("globalUsdPerMin", 5.0, 5.42)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockCostMeter,
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/agent/billed_agent")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(body.msg.contains("globalUsdPerMin"), "msg should name the breached limit")
            // Refused at the boundary — no process was launched.
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { mockCostMeter.checkCaps("billed_agent") }
        }

    @Test
    fun `POST agent rejects with 429 when cost cap exceeded for an agent with a Codex command`() =
        testApplication {
            // The preflight gates on any Command.LlmCommand, not only Claude — a
            // Codex-only agent must be refused the same way.
            val agent = AgentFixtures.codexAgent("billed_codex_agent")
            every { mockAgentsService.agents } returns mapOf("billed_codex_agent" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockCostMeter = mockk<CostMeter>()
            coEvery { mockCostMeter.checkCaps("billed_codex_agent") } throws
                CostCapExceededException("globalUsdPerMin", 5.0, 5.42)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockCostMeter,
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/agent/billed_codex_agent")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(body.msg.contains("globalUsdPerMin"), "msg should name the breached limit")
            // Refused at the boundary — no process was launched.
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { mockCostMeter.checkCaps("billed_codex_agent") }
        }

    @Test
    fun `retry rejects with 429 when cost cap exceeded for an agent with a Codex command`() =
        testApplication {
            val agent = AgentFixtures.codexAgent("billed_codex_agent")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "billed_codex_agent",
                )
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockCostMeter = mockk<CostMeter>()
            coEvery { mockCostMeter.checkCaps("billed_codex_agent") } throws
                CostCapExceededException("globalUsdPerMin", 5.0, 5.42)

            application {
                install(ContentNegotiation) { json(apiJson) }
                configureStatusPages()
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = mockTelemetryService,
                            costMeter = mockCostMeter,
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            // Refused at the boundary — no process was launched.
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { mockCostMeter.checkCaps("billed_codex_agent") }
        }

    @Test
    fun `resume rejects with 429 when cost cap exceeded for an agent with a Codex command`() =
        testApplication {
            val agent = AgentFixtures.codexAgent("billed_codex_agent")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "billed_codex_agent",
                )
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockCostMeter = mockk<CostMeter>()
            coEvery { mockCostMeter.checkCaps("billed_codex_agent") } throws
                CostCapExceededException("globalUsdPerMin", 5.0, 5.42)

            application {
                install(ContentNegotiation) { json(apiJson) }
                configureStatusPages()
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = mockTelemetryService,
                            costMeter = mockCostMeter,
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/resume")

            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            // Refused at the boundary — no process was launched.
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { mockCostMeter.checkCaps("billed_codex_agent") }
        }

    @Test
    fun `POST agent skips cost cap check for agents without Claude commands`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent("cmd_only")
            every { mockAgentsService.agents } returns mapOf("cmd_only" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 7)
            val mockCostMeter = mockk<CostMeter>(relaxed = true)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockCostMeter,
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/agent/cmd_only")

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 0) { mockCostMeter.checkCaps(any()) }
        }

    @Test
    fun `POST agent with mixed query body header inputs creates process with env from all sources`() =
        testApplication {
            val agent =
                Agent(
                    name = "mixed-inputs",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "id",
                                envName = "ID",
                                src = ParamSource.QUERY,
                                type = ParamType.STRING,
                            ),
                            Param(
                                paramName = "user",
                                envName = "USER",
                                src = ParamSource.BODY,
                                type = ParamType.STRING,
                            ),
                            Param(
                                paramName = "X-Trace-Id",
                                envName = "TRACE",
                                src = ParamSource.HEADER,
                                type = ParamType.STRING,
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("mixed-inputs" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery {
                mockProcessExecutor.execute(any(), any())
            } returns ProcessCreationResult(processId = 11)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response =
                client.post("/api/v1/agent/mixed-inputs?id=42") {
                    contentType(ContentType.Application.Json)
                    header("X-Trace-Id", "abc")
                    setBody("""{"user":"alice"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    agent = agent,
                    queryParams = mapOf("ID" to "42", "USER" to "alice", "TRACE" to "abc"),
                    parentContext = any(),
                    invocationChain = any(),
                )
            }
        }

    @Test
    fun `GET agent with body input returns 400 BadRequest`() =
        testApplication {
            val agent =
                Agent(
                    name = "needs-body",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs =
                        listOf(
                            Param(
                                paramName = "user",
                                envName = "USER",
                                src = ParamSource.BODY,
                                type = ParamType.STRING,
                            ),
                        ),
                )
            every { mockAgentsService.agents } returns mapOf("needs-body" to agent)
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    get("/api/v1/agent/{agentName}") {
                        handleAgentExecution(
                            call = call,
                            agent = agent,
                            agentInputResolver = agentInputResolver,
                            agentRunStarter =
                                AgentRunStarter(
                                    serverId = "test-server",
                                    processExecutor = mockProcessExecutor,
                                    processFactory = mockProcessFactory,
                                    telemetryService = mockTelemetryService,
                                    costMeter = mockk(relaxed = true),
                                    maxInvocationDepth = 5,
                                    cooldownGate = mockk(relaxed = true),
                                    maintenanceGate = mockk(relaxed = true),
                                    processReads = mockk(relaxed = true),
                                ),
                        )
                    }
                }
            }

            val response = client.get("/api/v1/agent/needs-body")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(
                body.msg.contains("requires request body"),
                "msg should explain that the agent requires a request body, got: ${body.msg}",
            )
            verify(exactly = 0) { mockProcessFactory.create(any(), any(), any(), any(), any(), any()) }
        }

    // ========== handleRetry Tests ==========

    @Test
    fun `POST retry returns 400 when the current agent now requires an input absent from the stored run`() =
        testApplication {
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FINISHED,
                    agentName = "deploy",
                    inputParams = emptyMap(),
                )
            // The stored run predates a reload that made "id" required — retry must
            // validate against the CURRENT agent definition, not the original one.
            val reloadedAgent =
                Agent(
                    name = "deploy",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs = listOf(Param(paramName = "id", envName = "ID", required = true)),
                )
            val agentInputResolver = AgentInputResolver()
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)

            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                configureStatusPages()
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = reloadedAgent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = mockTelemetryService,
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = agentInputResolver,
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            assertTrue(body.msg.contains("id"), "msg should mention the now-required param name")
            coVerify(exactly = 0) { mockProcessExecutor.execute(any(), any()) }
        }

    // ========== server.maxRequestBodyBytes Tests ==========

    /**
     * Mounts a minimal app with [RequestBodyLimit] installed at `maxRequestBodyBytes`
     * (mirrors the application-level install in `configureServer`) plus a `body-input-agent`
     * with a single BODY input, wired the same way as the other `handleAgentExecution` tests.
     */
    private fun ApplicationTestBuilder.mountBodyLimitedAgent(maxRequestBodyBytes: Long) {
        val agent =
            Agent(
                name = "body-input-agent",
                commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                inputs =
                    listOf(
                        Param(
                            paramName = "user",
                            envName = "USER",
                            src = ParamSource.BODY,
                            type = ParamType.STRING,
                        ),
                    ),
            )
        every { mockAgentsService.agents } returns mapOf("body-input-agent" to agent)
        val agentInputResolver = AgentInputResolver()
        val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
        val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
        coEvery {
            mockProcessExecutor.execute(any(), any())
        } returns ProcessCreationResult(processId = 31)

        application {
            install(RequestBodyLimit) {
                bodyLimit { maxRequestBodyBytes }
            }
            install(ContentNegotiation) {
                json(apiJson)
            }
            configureStatusPages()
            routing {
                post("/api/v1/agent/{agentName}") {
                    handleAgentExecution(
                        call = call,
                        agent = agent,
                        agentInputResolver = agentInputResolver,
                        agentRunStarter =
                            AgentRunStarter(
                                serverId = "test-server",
                                processExecutor = mockProcessExecutor,
                                processFactory = mockProcessFactory,
                                telemetryService = mockTelemetryService,
                                costMeter = mockk(relaxed = true),
                                maxInvocationDepth = 5,
                                cooldownGate = mockk(relaxed = true),
                                maintenanceGate = mockk(relaxed = true),
                                processReads = mockk(relaxed = true),
                            ),
                    )
                }
            }
        }
    }

    @Test
    fun `request body over maxRequestBodyBytes is rejected with 413`() =
        testApplication {
            mountBodyLimitedAgent(maxRequestBodyBytes = 1024)

            val bigBody = """{"user":"${"x".repeat(2048)}"}"""
            val response =
                client.post("/api/v1/agent/body-input-agent") {
                    contentType(ContentType.Application.Json)
                    setBody(bigBody)
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `request body under maxRequestBodyBytes passes the limit`() =
        testApplication {
            mountBodyLimitedAgent(maxRequestBodyBytes = 1024)

            val response =
                client.post("/api/v1/agent/body-input-agent") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"user":"alice"}""")
                }

            assertNotEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }
}
