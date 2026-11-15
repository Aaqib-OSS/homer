package homer.app.routes

import homer.app.apiJson
import homer.app.executors.ParallelRunsManager
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.ApiRouteTestBase
import homer.app.fixtures.mountApiV1Routes
import homer.app.models.Agent
import homer.app.models.AgentReloadResult
import homer.app.models.AgentsListResponse
import homer.app.models.Command
import homer.app.models.CronConfig
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ParamType
import homer.app.models.ProcessCreationResult
import homer.app.services.Authorizer
import homer.app.services.CronSchedulerService
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `AgentRoutes` endpoints through the real [apiV1Routes]: `GET /schemas/agent`, `GET /agents`,
 * `POST /agents/reload`, `POST /agents/{name}/debug`, `POST /agents/{name}/cron-run` and
 * `GET /agents/{name}/langgraph`. The agent-file endpoints are covered by `AgentFilesRoutesTest`.
 */
class AgentRoutesTest : ApiRouteTestBase() {
    // ========== GET /schemas/agent Tests ==========

    @Test
    fun `GET schemas agent serves bundled homer-agent schema`() =
        testApplication {
            mountApiV1Routes()

            val response = client.get("/api/v1/schemas/agent")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"title\""), "Schema body should contain a title field")
            assertTrue(body.contains("Homer Agent"), "Schema body should declare itself as the Homer Agent schema")
        }

    // ========== GET /agents Tests ==========

    @Test
    fun `GET agents should return empty list when no agents`() =
        testApplication {
            mountApiV1Routes { every { agentsService.agents } returns emptyMap() }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(apiJson.decodeFromString<AgentsListResponse>(response.bodyAsText()).agents.isEmpty())
        }

    @Test
    fun `GET agents exposes new-style inputs (regression - inputs-migrated agents showed no params)`() =
        testApplication {
            // Exercises the REAL /agents route (not a duplicated handler) so the DTO
            // mapping can't silently drop the new `inputs:` field again.
            val agent =
                AgentFixtures.simpleAgent(name = "with-inputs").copy(
                    inputs =
                        listOf(
                            Param(
                                paramName = "ticket_id",
                                envName = "TICKET_ID",
                                type = ParamType.STRING,
                                required = true,
                            ),
                        ),
                )
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("with-inputs" to agent)
            }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"inputs\""), "response must include the inputs field: $body")
            assertTrue(body.contains("ticket_id"), "declared input must be exposed: $body")
        }

    @Test
    fun `GET agents should include description when agent has one`() =
        testApplication {
            val agent =
                Agent(
                    name = "test_agent",
                    description = "My test description",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "test"), timeout = 60)),
                )
            mountApiV1Routes { every { agentsService.agents } returns mapOf("test_agent" to agent) }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            val listed = apiJson.decodeFromString<AgentsListResponse>(response.bodyAsText()).agents.single()
            assertEquals("My test description", listed.description)
        }

    @Test
    fun `GET agents serves each agent's group and an explicit null for an ungrouped one`() =
        testApplication {
            val grouped = AgentFixtures.simpleAgent(name = "grouped").copy(group = "Platform")
            val ungrouped = AgentFixtures.simpleAgent(name = "ungrouped")
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("grouped" to grouped, "ungrouped" to ungrouped)
            }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            val listed = apiJson.decodeFromString<AgentsListResponse>(body).agents.associateBy { it.name }
            assertEquals("Platform", listed.getValue("grouped").group)
            assertNull(listed.getValue("ungrouped").group)
            // Explicit null, not an omitted key: the console mirrors `string | null`.
            assertTrue(body.contains("\"group\":null") || body.contains("\"group\": null"), body)
        }

    private fun secretSummaryParams() =
        listOf(
            Param(
                paramName = "token",
                envName = "TOKEN",
                type = ParamType.STR,
                required = false,
                secret = true,
                values = listOf("abc", "def"),
                default = "super-secret-default",
            ),
            Param(
                paramName = "environment",
                envName = "ENV_NAME",
                type = ParamType.STR,
                required = false,
                values = listOf("dev", "prod"),
                default = "dev",
            ),
        )

    private suspend fun assertSecretFieldsDropped(response: HttpResponse) {
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("secret-defaults"), "agent must be listed: $body")
        assertTrue(body.contains("environment"), "the declared param must be listed: $body")
        assertFalse(body.contains("super-secret-default"), "secret default must not leave the API: $body")
        assertFalse(body.contains("abc"), "secret input's values must not leave the API: $body")
        assertTrue(body.contains("dev"), "non-secret default must still be served: $body")
    }

    @Test
    fun `GET agents drops a secret input's default and values from the served summary`() =
        testApplication {
            // Real route via AgentInfo.from; any grant-holder reads it. See ADR-0071.
            val agent = AgentFixtures.simpleAgent(name = "secret-defaults").copy(inputs = secretSummaryParams())
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("secret-defaults" to agent)
            }

            assertSecretFieldsDropped(client.get("/api/v1/agents"))
        }

    @Test
    fun `GET agents drops a secret input's default and values on the legacy queryParams alias`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "secret-defaults").copy(queryParams = secretSummaryParams())
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("secret-defaults" to agent)
            }

            assertSecretFieldsDropped(client.get("/api/v1/agents"))
        }

    @Test
    fun `GET agents reports no cron and reload carries the disable error for an unsatisfiable cron agent`() =
        testApplication {
            // Black-box check of the load-time rule: an agent whose required input a
            // cron fire can never satisfy loads with its schedule cleared, so the
            // agents list must serve `cron: null` for it, and the reload result must
            // carry the disable reason (CLAUDE.md §6). `agents` holds the agent as
            // load produced it — schedule already stripped, per AgentsServiceTest.
            val agent =
                AgentFixtures.simpleAgent(name = "cron-unsatisfiable").copy(
                    inputs =
                        listOf(
                            Param(
                                paramName = "id",
                                envName = "ID",
                                type = ParamType.STRING,
                                required = true,
                            ),
                        ),
                )
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("cron-unsatisfiable" to agent)
                every { cronSchedulerService.getScheduleStatus(any()) } returns null
                every { agentsService.reload() } returns
                    AgentReloadResult(
                        added = emptyList(),
                        updated = emptyList(),
                        removed = emptyList(),
                        errors =
                            listOf(
                                "Agent 'cron-unsatisfiable' in agent: " +
                                    "cron schedule disabled — required input 'id' has no default; scheduled runs pass no inputs",
                            ),
                    )
            }

            val listBody = client.get("/api/v1/agents").bodyAsText()
            val listed = Json.decodeFromString<AgentsListResponse>(listBody).agents.single()
            assertNull(listed.cron, "a disabled schedule must be served as `cron: null`: $listBody")

            val reloadResponse = client.post("/api/v1/agents/reload")
            assertEquals(HttpStatusCode.OK, reloadResponse.status)
            val reloadBody = reloadResponse.bodyAsText()
            assertTrue(
                reloadBody.contains("cron-unsatisfiable") && reloadBody.contains("cron schedule disabled"),
                "reload response must carry the disable reason: $reloadBody",
            )
        }

    // ========== POST /agents/reload Tests ==========

    @Test
    fun `POST agents reload returns diff result and kills CREATED processes for updated and removed agents`() =
        testApplication {
            val reloadResult =
                AgentReloadResult(
                    added = listOf("new_agent"),
                    updated = listOf("changed_agent"),
                    removed = listOf("old_agent"),
                    errors = emptyList(),
                )
            every { mockAgentsService.reload() } returns reloadResult
            coEvery { mockProcessManager.createdRunsOf(any()) } returns
                listOf(10 to "changed_agent", 11 to "old_agent")
            val parallelRunsManager = mockk<ParallelRunsManager>(relaxed = true)
            mountWithServiceMocks { every { this@mountWithServiceMocks.parallelRunsManager } returns parallelRunsManager }

            val response = client.post("/api/v1/agents/reload")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(reloadResult, apiJson.decodeFromString<AgentReloadResult>(response.bodyAsText()))
            // Only CREATED runs of updated/removed agents are looked up — never the added agent's.
            coVerify(exactly = 1) { mockProcessManager.createdRunsOf(setOf("changed_agent", "old_agent")) }
            coVerify(exactly = 1) { mockProcessManager.killProcess(10, "Agent 'changed_agent' was reloaded") }
            coVerify(exactly = 1) { mockProcessManager.killProcess(11, "Agent 'old_agent' was reloaded") }
            coVerify(exactly = 2) { mockProcessManager.killProcess(any(), any()) }
            // The reload diff also releases the per-agent slots the changed definitions held.
            verify(exactly = 1) { parallelRunsManager.onAgentsReloaded(setOf("changed_agent"), setOf("old_agent")) }
        }

    @Test
    fun `POST agents reload does not kill processes when only agents are added`() =
        testApplication {
            val reloadResult =
                AgentReloadResult(
                    added = listOf("new_agent"),
                    updated = emptyList(),
                    removed = emptyList(),
                    errors = emptyList(),
                )
            every { mockAgentsService.reload() } returns reloadResult
            val parallelRunsManager = mockk<ParallelRunsManager>(relaxed = true)
            mountWithServiceMocks { every { this@mountWithServiceMocks.parallelRunsManager } returns parallelRunsManager }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/agents/reload").status)

            coVerify(exactly = 0) { mockProcessManager.createdRunsOf(any()) }
            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
            // An added-only reload still releases slots — with both diff sets empty.
            verify(exactly = 1) { parallelRunsManager.onAgentsReloaded(emptySet(), emptySet()) }
        }

    // ========== POST /agents/{name}/debug Tests ==========

    @Test
    fun `POST agents debug accepts body and headers payload and resolves them into env`() =
        testApplication {
            val agent =
                Agent(
                    name = "debug-body",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                    inputs =
                        listOf(
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
            every { mockAgentsService.agents } returns mapOf("debug-body" to agent)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery {
                mockProcessExecutor.execute(any(), any())
            } returns ProcessCreationResult(processId = 7)
            mountWithServiceMocks { stubRunDeps(mockProcessFactory, mockProcessExecutor) }

            val response =
                client.post("/api/v1/agents/debug-body/debug") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "commandIndex": 0,
                          "env": {"EXTRA":"v"},
                          "body": {"user":"alice"},
                          "headers": {"X-Trace-Id":"abc"}
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 1) {
                mockProcessFactory.create(
                    match<Agent> { it.name == "debug-body" && it.env["EXTRA"] == "v" },
                    mapOf("USER" to "alice", "TRACE" to "abc"),
                    any(),
                    commandIndices = listOf(0),
                )
            }
        }

    @Test
    fun `debug endpoint rejects reserved env overrides`() =
        testApplication {
            val agent =
                Agent(
                    name = "debug-reserved",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                )
            every { mockAgentsService.agents } returns mapOf("debug-reserved" to agent)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery {
                mockProcessExecutor.execute(any(), any())
            } returns ProcessCreationResult(processId = 7)
            mountWithServiceMocks { stubRunDeps(mockProcessFactory, mockProcessExecutor) }

            val response =
                client.post("/api/v1/agents/debug-reserved/debug") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"commandIndex":0,"env":{"PATH":"/tmp/evil"},"queryParams":{},"headers":{}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)

            // BASH_ENV is sourced by non-interactive `bash -c` (WhenEvaluator)
            // before the expression runs, so it must be rejected like PATH.
            val bashEnvResponse =
                client.post("/api/v1/agents/debug-reserved/debug") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"commandIndex":0,"env":{"BASH_ENV":"/tmp/evil.sh"},"queryParams":{},"headers":{}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, bashEnvResponse.status)
            verify(exactly = 0) {
                mockProcessFactory.create(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `debug endpoint rejects a commandIndex outside the agent's commands`() =
        testApplication {
            val agent =
                Agent(
                    name = "debug-range",
                    commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
                )
            every { mockAgentsService.agents } returns mapOf("debug-range" to agent)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            mountWithServiceMocks { stubRunDeps(mockProcessFactory, mockProcessExecutor) }

            // Both ends of `0 until commands.size`: the last valid index is commands.size - 1,
            // so one below zero and the size itself are out of range and start nothing.
            for (index in listOf(-1, agent.commands.size)) {
                val response =
                    client.post("/api/v1/agents/debug-range/debug") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"commandIndex":$index,"env":{},"queryParams":{},"headers":{}}""")
                    }

                assertEquals(HttpStatusCode.BadRequest, response.status, "commandIndex $index")
                assertTrue(response.errorMsg().contains("commandIndex out of range"), response.errorMsg())
            }
            verify(exactly = 0) {
                mockProcessFactory.create(any(), any(), any(), any(), any(), any())
            }
        }

    // ========== POST /agents/{name}/cron-run Tests ==========

    @Test
    fun `POST agents cron-run fires the agent once and returns 204`() =
        testApplication {
            val agent =
                AgentFixtures.simpleAgent(name = "cron-run-agent").copy(
                    cron = CronConfig(expression = "* * * * *", timezone = "UTC"),
                )
            val scheduler = mockk<CronSchedulerService>(relaxed = true)
            coEvery { scheduler.fireNow(agent) } returns Unit
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("cron-run-agent" to agent)
                every { cronSchedulerService } returns scheduler
            }

            val response = client.post("/api/v1/agents/cron-run-agent/cron-run")

            assertEquals(HttpStatusCode.NoContent, response.status)
            coVerify(exactly = 1) { scheduler.fireNow(agent) }
        }

    @Test
    fun `POST agents cron-run denies a non-admin with the same 404 as an unknown agent`() =
        testApplication {
            val agent =
                AgentFixtures.simpleAgent(name = "cron-run-agent").copy(
                    cron = CronConfig(expression = "* * * * *", timezone = "UTC"),
                )
            val scheduler = mockk<CronSchedulerService>(relaxed = true)
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("cron-run-agent" to agent)
                every { cronSchedulerService } returns scheduler
                // Enabled auth with no grants: a non-admin principal.
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
            }

            val response = client.post("/api/v1/agents/cron-run-agent/cron-run")

            assertEquals(HttpStatusCode.NotFound, response.status)
            coVerify(exactly = 0) { scheduler.fireNow(any()) }
        }

    @Test
    fun `POST agents cron-run denial body matches the unknown-agent 404`() =
        testApplication {
            val agent =
                AgentFixtures.simpleAgent(name = "cron-run-agent").copy(
                    cron = CronConfig(expression = "* * * * *", timezone = "UTC"),
                )
            val scheduler = mockk<CronSchedulerService>(relaxed = true)
            mountApiV1Routes {
                every { agentsService.agents } returns mapOf("cron-run-agent" to agent)
                every { cronSchedulerService } returns scheduler
                // Enabled auth with no grants: a non-admin principal.
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
            }

            val denied = client.post("/api/v1/agents/cron-run-agent/cron-run")
            val unknown = client.post("/api/v1/agents/no-such-agent/cron-run")

            // Existence hiding (ADR-0047 rule 3) is the body, not the status alone:
            // the denial must be the unknown-agent 404 apart from the name the
            // caller itself supplied — no "not permitted" wording or extra field
            // a grant-probing caller could read existence from.
            assertEquals(HttpStatusCode.NotFound, denied.status)
            assertEquals(unknown.bodyAsText().replace("no-such-agent", "cron-run-agent"), denied.bodyAsText())
            coVerify(exactly = 0) { scheduler.fireNow(any()) }
        }

    @Test
    fun `POST agents cron-run returns 404 for an unknown agent`() =
        testApplication {
            val scheduler = mockk<CronSchedulerService>(relaxed = true)
            mountApiV1Routes {
                every { agentsService.agents } returns emptyMap()
                every { cronSchedulerService } returns scheduler
            }

            val response = client.post("/api/v1/agents/no-such-agent/cron-run")

            assertEquals(HttpStatusCode.NotFound, response.status)
            coVerify(exactly = 0) { scheduler.fireNow(any()) }
        }

    // ========== GET /agents/{agentName}/langgraph Tests ==========

    @Test
    fun `GET langgraph returns 404 for unknown agent`() =
        testApplication {
            mountApiV1Routes()

            val response = client.get("/api/v1/agents/does-not-exist/langgraph")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
}
