package homer.e2e

import homer.e2e.support.Awaits
import homer.e2e.support.Continuation
import homer.e2e.support.ContinuationList
import homer.e2e.support.DispatchList
import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ProcessList
import homer.e2e.support.QuestionsList
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The operator auth boundary: no credential is `401`; a token granted on another agent gets the not-found `404`
 * (ADR-0047 rule 3) on one-resource routes, filtered `200` on lists. `echoScoped` is the wrong-agent probe;
 * dispatch goes through `continuation-inherit`, since `dispatcher` dispatches to `echo`, which it can read.
 */
class AuthBoundaryTest : E2eTestBase() {
    private val scopedAuth = "Bearer echo-scoped-token"

    /** An id no row can hold: the not-found half of the ADR-0047 rule 3 body comparison. */
    private val unknownId = 2_000_000_000

    /**
     * Every operator resource family and one request per route, as `family, method, path`. The ids
     * need not exist: without a credential the `401` is decided before the handler runs.
     */
    private val noCredentialProbes =
        listOf(
            Triple("process", HttpMethod.Get, "/api/v1/status/1"),
            Triple("process", HttpMethod.Get, "/api/v1/status/all"),
            Triple("process", HttpMethod.Delete, "/api/v1/kill/1"),
            Triple("process", HttpMethod.Post, "/api/v1/processes/1/retry"),
            Triple("question", HttpMethod.Get, "/api/v1/questions"),
            Triple("question", HttpMethod.Post, "/api/v1/questions/00000000-0000-0000-0000-000000000000/answer"),
            Triple("dispatch", HttpMethod.Get, "/api/v1/dispatches"),
            Triple("dispatch", HttpMethod.Get, "/api/v1/dispatches/1"),
            Triple("dispatch", HttpMethod.Delete, "/api/v1/dispatches/1"),
            Triple("continuation", HttpMethod.Get, "/api/v1/continuations"),
            Triple("continuation", HttpMethod.Get, "/api/v1/continuations/1"),
            Triple("continuation", HttpMethod.Delete, "/api/v1/continuations/1"),
            Triple("artifacts", HttpMethod.Get, "/api/v1/artifacts?processId=1&path=cmd_0.out"),
            Triple("artifacts", HttpMethod.Get, "/api/v1/artifacts/list?processId=1"),
            Triple("agent run", HttpMethod.Post, "/api/v1/agent/echo"),
            Triple("agent files", HttpMethod.Get, "/api/v1/agents/echo/files"),
        )

    @Test
    fun `no credential gets 401 on every operator resource family`() =
        runBlocking {
            for ((family, method, path) in noCredentialProbes) {
                val response = E2e.unauthClient.request(path) { this.method = method }
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    response.status,
                    "$family family: $method $path must demand a credential",
                )
            }
        }

    @Test
    fun `a token granted on another agent gets 404 for another agent's run`() =
        runBlocking {
            val hiddenId = Awaits.awaitTerminal(HomerApi.startRun("fail")).id

            val routes =
                listOf(
                    HttpMethod.Get to "/api/v1/status/$hiddenId",
                    HttpMethod.Delete to "/api/v1/kill/$hiddenId",
                    HttpMethod.Post to "/api/v1/processes/$hiddenId/retry",
                )
            for ((method, path) in routes) {
                val response = scoped(method, path)
                assertEquals(
                    HttpStatusCode.NotFound,
                    response.status,
                    "process family: $method $path must hide another agent's run",
                )
            }

            assertDenialAnswersLikeNotFound(
                hidden = scoped(HttpMethod.Get, "/api/v1/status/$hiddenId"),
                unknown = scoped(HttpMethod.Get, "/api/v1/status/$unknownId"),
                label = "process family",
            )

            val list: ProcessList = HomerApi.decode(scoped(HttpMethod.Get, "/api/v1/status/all?agentName=fail"))
            assertFalse(
                list.processes.any { it.id == hiddenId },
                "process family: /api/v1/status/all must filter out a run of an agent the caller holds no grant on",
            )
        }

    @Test
    fun `a token granted on another agent gets 404 for another agent's question`() =
        runBlocking {
            val askerId = HomerApi.startRun("asker")
            val question =
                Awaits.awaitNotNull("OPEN question from process $askerId") {
                    HomerApi.questions(status = "OPEN", processId = askerId).questions.firstOrNull()
                }

            val answer = scoped(HttpMethod.Post, "/api/v1/questions/${question.id}/answer", """{"answer":"A"}""")
            assertEquals(
                HttpStatusCode.NotFound,
                answer.status,
                "question family: POST /api/v1/questions/{id}/answer must hide another agent's question",
            )
            val unknownAnswer =
                scoped(
                    HttpMethod.Post,
                    "/api/v1/questions/00000000-0000-0000-0000-000000000000/answer",
                    """{"answer":"A"}""",
                )
            assertDenialAnswersLikeNotFound(
                hidden = answer,
                unknown = unknownAnswer,
                label = "question family",
            )
            assertNotNull(
                HomerApi.questions(status = "OPEN", processId = askerId).questions.firstOrNull { it.id == question.id },
                "question family: a denied answer must leave the question open",
            )

            val list: QuestionsList = HomerApi.decode(scoped(HttpMethod.Get, "/api/v1/questions?processId=$askerId"))
            assertFalse(
                list.questions.any { it.id == question.id },
                "question family: /api/v1/questions must filter out a question of an agent the caller holds no grant on",
            )

            HomerApi.kill(askerId)
            Awaits.awaitStatus(askerId, setOf("KILLED"))
        }

    @Test
    fun `a token granted on another agent gets 404 for another agent's dispatch`() =
        runBlocking {
            // The probe needs a dispatch on an agent `echoScoped` holds no grant on; `dispatcher`
            // targets `echo`, so `continuation-inherit` asks the operator to dispatch to itself.
            val originId =
                HomerApi.startRun(
                    "continuation-inherit",
                    query =
                        mapOf(
                            "ticket" to "e2e-authz-${System.nanoTime()}",
                            "askDispatch" to "true",
                            "dispatchSub" to "false",
                        ),
                )
            assertEquals("FINISHED", Awaits.awaitTerminal(originId).status)

            val dispatch =
                Awaits.awaitNotNull("PENDING dispatch registered by process $originId") {
                    HomerApi.dispatches(status = "PENDING").dispatches.firstOrNull { it.originProcessId == originId }
                }
            assertEquals(
                "continuation-inherit",
                dispatch.agentName,
                "the probe must target an agent the scoped token holds no grant on",
            )

            for (method in listOf(HttpMethod.Get, HttpMethod.Delete)) {
                val response = scoped(method, "/api/v1/dispatches/${dispatch.id}")
                assertEquals(
                    HttpStatusCode.NotFound,
                    response.status,
                    "dispatch family: $method /api/v1/dispatches/{id} must hide another agent's dispatch",
                )
            }

            assertDenialAnswersLikeNotFound(
                hidden = scoped(HttpMethod.Get, "/api/v1/dispatches/${dispatch.id}"),
                unknown = scoped(HttpMethod.Get, "/api/v1/dispatches/$unknownId"),
                label = "dispatch family",
                idSwap = unknownId.toString() to dispatch.id.toString(),
            )

            val list: DispatchList = HomerApi.decode(scoped(HttpMethod.Get, "/api/v1/dispatches?agentName=continuation-inherit"))
            assertFalse(
                list.dispatches.any { it.id == dispatch.id },
                "dispatch family: /api/v1/dispatches must filter out a dispatch of an agent the caller holds no grant on",
            )

            assertEquals(
                "PENDING",
                HomerApi.dispatch(dispatch.id).status,
                "dispatch family: a denied read or cancel must leave the dispatch untouched",
            )
        }

    @Test
    fun `a token granted on another agent gets 404 for another agent's continuation`() =
        runBlocking {
            val originId = HomerApi.startRun("continuer")
            assertEquals("FINISHED", Awaits.awaitTerminal(originId).status)

            val continuation =
                Awaits.awaitNotNull("continuation registered by process $originId") {
                    HomerApi.continuations().continuations.firstOrNull { it.originProcessId == originId }
                }
            assertEquals(
                "continued",
                continuation.agentName,
                "the probe must target an agent the scoped token holds no grant on",
            )

            for (method in listOf(HttpMethod.Get, HttpMethod.Delete)) {
                val response = scoped(method, "/api/v1/continuations/${continuation.id}")
                assertEquals(
                    HttpStatusCode.NotFound,
                    response.status,
                    "continuation family: $method /api/v1/continuations/{id} must hide another agent's continuation",
                )
            }

            assertDenialAnswersLikeNotFound(
                hidden = scoped(HttpMethod.Get, "/api/v1/continuations/${continuation.id}"),
                unknown = scoped(HttpMethod.Get, "/api/v1/continuations/$unknownId"),
                label = "continuation family",
                idSwap = unknownId.toString() to continuation.id.toString(),
            )

            val list: ContinuationList = HomerApi.decode(scoped(HttpMethod.Get, "/api/v1/continuations?agentName=continued"))
            assertFalse(
                list.continuations.any { it.id == continuation.id },
                "continuation family: /api/v1/continuations must filter out a continuation of an agent the caller holds no grant on",
            )

            val admin = HomerApi.decode<Continuation>(E2e.client.get("/api/v1/continuations/${continuation.id}"))
            assertEquals(
                continuation.id,
                admin.id,
                "continuation family: a denied read or cancel must leave the continuation untouched",
            )
        }

    @Test
    fun `a token granted on another agent gets 404 for another agent's artifacts`() =
        runBlocking {
            val hiddenId = Awaits.awaitTerminal(HomerApi.startRun("artifact-writer")).id

            val routes =
                listOf(
                    "/api/v1/artifacts?processId=$hiddenId&path=hello.txt",
                    "/api/v1/artifacts/list?processId=$hiddenId",
                )
            for (path in routes) {
                val response = scoped(HttpMethod.Get, path)
                assertEquals(
                    HttpStatusCode.NotFound,
                    response.status,
                    "artifacts family: GET $path must hide another agent's artifacts",
                )
            }

            assertDenialAnswersLikeNotFound(
                hidden = scoped(HttpMethod.Get, "/api/v1/artifacts?processId=$hiddenId&path=hello.txt"),
                unknown = scoped(HttpMethod.Get, "/api/v1/artifacts?processId=$unknownId&path=hello.txt"),
                label = "artifacts family",
            )
        }

    @Test
    fun `a token granted on another agent cannot start it`() =
        runBlocking {
            val response = scoped(HttpMethod.Post, "/api/v1/agent/fail")
            assertEquals(
                HttpStatusCode.NotFound,
                response.status,
                "agent run family: POST /api/v1/agent/{name} must hide an agent the caller holds no grant on",
            )
            assertDenialAnswersLikeNotFound(
                hidden = response,
                unknown = scoped(HttpMethod.Post, "/api/v1/agent/no-such-agent"),
                label = "agent run family",
                idSwap = "no-such-agent" to "fail",
            )
        }

    @Test
    fun `a token granted on another agent gets 404 for its files, and 403 without edit on its own agent`() =
        runBlocking {
            val routes =
                listOf(
                    Triple(HttpMethod.Get, "files", null),
                    Triple(HttpMethod.Get, "files/notes.txt", null),
                    Triple(HttpMethod.Put, "files/notes.txt", """{"content":"x"}"""),
                    Triple(HttpMethod.Delete, "files/notes.txt", null),
                )
            for ((method, path, body) in routes) {
                val hidden = scoped(method, "/api/v1/agents/fail/$path", body)
                assertEquals(
                    HttpStatusCode.NotFound,
                    hidden.status,
                    "agent files family: $method /api/v1/agents/{name}/$path must hide an agent the caller holds no grant on",
                )
                assertDenialAnswersLikeNotFound(
                    hidden = hidden,
                    unknown = scoped(method, "/api/v1/agents/no-such-agent/$path", body),
                    label = "agent files family",
                    idSwap = "no-such-agent" to "fail",
                )
                assertEquals(
                    HttpStatusCode.Forbidden,
                    scoped(method, "/api/v1/agents/echo/$path", body).status,
                    "agent files family: $method /api/v1/agents/{name}/$path needs edit on an agent the caller can see",
                )
            }
        }

    /**
     * ADR-0047 rule 3: a denial must answer exactly like not-found, not merely share its status —
     * the same scoped request at an id that does not exist must return the denial's body, with the
     * two ids normalized where the message names them (as `ProcessIsolationTest` does).
     */
    private suspend fun assertDenialAnswersLikeNotFound(
        hidden: HttpResponse,
        unknown: HttpResponse,
        label: String,
        idSwap: Pair<String, String>? = null,
    ) {
        assertEquals(HttpStatusCode.NotFound, unknown.status, "$label: the not-found probe must itself be 404")
        val unknownBody = unknown.bodyAsText()
        assertEquals(
            if (idSwap == null) unknownBody else unknownBody.replace(idSwap.first, idSwap.second),
            hidden.bodyAsText(),
            "$label: a denial must be indistinguishable from not-found (ADR-0047 rule 3)",
        )
    }

    /** A request carrying the narrowly granted fixture token instead of the admin one. */
    private suspend fun scoped(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): HttpResponse =
        E2e.client.request(path) {
            this.method = method
            header(HttpHeaders.Authorization, scopedAuth)
            if (body != null) {
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
}
