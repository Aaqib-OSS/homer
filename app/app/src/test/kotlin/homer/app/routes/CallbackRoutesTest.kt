package homer.app.routes

import homer.app.EnvConstants
import homer.app.apiJson
import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.DispatchGroupTooLargeException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.TooManyPendingDispatchesException
import homer.app.exceptions.ValidationException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.mountCallbackRoutes
import homer.app.infra.LocalFileOperations
import homer.app.infra.SecretRedactor
import homer.app.models.AskQuestionResponse
import homer.app.models.CreateDispatchGroupResponse
import homer.app.models.CreateJoinRequest
import homer.app.models.CreateJoinResponse
import homer.app.models.CreateRunRequest
import homer.app.models.CreateRunResponse
import homer.app.models.ErrorResponse
import homer.app.models.JoinSubSpec
import homer.app.models.NewRunRecord
import homer.app.models.QuestionResponse
import homer.app.models.QuestionsConfig
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.RunMessagesConfig
import homer.app.models.SrcType
import homer.app.models.TagValidation
import homer.app.models.TakeRunMessagesResponse
import homer.app.models.TakenRunMessage
import homer.app.services.ArtifactTooLargeException
import homer.app.services.DispatchGroupService
import homer.app.services.HookEvent
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.RESULT_TRANSFORM_MAX_BYTES
import homer.app.services.RunMessageHookOutput
import homer.app.services.RunMessageStatus
import homer.app.services.RunMessagesService
import homer.app.services.persistence.ResultTokenRecord
import homer.app.services.persistence.RunMessagesRepository
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallbackRoutesTest {
    private fun newService(config: QuestionsConfig = QuestionsConfig()) = PersistenceTestSupport.questionsService(config = config)

    @Test
    fun `ask without a token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/callback/questions") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"anyone?"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `ask with an unknown token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/callback/questions") {
                    header(HttpHeaders.Authorization, "Bearer not-a-token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"anyone?"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `ask returns the question id`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.post("/api/v1/callback/questions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"Deploy to prod?","options":["yes","no"]}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<AskQuestionResponse>(response.bodyAsText())
            assertEquals(body.id, service.get(body.id)?.id)
            assertEquals(listOf("yes", "no"), service.get(body.id)?.options)
        }

    @Test
    fun `check without waitSec returns the current OPEN snapshot immediately`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question = runBlocking { service.ask(1, "Proceed?") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.get("/api/v1/callback/questions/${question.id}") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<QuestionResponse>(response.bodyAsText())
            assertEquals("OPEN", body.status)
            assertEquals(null, body.answer)
        }

    @Test
    fun `long-poll resolves when the answer lands mid-poll`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question = runBlocking { service.ask(1, "Proceed?") }
            mountCallbackRoutes { every { questionsService } returns service }

            coroutineScope {
                val answerJob =
                    launch {
                        delay(200)
                        service.answer(question.id, "yes, go")
                    }

                val response =
                    client.get("/api/v1/callback/questions/${question.id}?waitSec=30") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }

                assertEquals(HttpStatusCode.OK, response.status)
                val body = apiJson.decodeFromString<QuestionResponse>(response.bodyAsText())
                assertEquals("ANSWERED", body.status)
                assertEquals("yes, go", body.answer)
                answerJob.join()
            }
        }

    @Test
    fun `waitSec is clamped to maxWaitSec so the poll returns OPEN`() =
        testApplication {
            val service = newService(QuestionsConfig(maxWaitSec = 1))
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question = runBlocking { service.ask(1, "Proceed?") }
            mountCallbackRoutes { every { questionsService } returns service }

            // Unclamped, this request would hold for ~28 hours and time the
            // test out; returning OPEN promptly proves the clamp applied.
            val response =
                client.get("/api/v1/callback/questions/${question.id}?waitSec=99999") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<QuestionResponse>(response.bodyAsText())
            assertEquals("OPEN", body.status)
        }

    @Test
    fun `non-integer waitSec responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val question = runBlocking { service.ask(1, "Proceed?") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.get("/api/v1/callback/questions/${question.id}?waitSec=soon") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `a foreign process question id responds 404`() =
        testApplication {
            val service = newService()
            val tokenA = runBlocking { service.registerProcess(1, "agent-a") }
            runBlocking { service.registerProcess(2, "agent-b") }
            val foreignQuestion = runBlocking { service.ask(2, "B's question") }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.get("/api/v1/callback/questions/${foreignQuestion.id}") {
                    header(HttpHeaders.Authorization, "Bearer $tokenA")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `ask on a terminal process responds 409`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            runBlocking { service.expireForProcess(1) }
            mountCallbackRoutes { every { questionsService } returns service }

            val response =
                client.post("/api/v1/callback/questions") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"too late?"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    // --- runtime subject tags (POST /api/v1/callback/tags), ADR-0060 ---

    private fun agentWithRuntimeTag(name: String = "agent-a") =
        homer.app.fixtures.AgentFixtures.simpleAgent(name = name).copy(
            tags = homer.app.models.Tags(fromInputs = listOf("mr"), runtime = listOf("decision")),
        )

    @Test
    fun `declare tag without a token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/callback/tags") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `declare tag stores it against the token's own run and returns the count`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                    coEvery { processManager.addRuntimeTag(1, "decision:approved", 20, setOf("mr")) } returns
                        homer.app.services.persistence.ProcessRepository
                            .RuntimeTagResult(written = true, count = 1)
                }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<homer.app.models.DeclareTagResponse>(response.bodyAsText())
            assertEquals("decision:approved", body.tag)
            assertEquals(1, body.count)
            // The run id comes from the token, never the body; fromInputs keys pass through, excluded from the ceiling.
            // Resolve the child mock outside verify: the handler also reads `processManager`, which verify would count.
            val pm = deps.processManager
            coVerify(exactly = 1) { pm.addRuntimeTag(1, "decision:approved", 20, setOf("mr")) }
        }

    @Test
    fun `re-declaring the same tag collapses to one and keeps the count idempotent`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                // The repository collapses a repeat to the same set: written=true, count unchanged.
                coEvery { processManager.addRuntimeTag(1, "decision:approved", 20, setOf("mr")) } returns
                    homer.app.services.persistence.ProcessRepository
                        .RuntimeTagResult(written = true, count = 1)
            }

            repeat(2) {
                val response =
                    client.post("/api/v1/callback/tags") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"tag":"decision:approved"}""")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(1, apiJson.decodeFromString<homer.app.models.DeclareTagResponse>(response.bodyAsText()).count)
            }
        }

    @Test
    fun `declare tag on a terminal run responds 409`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            runBlocking { service.expireForProcess(1) }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `declare tag with an undeclared runtime key responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"undeclared:x"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("not a declared tags.runtime key"))
        }

    @Test
    fun `declare tag whose key is a fromInputs name is refused as an override`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            // `mr` is a fromInputs key, not a runtime key: runtime tags cannot override it.
            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"mr:15"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("cannot override"))
        }

    @Test
    fun `declare tag with a malformed value responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            // A colon in the value breaks the single-colon `key:value` split; charset rejects it.
            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:has:colon"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    /**
     * The redactor a run registers: built the way `Process` builds it, from the workspace
     * env restricted to the secret names plus the callback token the service minted.
     */
    private fun redactorFor(
        env: Map<String, String>,
        secretEnvNames: Set<String>,
    ): (String) -> SecretRedactor =
        { token ->
            SecretRedactor.from(
                env + (EnvConstants.CALLBACK_TOKEN to token),
                secretEnvNames + EnvConstants.CALLBACK_TOKEN,
                minNeedleLength = TagValidation.SECRET_NEEDLE_MIN_LENGTH,
            )
        }

    private val secretEnv =
        mapOf(
            // A `secret: true` input.
            "TOKEN" to "s3cr3tvalue1",
            // An operator credential forwarded via `@SYSENV:` — never an input, so
            // `inputParams` does not carry it; only the workspace redactor does.
            "API_KEY" to "hostcred-abcdef",
            // A secret below the needle floor: unprotectable by matching.
            "PIN" to "1234",
            "MR_IID" to "15",
        )
    private val secretNames = setOf("TOKEN", "API_KEY", "PIN")

    private suspend fun io.ktor.client.HttpClient.declareTag(
        token: String,
        tag: String,
    ) = post("/api/v1/callback/tags") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody("""{"tag":"$tag"}""")
    }

    private fun assertRefusedAsSecret(
        response: io.ktor.client.statement.HttpResponse,
        deps: homer.app.routes.ApiDeps,
    ) = runBlocking {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("secret"))
        // A refused value is not persisted; the child mock is resolved outside verify so the accessor is uncounted.
        val pm = deps.processManager
        coVerify(exactly = 0) { pm.addRuntimeTag(any(), any(), any(), any()) }
    }

    @Test
    fun `sensitive_data_exposure - declare tag whose value equals a secret input is refused 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                }

            assertRefusedAsSecret(client.declareTag(token, "decision:s3cr3tvalue1"), deps)
        }

    @Test
    fun `sensitive_data_exposure - declare tag embedding a secret input as a substring is refused 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                }

            assertRefusedAsSecret(client.declareTag(token, "decision:pre-s3cr3tvalue1-post"), deps)
        }

    @Test
    fun `sensitive_data_exposure - declare tag carrying a SYSENV-sourced credential is refused 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                }

            assertRefusedAsSecret(client.declareTag(token, "decision:hostcred-abcdef"), deps)
        }

    @Test
    fun `sensitive_data_exposure - declare tag carrying the run's own callback token is refused 400`() =
        testApplication {
            // A published callback token would let any reader of /status/all act as this run.
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                }

            assertRefusedAsSecret(client.declareTag(token, "decision:$token"), deps)
        }

    @Test
    fun `a secret shorter than the needle floor does not refuse a tag that contains it`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a", redactorFor(secretEnv, secretNames)) }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                coEvery { processManager.addRuntimeTag(1, "decision:1234x", 20, setOf("mr")) } returns
                    homer.app.services.persistence.ProcessRepository
                        .RuntimeTagResult(written = true, count = 1)
            }

            val response = client.declareTag(token, "decision:1234x")

            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `declare tag past the per-run ceiling responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
                coEvery { processManager.addRuntimeTag(1, "decision:approved", 20, setOf("mr")) } returns
                    homer.app.services.persistence.ProcessRepository
                        .RuntimeTagResult(written = false, count = 20)
            }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"tag":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("maximum"))
        }

    @Test
    fun `declare tag with a malformed body responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                every { agentsService.agents } returns mapOf("agent-a" to agentWithRuntimeTag())
            }

            val response =
                client.post("/api/v1/callback/tags") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"nope":"decision:approved"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `dispatch run without a token or with an unknown one responds 401 and calls no service`() =
        testApplication {
            val deps = mountCallbackRoutes { every { questionsService } returns newService() }
            // Read the child mock out of the wiring mock first: `deps.continuationService` inside a
            // verification block would be verified too, and the builder reads it at mount.
            val continuations = deps.continuationService

            val missing =
                client.post("/api/v1/callback/runs") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }
            val unknown =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer nope")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            coVerify(exactly = 0) { continuations.startWithContinuation(any(), any()) }
        }

    @Test
    fun `dispatch run returns the new process id`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val request = slot<CreateRunRequest>()
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    coEvery {
                        continuationService.startWithContinuation(1, capture(request))
                    } returns CreateRunResponse(processId = 99)
                }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    val body =
                        """{"agent":"target","params":{"foo":"bar"},"continueAgent":"parent",""" +
                            """"continueThread":"t1","continueParams":{"decision":"approved"},""" +
                            """"resultJsonPath":"$.result","ttlSec":600}"""
                    setBody(body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(99, apiJson.decodeFromString<CreateRunResponse>(response.bodyAsText()).processId)
            coVerify(exactly = 1) { deps.continuationService.startWithContinuation(1, any()) }
            assertEquals(
                CreateRunRequest(
                    agent = "target",
                    params = mapOf("foo" to "bar"),
                    continueAgent = "parent",
                    continueThread = "t1",
                    continueParams = mapOf("decision" to "approved"),
                    resultJsonPath = "$.result",
                    ttlSec = 600,
                ),
                request.captured,
                "the route forwards the decoded request to the service",
            )
        }

    @Test
    fun `dispatch run to a non-dispatchable agent responds 403`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { continuationService.startWithContinuation(any(), any()) } throws
                    AgentNotDispatchableException("target")
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `dispatch run to an unknown agent responds 404`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { continuationService.startWithContinuation(any(), any()) } throws
                    DispatchAgentNotFoundException("nope")
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"nope"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `callback runs responds 404 when the nested dispatch hop is unauthorized`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { continuationService.startWithContinuation(any(), any()) } throws
                    DispatchAgentNotFoundException("target")
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Dispatch target agent 'target' not found"))
        }

    @Test
    fun `callback joins responds 404 when a sub hop is unauthorized`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { continuationService.startJoin(any(), any()) } throws DispatchAgentNotFoundException("b")
            }

            val response =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"subs":[{"agent":"b","interruptId":"i1"}],"continueAgent":"parent"}""")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // The sub is started and the continuation registered inside ContinuationService now, so the
    // "a refused continuation starts nothing" invariants are asserted in ContinuationServiceTest.
    @Test
    fun `callback joins returns the started sub ids and the join id`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val request = slot<CreateJoinRequest>()
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { continuationService.startJoin(1, capture(request)) } returns
                    CreateJoinResponse(processIds = listOf(10, 11), continuationId = 12)
            }

            val response =
                client.post("/api/v1/callback/joins") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    val body =
                        """{"subs":[{"agent":"b","params":{"k":"v"},"resultJsonPath":"$.r","interruptId":"i1"}],""" +
                            """"continueAgent":"parent","continueThread":"t1",""" +
                            """"continueParams":{"decision":"approved"},"ttlSec":300}"""
                    setBody(body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                CreateJoinResponse(processIds = listOf(10, 11), continuationId = 12),
                apiJson.decodeFromString<CreateJoinResponse>(response.bodyAsText()),
            )
            assertEquals(
                CreateJoinRequest(
                    subs =
                        listOf(
                            JoinSubSpec(agent = "b", params = mapOf("k" to "v"), resultJsonPath = "$.r", interruptId = "i1"),
                        ),
                    continueAgent = "parent",
                    continueThread = "t1",
                    continueParams = mapOf("decision" to "approved"),
                    ttlSec = 300,
                ),
                request.captured,
                "the route forwards the decoded request to the service",
            )
        }

    @Test
    fun `dispatch run from a dead origin process responds 409`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { continuationService.startWithContinuation(any(), any()) } throws QuestionsClosedForProcessException(1)
            }

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `dispatch run with a malformed body responds 400 and calls no service`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps = mountCallbackRoutes { every { questionsService } returns service }
            val continuations = deps.continuationService

            val response =
                client.post("/api/v1/callback/runs") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"missing":"agent"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            coVerify(exactly = 0) { continuations.startWithContinuation(any(), any()) }
        }

    // --- grouped ask-and-dispatch (POST /api/v1/callback/dispatch-groups) ---

    @Test
    fun `dispatch group without a token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response =
                client.post("/api/v1/callback/dispatch-groups") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"questions":[{"text":"q"}],"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `dispatch group returns the dispatch id and one question id per question`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    coEvery {
                        dispatchGroupService.create(1, any(), "target", "answer", emptyMap(), null)
                    } returns DispatchGroupService.CreateResult(11, listOf("q1", "q2"))
                }

            val response =
                client.post("/api/v1/callback/dispatch-groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"questions":[{"text":"q1"},{"text":"q2","options":["a"]}],"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<CreateDispatchGroupResponse>(response.bodyAsText())
            assertEquals(11, body.dispatchId)
            assertEquals(listOf("q1", "q2"), body.questionIds)
            // The route maps the request's questions onto the service's own spec type, options and all.
            coVerify {
                deps.dispatchGroupService.create(
                    1,
                    listOf(
                        DispatchGroupService.QuestionSpec("q1"),
                        DispatchGroupService.QuestionSpec("q2", listOf("a")),
                    ),
                    "target",
                    "answer",
                    emptyMap(),
                    null,
                )
            }
        }

    // The bound on the multiplication is the service's; the route must carry its refusal out as a
    // 400 rather than a 500, and register nothing.
    @Test
    fun `dispatch group over the size bound responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchGroupService.create(any(), any(), any(), any(), any(), any()) } throws
                    DispatchGroupTooLargeException(8)
            }

            val response =
                client.post("/api/v1/callback/dispatch-groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"questions":[{"text":"q"}],"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertContains(response.bodyAsText(), "at most 8")
        }

    @Test
    fun `dispatch group of no questions responds 400`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchGroupService.create(any(), any(), any(), any(), any(), any()) } throws
                    ValidationException("A dispatch group needs at least one question", "questions")
            }

            val response =
                client.post("/api/v1/callback/dispatch-groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"questions":[],"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // A target that exists but never opted in is refused as 403, distinct from the 404 an
    // unknown or unauthorized one gets — the same mapping the single-dispatch callback has.
    @Test
    fun `dispatch group to a non-dispatchable agent responds 403`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchGroupService.create(any(), any(), any(), any(), any(), any()) } throws
                    AgentNotDispatchableException("target")
            }

            val response =
                client.post("/api/v1/callback/dispatch-groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"questions":[{"text":"q"}],"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertContains(response.bodyAsText(), "not an allowed dispatch target")
        }

    @Test
    fun `dispatch group past the pending cap responds 429 and to an unknown agent 404`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            // One stub, two outcomes: the second request must hit the same mounted route.
            var refusal: Exception = TooManyPendingDispatchesException(100)
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchGroupService.create(any(), any(), any(), any(), any(), any()) } answers { throw refusal }
            }
            val body = """{"questions":[{"text":"q"}],"agent":"target"}"""

            val capped =
                client.post("/api/v1/callback/dispatch-groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.TooManyRequests, capped.status)

            refusal = DispatchAgentNotFoundException("target")
            val unknown =
                client.post("/api/v1/callback/dispatch-groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.NotFound, unknown.status)
        }

    @Test
    fun `dispatch group from a dead origin responds 409`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { dispatchGroupService.create(any(), any(), any(), any(), any(), any()) } throws
                    QuestionsClosedForProcessException(1)
            }

            val response =
                client.post("/api/v1/callback/dispatch-groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"questions":[{"text":"q"}],"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }

    @Test
    fun `dispatch group with a malformed body responds 400 and registers nothing`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps: ApiDeps = mountCallbackRoutes { every { questionsService } returns service }
            val groups = deps.dispatchGroupService

            val response =
                client.post("/api/v1/callback/dispatch-groups") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody("""{"agent":"target"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            coVerify(exactly = 0) { groups.create(any(), any(), any(), any(), any(), any()) }
        }

    // --- read-by-reference result fetch (GET /api/v1/callback/results/{token}) ---

    private val summary = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.summary"))

    private fun record(
        transformer: ResultEnvParam = summary,
        consumerAgent: String = "agent-a",
    ) = ResultTokenRecord(
        token = "tok-1",
        watchedProcessId = 42,
        path = "cmd_0.out",
        resultTransformer = transformer,
        sizeBytes = 3,
        createdAt = 1,
        expiresAt = null,
        consumerAgent = consumerAgent,
    )

    /** Serves [file] as the token's artifact, checked at [size] bytes and read through the real bounded read. */
    private fun ApiDeps.servesResult(
        file: Path,
        size: Long = Files.size(file),
    ) {
        coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(file)
        every { fileOperations.fileExists(any()) } returns true
        every { artifactService.checkArtifactSize(any()) } returns Result.success(size)
        every { fileOperations.readTextBounded(any(), any()) } answers {
            LocalFileOperations().readTextBounded(firstArg(), secondArg())
        }
    }

    private fun resultFile(content: String): Path = Files.createTempFile("result", ".out").also { it.writeText(content) }

    @Test
    fun `result fetch without a callback token responds 401`() =
        testApplication {
            mountCallbackRoutes { every { questionsService } returns newService() }

            val response = client.get("/api/v1/callback/results/tok-1")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `result fetch with a bad or expired result token responds 401`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns null
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `result fetch is reusable within the TTL`() =
        testApplication {
            val file = resultFile("""{"summary":"DONE"}""")
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                servesResult(file)
            }

            repeat(2) {
                val response =
                    client.get("/api/v1/callback/results/tok-1") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("DONE", response.bodyAsText())
            }
        }

    @Test
    fun `result fetch applies the transformer server-side`() =
        testApplication {
            val file = resultFile("""{"summary":"DONE"}""")
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                servesResult(file)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("DONE", response.bodyAsText())
        }

    @Test
    fun `result fetch reads the artifact bounded at the transform cap, never whole`() =
        testApplication {
            val file = resultFile("""{"summary":"DONE"}""")
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    coEvery { resultTokenService.resolve("tok-1") } returns record()
                    servesResult(file)
                }

            client.get("/api/v1/callback/results/tok-1") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            val files = deps.fileOperations
            verify { files.readTextBounded(file, RESULT_TRANSFORM_MAX_BYTES) }
            verify(exactly = 0) { files.readText(any()) }
        }

    @Test
    fun `result fetch of a hard-linked artifact responds 404 without serving it`() =
        testApplication {
            val file = resultFile("""{"summary":"ELSEWHERE"}""")
            Files.createLink(file.resolveSibling("${file.fileName}.link"), file)
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                servesResult(file)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertFalse(response.bodyAsText().contains("ELSEWHERE"))
        }

    @Test
    fun `result fetch of an artifact swapped for a symlink after resolve responds 404 without following it`() =
        testApplication {
            val outside = resultFile("""{"summary":"HOST-SECRET"}""")
            val link = Files.createTempDirectory("result").resolve("cmd_0.out")
            Files.createSymbolicLink(link, outside)
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                servesResult(link, size = 18L)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertFalse(response.bodyAsText().contains("HOST-SECRET"))
        }

    @Test
    fun `result fetch of an artifact grown past the transform cap after its size check responds 404`() =
        testApplication {
            val file = resultFile("""{"summary":"DONE"}""" + " ".repeat(RESULT_TRANSFORM_MAX_BYTES.toInt()))
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                servesResult(file, size = 18L)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // The by-reference fetch reads through the same mapper as `resultEnvParam` (ADR-0025), so an
    // object-selecting transformer serves compact JSON as `text/plain` — opaque data, never a path.
    @Test
    fun `result fetch serializes a selected object as compact JSON`() =
        testApplication {
            val file = resultFile("""{"summary":{"a":1,"b":"x"}}""")
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                servesResult(file)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("""{"a":1,"b":"x"}""", response.bodyAsText())
            assertEquals(ContentType.Text.Plain, response.contentType()?.withoutParameters())
        }

    @Test
    fun `result fetch with a transformer at exactly the transform cap still transforms`() =
        testApplication {
            // The cap is inclusive: an artifact of exactly RESULT_TRANSFORM_MAX_BYTES is the
            // largest one that still transforms, so `>` (not `>=`) is the boundary to pin.
            val file = resultFile("""{"summary":"DONE"}""")
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                servesResult(file, size = RESULT_TRANSFORM_MAX_BYTES)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("DONE", response.bodyAsText())
        }

    @Test
    fun `result fetch with a transformer over the transform cap responds 413 without reading the file`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            val deps =
                mountCallbackRoutes {
                    every { questionsService } returns service
                    coEvery { resultTokenService.resolve("tok-1") } returns record()
                    servesResult(Path.of("/x/cmd_0.out"), size = RESULT_TRANSFORM_MAX_BYTES + 1)
                }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertContains(response.bodyAsText(), "too large to transform")
            val files = deps.fileOperations
            verify(exactly = 0) { files.readTextBounded(any(), any()) }
        }

    @Test
    fun `result fetch whose transformer matches nothing responds an empty body`() =
        testApplication {
            val file = resultFile("""{"other":"x"}""")
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                servesResult(file)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `result fetch by an agent other than the token's audience responds 403`() =
        testApplication {
            // A leaked token presented by a process running a DIFFERENT agent must not
            // redeem it, even though that process holds a valid callback token.
            val service = newService()
            val foreignToken = runBlocking { service.registerProcess(2, "agent-b") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record(consumerAgent = "agent-a")
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $foreignToken")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `result fetch by a second process of the same agent still succeeds`() =
        testApplication {
            // A resumed LangGraph segment re-runs the agent as a NEW process and re-fetches the same token; audience
            // binding is by agent, not process, so that cross-segment re-fetch must keep working.
            val file = resultFile("""{"summary":"DONE"}""")
            val service = newService()
            val secondSegmentToken = runBlocking { service.registerProcess(2, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record(consumerAgent = "agent-a")
                servesResult(file)
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $secondSegmentToken")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("DONE", response.bodyAsText())
        }

    @Test
    fun `result fetch responds 404 when the artifact is gone`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(Path.of("/x/cmd_0.out"))
                every { fileOperations.fileExists(any()) } returns false
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `result fetch responds 413 when the artifact is oversized`() =
        testApplication {
            val service = newService()
            val token = runBlocking { service.registerProcess(1, "agent-a") }
            mountCallbackRoutes {
                every { questionsService } returns service
                coEvery { resultTokenService.resolve("tok-1") } returns record()
                coEvery { artifactService.resolveArtifactPath(42, "cmd_0.out") } returns Result.success(Path.of("/x/cmd_0.out"))
                every { fileOperations.fileExists(any()) } returns true
                every { artifactService.checkArtifactSize(any()) } returns
                    Result.failure(ArtifactTooLargeException(size = 999, maxBytes = 10))
            }

            val response =
                client.get("/api/v1/callback/results/tok-1") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    private class Inbox(
        val questions: QuestionsService,
        val messages: RunMessagesService,
        val processes: ProcessManager,
        val processId: Int,
        val token: String,
    )

    private fun inbox(): Inbox {
        val db = PersistenceTestSupport.tempDatabase()
        val questions = PersistenceTestSupport.questionsService(db = db)
        val messages =
            RunMessagesService(RunMessagesRepository(db), ProcessEventBroadcaster(), RunMessagesConfig(), TestFixtures.mockLogger())
        val pm = PersistenceTestSupport.processManager(db = db)
        val id = runBlocking { pm.obtainProcessId(NewRunRecord("agent-a")) }
        val token = runBlocking { questions.registerProcess(id, "agent-a") }
        return Inbox(questions, messages, pm, id, token)
    }

    private fun ApplicationTestBuilder.mountInbox(inbox: Inbox) =
        mountCallbackRoutes {
            every { questionsService } returns inbox.questions
            every { runMessagesService } returns inbox.messages
        }

    @Test
    fun `take without a hook returns the queued messages and marks them delivered`() =
        testApplication {
            val inbox = inbox()
            val first = runBlocking { inbox.messages.send(inbox.processId, "alice", "one") }
            val second = runBlocking { inbox.messages.send(inbox.processId, "alice", "two") }
            mountInbox(inbox)

            val response = client.post("/api/v1/callback/messages/take") { header(HttpHeaders.Authorization, "Bearer ${inbox.token}") }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<TakeRunMessagesResponse>(response.bodyAsText())
            assertEquals(
                listOf(TakenRunMessage(first.id, "one", first.createdAt), TakenRunMessage(second.id, "two", second.createdAt)),
                body.messages,
            )
            assertEquals(
                listOf(RunMessageStatus.DELIVERED, RunMessageStatus.DELIVERED),
                runBlocking { inbox.messages.list(inbox.processId) }.map { it.status },
            )
        }

    @Test
    fun `take without a hook on an empty inbox returns an empty list`() =
        testApplication {
            val inbox = inbox()
            mountInbox(inbox)

            val response = client.post("/api/v1/callback/messages/take") { header(HttpHeaders.Authorization, "Bearer ${inbox.token}") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(TakeRunMessagesResponse(emptyList()), apiJson.decodeFromString(response.bodyAsText()))
        }

    @Test
    fun `take with the stop hook and nothing queued is 204 with no body`() =
        testApplication {
            val inbox = inbox()
            mountInbox(inbox)

            val response =
                client.post("/api/v1/callback/messages/take?hook=stop") { header(HttpHeaders.Authorization, "Bearer ${inbox.token}") }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `take with the post-tool-use hook returns the hook JSON`() =
        testApplication {
            val inbox = inbox()
            val sent = runBlocking { inbox.messages.send(inbox.processId, "alice", "say \"hi\"\nnow") }
            mountInbox(inbox)

            val response =
                client.post("/api/v1/callback/messages/take?hook=post-tool-use") {
                    header(HttpHeaders.Authorization, "Bearer ${inbox.token}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val delivered = sent.copy(status = RunMessageStatus.DELIVERED)
            assertEquals(
                RunMessageHookOutput.render(HookEvent.POST_TOOL_USE, listOf(delivered)),
                Json.parseToJsonElement(response.bodyAsText()),
            )
        }

    @Test
    fun `take with the stop hook blocks with the messages as the reason`() =
        testApplication {
            val inbox = inbox()
            val sent = runBlocking { inbox.messages.send(inbox.processId, "alice", "keep going") }
            mountInbox(inbox)

            val response =
                client.post("/api/v1/callback/messages/take?hook=stop") { header(HttpHeaders.Authorization, "Bearer ${inbox.token}") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                RunMessageHookOutput.render(HookEvent.STOP, listOf(sent)),
                Json.parseToJsonElement(response.bodyAsText()),
            )
        }

    @Test
    fun `take with an unknown hook is 400 and takes nothing`() =
        testApplication {
            val inbox = inbox()
            runBlocking { inbox.messages.send(inbox.processId, "alice", "one") }
            mountInbox(inbox)

            val response =
                client.post("/api/v1/callback/messages/take?hook=nope") { header(HttpHeaders.Authorization, "Bearer ${inbox.token}") }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(listOf(RunMessageStatus.QUEUED), runBlocking { inbox.messages.list(inbox.processId) }.map { it.status })
        }

    @Test
    fun `take on a closed run is 409 and leaves the messages queued`() =
        testApplication {
            val inbox = inbox()
            runBlocking { inbox.messages.send(inbox.processId, "alice", "one") }
            runBlocking { inbox.questions.expireForProcess(inbox.processId) }
            mountInbox(inbox)

            val response = client.post("/api/v1/callback/messages/take") { header(HttpHeaders.Authorization, "Bearer ${inbox.token}") }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(
                ErrorResponse(msg = "Process ${inbox.processId} is no longer accepting messages"),
                apiJson.decodeFromString(response.bodyAsText()),
            )
            assertEquals(listOf(RunMessageStatus.QUEUED), runBlocking { inbox.messages.list(inbox.processId) }.map { it.status })
        }

    @Test
    fun `take without a token or with an unknown token is 401`() =
        testApplication {
            val inbox = inbox()
            runBlocking { inbox.messages.send(inbox.processId, "alice", "one") }
            mountInbox(inbox)

            val missing = client.post("/api/v1/callback/messages/take")
            val unknown = client.post("/api/v1/callback/messages/take") { header(HttpHeaders.Authorization, "Bearer not-a-token") }

            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            assertEquals(listOf(RunMessageStatus.QUEUED), runBlocking { inbox.messages.list(inbox.processId) }.map { it.status })
        }

    @Test
    fun `a child run's token takes only the child's messages, never its parent's`() =
        testApplication {
            val parent = inbox()
            val childId = runBlocking { parent.processes.obtainProcessId(NewRunRecord("child", parentProcessId = parent.processId)) }
            val childToken = runBlocking { parent.questions.registerProcess(childId, "child") }
            runBlocking { parent.messages.send(parent.processId, "alice", "for the parent") }
            val forChild = runBlocking { parent.messages.send(childId, "alice", "for the child") }
            mountInbox(parent)

            val response = client.post("/api/v1/callback/messages/take") { header(HttpHeaders.Authorization, "Bearer $childToken") }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<TakeRunMessagesResponse>(response.bodyAsText())
            assertEquals(listOf(forChild.id), body.messages.map { it.id })
            assertEquals(listOf(RunMessageStatus.QUEUED), runBlocking { parent.messages.list(parent.processId) }.map { it.status })
        }
}
