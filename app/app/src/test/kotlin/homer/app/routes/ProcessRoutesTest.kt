package homer.app.routes

import homer.app.apiJson
import homer.app.executors.Process
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.ApiRouteTestBase
import homer.app.fixtures.DelegatingChannel
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.mountApiV1Routes
import homer.app.infra.DescriptorOpen
import homer.app.infra.LocalFileOperations
import homer.app.models.AgentDispatchConfig
import homer.app.models.ArtifactListingResponse
import homer.app.models.ErrorResponse
import homer.app.models.ExecutionInfo
import homer.app.models.NewRunRecord
import homer.app.models.Param
import homer.app.models.ParamType
import homer.app.models.ProcessInfo
import homer.app.models.ProcessInfoResponse
import homer.app.models.ProcessListResponse
import homer.app.models.ProcessStatus
import homer.app.models.ProcessStatusResponse
import homer.app.models.RunStartRequest
import homer.app.services.AgentRunStarter
import homer.app.services.AgentsService
import homer.app.services.ArtifactService
import homer.app.services.ProcessManager
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `ProcessRoutes` endpoints through the real [apiV1Routes]: `GET /status/{processId}` (with
 * secret redaction), `GET /status/all`, `DELETE /kill/{processId}`, `GET /artifacts` and
 * `GET /artifacts/list`.
 */
class ProcessRoutesTest : ApiRouteTestBase() {
    /**
     * Mounts the real [apiV1Routes] handler with [pm] as the only wired
     * dependency (everything else is relaxed).
     */
    private fun ApplicationTestBuilder.mountStatusAllRoutes(pm: ProcessManager) {
        mountApiV1Routes { every { processManager } returns pm }
    }

    // ========== GET /status/{processId} Tests ==========

    @Test
    fun `GET status processId should return full process info`() =
        testApplication {
            val mockProcessInfo =
                ProcessInfo(
                    id = 1,
                    status = ProcessStatus.WORKING,
                    agentName = "test-agent",
                    job = null,
                    history =
                        listOf(
                            1700000000L to ProcessStatus.CREATED,
                            1700000100L to ProcessStatus.WORKING,
                        ),
                    executions =
                        listOf(
                            ExecutionInfo(
                                label = "cmd",
                                start = 1700000050L,
                                end = 1700000090L,
                                resultCode = 0,
                                stdOut =
                                    java.nio.file.Paths
                                        .get("/tmp/out.txt"),
                                stdErr =
                                    java.nio.file.Paths
                                        .get("/tmp/err.txt"),
                                errMsg = null,
                            ),
                        ),
                )
            coEvery { mockProcessManager.getProcessInfo(1) } returns mockProcessInfo
            mountWithServiceMocks()

            val response = client.get("/api/v1/status/1")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"id\": 1"), "Response should contain process ID")
            assertTrue(body.contains("\"status\": \"WORKING\""), "Response should contain process status")
            assertTrue(body.contains("\"agentName\": \"test-agent\""), "Response should contain agent name")
            assertTrue(body.contains("\"history\""), "Response should contain history")
            assertTrue(body.contains("\"executions\""), "Response should contain executions")
            assertTrue(body.contains("CREATED"), "Response should contain CREATED in history")
            assertTrue(body.contains("1700000050"), "Response should contain execution start time")
            assertTrue(body.contains("\"label\": \"cmd\""), "Response should contain execution label")
        }

    @Test
    fun `GET status processId should return NotFound for non-existent process`() =
        testApplication {
            coEvery { mockProcessManager.getProcessInfo(999) } returns null
            mountWithServiceMocks()

            val response = client.get("/api/v1/status/999")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Process not found", apiJson.decodeFromString<ErrorResponse>(response.bodyAsText()).msg)
        }

    @Test
    fun `GET status processId should return BadRequest for invalid ID`() =
        testApplication {
            mountWithServiceMocks()

            val response = client.get("/api/v1/status/invalid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid process ID", apiJson.decodeFromString<ErrorResponse>(response.bodyAsText()).msg)
            coVerify(exactly = 0) { mockProcessManager.getProcessInfo(any()) }
        }

    @Test
    fun `status responses carry the agent group from the current definition, null once it is gone`() =
        testApplication {
            // A real ProcessManager: the group is joined in from the current agent
            // definition at response time, never stored on the process row.
            val pm = realProcessManager()
            val grouped = pm.obtainProcessId(NewRunRecord("grouped-agent"))
            val gone = pm.obtainProcessId(NewRunRecord("gone-agent"))
            val agents = mockk<AgentsService>(relaxed = true)
            every { agents.agents } returns
                mapOf("grouped-agent" to AgentFixtures.simpleAgent(name = "grouped-agent").copy(group = "Platform"))

            mountApiV1Routes {
                every { processManager } returns pm
                every { agentsService } returns agents
            }

            val single = apiJson.decodeFromString<ProcessInfoResponse>(client.get("/api/v1/status/$grouped").bodyAsText())
            assertEquals("Platform", single.group)

            val goneSingle = apiJson.decodeFromString<ProcessInfoResponse>(client.get("/api/v1/status/$gone").bodyAsText())
            assertNull(goneSingle.group)

            val listed = apiJson.decodeFromString<ProcessListResponse>(client.get("/api/v1/status/all").bodyAsText())
            assertEquals("Platform", listed.processes.single { it.id == grouped }.group)
            assertNull(listed.processes.single { it.id == gone }.group)
        }

    // ========== GET /status/{processId} secret redaction Tests (Task 9) ==========

    @Test
    fun `status response redacts secret input values`() =
        testApplication {
            val pm = realProcessManager()
            val id =
                pm.obtainProcessId(
                    NewRunRecord(
                        "secret-agent",
                        inputParams = mapOf("TOKEN" to "hunter2", "USER_N" to "alice"),
                    ),
                )
            val secretAgent =
                AgentFixtures.simpleAgent(name = "secret-agent").copy(
                    inputs =
                        listOf(
                            Param(paramName = "token", envName = "TOKEN", type = ParamType.ANY, secret = true),
                            Param(paramName = "user", envName = "USER_N", type = ParamType.ANY),
                        ),
                )

            mountApiV1Routes {
                every { processManager } returns pm
                every { agentsService.agents } returns mapOf("secret-agent" to secretAgent)
            }

            val response = client.get("/api/v1/status/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val info = apiJson.decodeFromString<ProcessInfoResponse>(response.bodyAsText())
            assertEquals("***", info.inputParams["TOKEN"])
            assertEquals("alice", info.inputParams["USER_N"])
        }

    @Test
    fun `status response redacts a stored value whose envName no longer matches any current input`() =
        testApplication {
            val pm = realProcessManager()
            val id =
                pm.obtainProcessId(
                    NewRunRecord(
                        "renamed-secret-agent",
                        inputParams = mapOf("TOKEN" to "hunter2", "USER_N" to "alice"),
                    ),
                )
            // The secret input was renamed from envName TOKEN to API_TOKEN since this run
            // stored its value; the old TOKEN key matches no current input.
            val renamedAgent =
                AgentFixtures.simpleAgent(name = "renamed-secret-agent").copy(
                    inputs =
                        listOf(
                            Param(paramName = "token", envName = "API_TOKEN", type = ParamType.ANY, secret = true),
                            Param(paramName = "user", envName = "USER_N", type = ParamType.ANY),
                        ),
                )

            mountApiV1Routes {
                every { processManager } returns pm
                every { agentsService.agents } returns mapOf("renamed-secret-agent" to renamedAgent)
            }

            val response = client.get("/api/v1/status/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val info = apiJson.decodeFromString<ProcessInfoResponse>(response.bodyAsText())
            assertEquals("***", info.inputParams["TOKEN"])
            assertEquals("alice", info.inputParams["USER_N"])
        }

    @Test
    fun `status response redacts all input values when agent definition is gone`() =
        testApplication {
            val pm = realProcessManager()
            val id =
                pm.obtainProcessId(
                    NewRunRecord(
                        "removed-agent",
                        inputParams = mapOf("TOKEN" to "hunter2", "USER_N" to "alice"),
                    ),
                )

            mountApiV1Routes {
                every { processManager } returns pm
                every { agentsService.agents } returns emptyMap()
            }

            val response = client.get("/api/v1/status/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val info = apiJson.decodeFromString<ProcessInfoResponse>(response.bodyAsText())
            assertTrue(info.inputParams.values.all { it == "***" })
        }

    // ========== GET /status/{processId} retainedTruncated Tests (Task 6) ==========

    @Test
    fun `status response surfaces retainedTruncated after a retention prune`() =
        testApplication {
            val pm = realProcessManager()
            val id = pm.obtainProcessId(NewRunRecord("retain-agent"))
            pm.setProcessStatus(id, ProcessStatus.FINISHED)
            pm.markWorkspacePurged(id, retainedTruncated = true)

            mountApiV1Routes {
                every { processManager } returns pm
            }

            val response = client.get("/api/v1/status/$id")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"retainedTruncated\""), "Response should contain retainedTruncated")
            val info = apiJson.decodeFromString<ProcessInfoResponse>(body)
            assertEquals(true, info.retainedTruncated)
        }

    // ========== GET /status/all Tests ==========

    // Real apiV1Routes handler over a real ProcessManager, so parse -> getAllProcesses -> serialize runs;
    // query-parameter validation is pinned by StatusAllValidationTest.

    @Test
    fun `GET status all should return all processes without filters`() =
        testApplication {
            val pm = realProcessManager()
            val id1 = pm.obtainProcessId(NewRunRecord("test-agent"))
            val id2 = pm.obtainProcessId(NewRunRecord("test-agent"))
            pm.setProcessStatus(id1, ProcessStatus.WORKING)
            pm.setProcessStatus(id2, ProcessStatus.FINISHED)

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(2, result.total)
            assertEquals(0, result.offset)
            assertEquals(100, result.limit)
            assertEquals(setOf(id1, id2), result.processes.map { it.id }.toSet())
        }

    @Test
    fun `GET status all should return the root id on each row and filter by rootProcessId`() =
        testApplication {
            val pm = realProcessManager()
            val root = pm.obtainProcessId(NewRunRecord("test-agent"))
            val segment = pm.obtainProcessId(NewRunRecord("test-agent", parentProcessId = root, rootProcessId = root))
            val grandchild = pm.obtainProcessId(NewRunRecord("test-agent", parentProcessId = segment, rootProcessId = root))
            val unrelated = pm.obtainProcessId(NewRunRecord("test-agent"))

            mountStatusAllRoutes(pm)

            val all = client.get("/api/v1/status/all?order=asc")
            assertEquals(HttpStatusCode.OK, all.status)
            val allResult = apiJson.decodeFromString<ProcessListResponse>(all.bodyAsText())
            assertEquals(null, allResult.processes.first { it.id == root }.rootProcessId)
            assertEquals(root, allResult.processes.first { it.id == segment }.rootProcessId)

            // One paginated read covers the whole flow: both hops below the root, in id order.
            val flow = client.get("/api/v1/status/all?rootProcessId=$root&order=asc")
            assertEquals(HttpStatusCode.OK, flow.status)
            val flowResult = apiJson.decodeFromString<ProcessListResponse>(flow.bodyAsText())
            assertEquals(listOf(segment, grandchild), flowResult.processes.map { it.id })
            assertEquals(2, flowResult.total)
            assertTrue(unrelated !in flowResult.processes.map { it.id })
        }

    @Test
    fun `GET status all should filter to flow roots with roots=true`() =
        testApplication {
            val pm = realProcessManager()
            val rootA = pm.obtainProcessId(NewRunRecord("test-agent"))
            val rootB = pm.obtainProcessId(NewRunRecord("test-agent"))
            pm.obtainProcessId(NewRunRecord("test-agent", parentProcessId = rootA, rootProcessId = rootA))
            pm.obtainProcessId(NewRunRecord("test-agent", parentProcessId = rootB, rootProcessId = rootB))

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?roots=true&order=asc")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(listOf(rootA, rootB), result.processes.map { it.id })
            assertEquals(2, result.total)
        }

    @Test
    fun `GET status all should report one root for a continuation chain three segments deep`() =
        testApplication {
            // A real ProcessManager and ContinuationService share one database, so every segment row is INSERTed
            // through the production path (obtainProcessId) with the root the fire path derived.
            val db = PersistenceTestSupport.tempDatabase()
            val pm = PersistenceTestSupport.processManager(db = db)
            val questions = PersistenceTestSupport.questionsService(db = db)
            val agentsService = mockk<AgentsService>()
            val target = AgentFixtures.simpleAgent(name = "continuer").copy(dispatch = AgentDispatchConfig(enabled = true))
            every { agentsService.agents } returns mapOf("continuer" to target)

            // The flow's first run: no parent, and therefore no root of its own.
            val root = pm.obtainProcessId(NewRunRecord("continuer"))

            val runStarter = mockk<AgentRunStarter>()
            var lastFired: Int? = null
            // Each fire INSERTs the segment's row with the parent and root the fire path
            // derived, exactly as ProcessExecutor would.
            coEvery {
                runStarter.start(match { it.source == "continuation" }, any(), any(), any())
            } coAnswers {
                pm
                    .obtainProcessId(
                        NewRunRecord(
                            "continuer",
                            parentProcessId = firstArg<RunStartRequest>().parentProcessId,
                            rootProcessId = firstArg<RunStartRequest>().rootProcessId,
                        ),
                    ).also { lastFired = it }
            }
            val continuations =
                PersistenceTestSupport.continuationService(
                    db = db,
                    questionsService = questions,
                    agentsService = agentsService,
                    runStarter = runStarter,
                    inputResolver = mockk(relaxed = true),
                    processManager = pm,
                )

            // Fire three continuations in a chain: each segment registers the next one from
            // itself, so the chain is root -> segment 1 -> segment 2 -> segment 3.
            var originId = root
            repeat(3) {
                kotlinx.coroutines.runBlocking {
                    questions.registerProcess(originId, "continuer")
                    // The watched run is already terminal, so `create` race-closes and fires
                    // the continuation immediately (ADR-0023).
                    val watched = pm.obtainProcessId(NewRunRecord("continuer"))
                    pm.setProcessStatus(watched, ProcessStatus.FINISHED)
                    continuations.create(originId, watched, "continuer", emptyMap(), "HMR_LG_RESUME", null, null)
                }
                originId = assertNotNull(lastFired, "the chain's segment $it did not fire")
            }
            // The last segment's stored row carries the flow root, not its own id.
            assertEquals(root, pm.getProcessInfo(originId)?.rootProcessId)

            mountStatusAllRoutes(pm)

            val flow = client.get("/api/v1/status/all?rootProcessId=$root&order=asc")
            assertEquals(HttpStatusCode.OK, flow.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(flow.bodyAsText())
            // Three segments below the root, every one of them in the same flow.
            assertEquals(3, result.total)
            assertTrue(
                result.processes.all { it.rootProcessId == root },
                "every descendant must report the flow root; got ${result.processes.map { it.id to it.rootProcessId }}",
            )
            assertEquals(null, pm.getProcessInfo(root)?.rootProcessId, "the root run stays rootless")
        }

    @Test
    fun `GET status all should filter by single status type`() =
        testApplication {
            val pm = realProcessManager()
            val working = pm.obtainProcessId(NewRunRecord("test-agent"))
            val finished = pm.obtainProcessId(NewRunRecord("test-agent"))
            pm.setProcessStatus(working, ProcessStatus.WORKING)
            pm.setProcessStatus(finished, ProcessStatus.FINISHED)

            mountStatusAllRoutes(pm)

            // Lowercase on purpose: also pins the parser's case-insensitive types.
            val response = client.get("/api/v1/status/all?types=working")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(1, result.total)
            assertEquals(listOf(working), result.processes.map { it.id })
            assertEquals("WORKING", result.processes.single().status)
        }

    @Test
    fun `GET status all should filter by multiple status types`() =
        testApplication {
            val pm = realProcessManager()
            val working = pm.obtainProcessId(NewRunRecord("test-agent"))
            val finished = pm.obtainProcessId(NewRunRecord("test-agent"))
            val failed = pm.obtainProcessId(NewRunRecord("test-agent"))
            pm.setProcessStatus(working, ProcessStatus.WORKING)
            pm.setProcessStatus(finished, ProcessStatus.FINISHED)
            pm.setProcessStatus(failed, ProcessStatus.FAILED)

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?types=WORKING,FINISHED")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(2, result.total)
            assertEquals(setOf(working, finished), result.processes.map { it.id }.toSet())
            assertEquals(setOf("WORKING", "FINISHED"), result.processes.map { it.status }.toSet())
        }

    @Test
    fun `GET status all should order ascending when specified`() =
        testApplication {
            val pm = realProcessManager()
            val id1 = pm.obtainProcessId(NewRunRecord("test-agent"))
            val id2 = pm.obtainProcessId(NewRunRecord("test-agent"))
            val id3 = pm.obtainProcessId(NewRunRecord("test-agent"))

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?order=asc")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            // Ascending order: presence and position pinned together.
            assertEquals(listOf(id1, id2, id3), result.processes.map { it.id })
        }

    @Test
    fun `GET status all should return empty list when no processes exist`() =
        testApplication {
            val pm = realProcessManager()

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(0, result.total)
            assertTrue(result.processes.isEmpty())
        }

    @Test
    fun `GET status all should apply pagination with offset and limit`() =
        testApplication {
            val pm = realProcessManager()
            val ids = List(10) { pm.obtainProcessId(NewRunRecord("test-agent")) }

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?offset=2&limit=2")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(10, result.total)
            assertEquals(2, result.offset)
            assertEquals(2, result.limit)
            // Default order is descending by id; offset 2 / limit 2 selects the 3rd and 4th newest.
            assertEquals(ids.sortedDescending().subList(2, 4), result.processes.map { it.id })
        }

    @Test
    fun `GET status all should filter by agent name`() =
        testApplication {
            val pm = realProcessManager()
            val alphaProc = pm.obtainProcessId(NewRunRecord("agent-alpha"))
            val betaProc = pm.obtainProcessId(NewRunRecord("agent-beta"))

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?agentName=agent-alpha")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(1, result.total)
            assertEquals(listOf(alphaProc), result.processes.map { it.id })
            assertEquals("agent-alpha", result.processes.single().agentName)
        }

    @Test
    fun `GET status all should combine filtering ordering and pagination`() =
        testApplication {
            val pm = realProcessManager()
            // 6 processes alternating WORKING/FINISHED. Filter WORKING, order
            // ascending, then offset=1 limit=1 selects the 2nd-oldest WORKING id.
            val workingIds = mutableListOf<Int>()
            repeat(6) { idx ->
                val pid = pm.obtainProcessId(NewRunRecord("test-agent"))
                if (idx % 2 == 0) {
                    pm.setProcessStatus(pid, ProcessStatus.WORKING)
                    workingIds.add(pid)
                } else {
                    pm.setProcessStatus(pid, ProcessStatus.FINISHED)
                }
            }

            mountStatusAllRoutes(pm)

            val response = client.get("/api/v1/status/all?types=WORKING&order=asc&offset=1&limit=1")

            assertEquals(HttpStatusCode.OK, response.status)
            val result = apiJson.decodeFromString<ProcessListResponse>(response.bodyAsText())
            assertEquals(3, result.total)
            assertEquals(1, result.offset)
            assertEquals(1, result.limit)
            assertEquals(listOf(workingIds.sorted()[1]), result.processes.map { it.id })
        }

    // ========== DELETE /kill/{processId} Tests ==========

    @Test
    fun `DELETE kill processId should kill process and return status`() =
        testApplication {
            coEvery { mockProcessManager.getProcessInfo(1) } returns
                ProcessInfo(id = 1, status = ProcessStatus.WORKING, agentName = "test-agent")
            coEvery { mockProcessManager.getProcessStatus(1) } returns ProcessStatus.KILLED
            mountWithServiceMocks()

            val response = client.delete("/api/v1/kill/1")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("KILLED", apiJson.decodeFromString<ProcessStatusResponse>(response.bodyAsText()).status)
            coVerify { mockProcessManager.killProcess(1) }
        }

    @Test
    fun `DELETE kill processId should return BadRequest for invalid ID`() =
        testApplication {
            mountWithServiceMocks()

            val response = client.delete("/api/v1/kill/invalid")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
        }

    // ========== GET /artifacts Tests ==========

    /**
     * Mounts the real routes with a real [ArtifactService] over [files]; every process lookup
     * answers [processInfo], and the service streams at most [maxArtifactBytes].
     */
    private fun ApplicationTestBuilder.mountArtifactRoutes(
        processInfo: ProcessInfo? = null,
        files: LocalFileOperations = LocalFileOperations(),
        maxArtifactBytes: Long = ArtifactService.DEFAULT_MAX_ARTIFACT_BYTES,
    ) {
        coEvery { mockProcessManager.getProcessInfo(any()) } returns processInfo
        mountWithServiceMocks {
            every { artifactService } returns ArtifactService(mockProcessManager, files, logger, maxArtifactBytes)
            every { fileOperations } returns files
        }
    }

    private fun finishedProcessWithArtifacts(
        id: Int,
        artifactsDir: java.nio.file.Path,
    ) = ProcessInfo(
        id = id,
        status = ProcessStatus.FINISHED,
        agentName = "test-agent",
        artifactsDir = artifactsDir,
    )

    @Test
    fun `GET artifacts should return 400 when processId is missing`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts?path=cmd_0.out")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid or missing processId parameter", response.errorMsg())
        }

    @Test
    fun `GET artifacts should return 400 when processId is invalid`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts?processId=invalid&path=cmd_0.out")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid or missing processId parameter", response.errorMsg())
        }

    @Test
    fun `GET artifacts should return 400 when path is missing`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts?processId=123")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Missing path parameter", response.errorMsg())
        }

    @Test
    fun `GET artifacts should return 400 when path contains traversal`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts?processId=123&path=../etc/passwd")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid path: Path traversal (..) is not allowed", response.errorMsg())
            // Path validation runs before the process lookup.
            coVerify(exactly = 0) { mockProcessManager.getProcessInfo(any()) }
        }

    @Test
    fun `GET artifacts should return 404 when process not found`() =
        testApplication {
            mountArtifactRoutes(processInfo = null)

            val response = client.get("/api/v1/artifacts?processId=999&path=cmd_0.out")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Process not found", response.errorMsg())
        }

    @Test
    fun `GET artifacts should return 404 when file not found`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        mountArtifactRoutes(finishedProcessWithArtifacts(123, dir))

        val response = client.get("/api/v1/artifacts?processId=123&path=nonexistent.out")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Artifact file not found", response.errorMsg())
    }

    @Test
    fun `GET artifacts should return 404 for a symlinked artifact`(
        @TempDir dir: java.nio.file.Path,
        @TempDir outside: java.nio.file.Path,
    ) = testApplication {
        val target = Files.writeString(outside.resolve("secret.txt"), "host secret")
        Files.createSymbolicLink(dir.resolve("cmd_0.out"), target)
        mountArtifactRoutes(finishedProcessWithArtifacts(123, dir))

        val response = client.get("/api/v1/artifacts?processId=123&path=cmd_0.out")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Artifact not found", response.errorMsg())
        assertFalse(response.bodyAsText().contains("host secret"))
    }

    @Test
    fun `GET artifacts should return 404 when a symlinked subdirectory escapes the artifacts directory`(
        @TempDir dir: java.nio.file.Path,
        @TempDir outside: java.nio.file.Path,
    ) = testApplication {
        // `sub/secret.txt` passes the path validation (no `..`), so only the containment check
        // at resolve time can refuse it — the realpath view lands outside the artifacts directory.
        val targetDir = Files.createDirectories(outside.resolve("etc"))
        Files.writeString(targetDir.resolve("secret.txt"), "host secret")
        Files.createSymbolicLink(dir.resolve("sub"), targetDir)
        mountArtifactRoutes(finishedProcessWithArtifacts(123, dir))

        val response = client.get("/api/v1/artifacts?processId=123&path=sub/secret.txt")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Artifact not found", response.errorMsg())
        assertFalse(response.bodyAsText().contains("host secret"))
    }

    @Test
    fun `GET artifacts streams a terminal process's artifact and marks it complete`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        dir.resolve("cmd_0.out").toFile().writeText("done\n")
        coEvery { mockProcessManager.getProcessStatus(123) } returns ProcessStatus.FINISHED
        mountArtifactRoutes(finishedProcessWithArtifacts(123, dir))

        val response = client.get("/api/v1/artifacts?processId=123&path=cmd_0.out")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("done\n", response.bodyAsText())
        // Clients poll these two headers to know which run the bytes belong to and whether
        // more are still coming; the status is the process row's, the flag is its liveness.
        assertEquals("FINISHED", response.headers["X-Process-Status"])
        assertEquals("true", response.headers["X-File-Complete"])
    }

    @Test
    fun `GET artifacts marks a still-running process's artifact incomplete`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        dir.resolve("cmd_0.out").toFile().writeText("partial\n")
        coEvery { mockProcessManager.getProcessStatus(123) } returns ProcessStatus.WORKING
        mountArtifactRoutes(finishedProcessWithArtifacts(123, dir).copy(status = ProcessStatus.WORKING))

        val response = client.get("/api/v1/artifacts?processId=123&path=cmd_0.out")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("WORKING", response.headers["X-Process-Status"])
        assertEquals("false", response.headers["X-File-Complete"])
    }

    @Test
    fun `GET artifacts marks a queued process's artifact incomplete`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        // CREATED is an active status too: a queued run has produced nothing final, so dropping it from the active
        // check would serve its artifact as complete before the run started.
        dir.resolve("cmd_0.out").toFile().writeText("partial\n")
        coEvery { mockProcessManager.getProcessStatus(123) } returns ProcessStatus.CREATED
        mountArtifactRoutes(finishedProcessWithArtifacts(123, dir).copy(status = ProcessStatus.CREATED))

        val response = client.get("/api/v1/artifacts?processId=123&path=cmd_0.out")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("CREATED", response.headers["X-Process-Status"])
        assertEquals("false", response.headers["X-File-Complete"])
    }

    @Test
    fun `CWE-770 artifact grown past the cap between size check and open answers 413`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        val artifact = dir.resolve("cmd_0.out")
        Files.writeString(artifact, "small")
        // The pre-flight check sees the small file; the growth lands before the copy's own open, so only the
        // copy-time bound can refuse it (CWE-770, ADR-0047 rule 4).
        val growing =
            LocalFileOperations(
                openTimeoutMs = 60_000,
                beforeOpen = { path -> Files.write(path, ByteArray(4096), StandardOpenOption.APPEND) },
            )
        mountArtifactRoutes(finishedProcessWithArtifacts(123, dir), files = growing, maxArtifactBytes = 1024)

        val response = client.get("/api/v1/artifacts?processId=123&path=cmd_0.out")

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("Artifact exceeds server.maxArtifactBytes (4101 > 1024)", response.errorMsg())
        assertFalse(response.bodyAsText().contains("small"), "no artifact byte may reach the client")
    }

    @Test
    fun `CWE-770 artifact growing during the copy streams at most maxArtifactBytes`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        val artifact = dir.resolve("cmd_0.out")
        Files.writeString(artifact, "0123456789")
        // 4096 bytes land right after the copy's first read, so a copy that stopped only at EOF would send them
        // (CWE-770, ADR-0047 rule 4).
        val growing =
            LocalFileOperations(
                openTimeoutMs = 60_000,
                openRegular = { path ->
                    object : DelegatingChannel(DescriptorOpen.openRegularChannel(path)) {
                        private var grown = false

                        override fun read(dst: ByteBuffer): Int =
                            super.read(dst).also {
                                if (!grown) {
                                    grown = true
                                    Files.write(path, ByteArray(4096), StandardOpenOption.APPEND)
                                }
                            }
                    }
                },
                beforeOpen = {},
            )
        coEvery { mockProcessManager.getProcessStatus(123) } returns ProcessStatus.WORKING
        mountArtifactRoutes(
            finishedProcessWithArtifacts(123, dir).copy(status = ProcessStatus.WORKING),
            files = growing,
            maxArtifactBytes = 10,
        )

        val response = client.get("/api/v1/artifacts?processId=123&path=cmd_0.out")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("0123456789", response.bodyAsText(), "the copy must stop at the cap, not follow the growth")
        assertEquals("false", response.headers["X-File-Complete"])
    }

    // ========== GET /artifacts/list Tests ==========

    @Test
    fun `GET artifacts list returns 400 when processId missing`() =
        testApplication {
            mountArtifactRoutes()

            val response = client.get("/api/v1/artifacts/list")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("Invalid or missing processId parameter", response.errorMsg())
        }

    @Test
    fun `GET artifacts list returns 404 when process unknown`() =
        testApplication {
            mountArtifactRoutes(processInfo = null)

            val response = client.get("/api/v1/artifacts/list?processId=999")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Process not found", response.errorMsg())
        }

    @Test
    fun `GET artifacts list returns 200 with listing on success`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        dir.resolve("cmd_0.out").toFile().writeText("hello world\n")
        mountArtifactRoutes(finishedProcessWithArtifacts(7, dir))

        val response = client.get("/api/v1/artifacts/list?processId=7")

        assertEquals(HttpStatusCode.OK, response.status)
        val listing = apiJson.decodeFromString<ArtifactListingResponse>(response.bodyAsText())
        assertEquals(7, listing.processId)
        assertEquals(listOf("cmd_0.out" to 12L), listing.files.map { it.path to it.size })
    }

    @Test
    fun `GET artifacts list answers 404 with the service's message when the listing fails`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        // A directory the process row names but that is gone (purged workspace): the
        // service reports the reason, and the route must serve it rather than a bare 404.
        mountArtifactRoutes(finishedProcessWithArtifacts(7, dir.resolve("gone")))

        val response = client.get("/api/v1/artifacts/list?processId=7")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Artifacts directory does not exist", response.errorMsg())
    }
}
