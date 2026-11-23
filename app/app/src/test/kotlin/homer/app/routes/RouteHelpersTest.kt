package homer.app.routes

import homer.app.apiJson
import homer.app.buildBearerProvider
import homer.app.exceptions.QueryParamValidationException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.authorizerFor
import homer.app.models.Action
import homer.app.models.AuthConfig
import homer.app.models.Grant
import homer.app.models.TokenConfig
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parametersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RouteHelpersTest {
    private fun callWhoseReceiveThrows(failure: Throwable): ApplicationCall =
        mockk {
            coEvery { receiveNullable<Any>(any()) } throws failure
        }

    @Test
    fun `receiveOr400 lets a cancellation through instead of answering 400`() {
        val call = callWhoseReceiveThrows(CancellationException("client went away"))

        val thrown = assertFailsWith<CancellationException> { runBlocking { call.receiveOr400<String>("unused") } }

        assertEquals("client went away", thrown.message)
    }

    @Test
    fun `receiveOr400 turns any other failure into a 400 with the given message`() {
        val call = callWhoseReceiveThrows(IllegalStateException("bad body"))

        val thrown = assertFailsWith<QueryParamValidationException> { runBlocking { call.receiveOr400<String>("Invalid JSON body") } }

        assertEquals("Invalid JSON body", thrown.message)
    }

    @Test
    fun `pathParameter returns the declared parameter and names a missing one`() {
        val call = mockk<ApplicationCall> { every { parameters } returns parametersOf("id", "42") }

        assertEquals("42", call.pathParameter("id"))
        val thrown = assertFailsWith<IllegalStateException> { call.pathParameter("agentName") }
        assertEquals("route declares no {agentName} path parameter", thrown.message)
    }

    // --- the two gates: their Boolean verdict and the response it sends, pinned together ---

    private val runToken = TokenConfig(token = "run-token", grants = listOf(Grant(listOf("echo"), listOf(Action.RUN))))
    private val adminToken = TokenConfig(token = "admin-token", role = "admin")
    private val starReadToken = TokenConfig(token = "star-token", grants = listOf(Grant(listOf("*"), listOf(Action.READ))))

    /**
     * Mounts `GET /gate` behind the production bearer provider and `GET /gate-open` outside it (so the principal is
     * null); each route records [gate]'s verdict, so a denial is asserted next to the response the gate sent for it.
     */
    private fun ApplicationTestBuilder.mountGate(
        tokens: Map<String, TokenConfig>,
        authorizer: Authorizer,
        gate: suspend (ApplicationCall, Authorizer) -> Boolean,
    ): MutableList<Boolean> {
        val results = mutableListOf<Boolean>()
        application {
            install(Authentication) { buildBearerProvider(AuthConfig.Bearer(tokens = tokens)) }
            routing {
                install(ContentNegotiation) { json(apiJson) }
                get("/gate-open") { recordGate(results, authorizer, gate) }
                authenticate("auth-bearer", strategy = AuthenticationStrategy.FirstSuccessful) {
                    get("/gate") { recordGate(results, authorizer, gate) }
                }
            }
        }
        return results
    }

    /** Calls [gate]; a passing verdict answers `200`, so status and result pin the same outcome. */
    private suspend fun RoutingContext.recordGate(
        results: MutableList<Boolean>,
        authorizer: Authorizer,
        gate: suspend (ApplicationCall, Authorizer) -> Boolean,
    ) {
        val denied = gate(call, authorizer)
        results += denied
        if (!denied) call.respond(HttpStatusCode.OK)
    }

    @Test
    fun `deniedUnlessAdmin passes an admin and answers a non-admin or an unknown caller 403`() =
        testApplication {
            val tokens = mapOf("runner" to runToken, "root" to adminToken)
            val results = mountGate(tokens, authorizerFor(tokens)) { call, authorizer -> call.deniedUnlessAdmin(authorizer) }

            val admin = client.get("/gate") { header(HttpHeaders.Authorization, "Bearer admin-token") }
            val scoped = client.get("/gate") { header(HttpHeaders.Authorization, "Bearer run-token") }
            val unknown = client.get("/gate-open")

            assertEquals(HttpStatusCode.OK, admin.status, "an admin passes without the gate answering")
            assertEquals(HttpStatusCode.Forbidden, scoped.status)
            assertContains(scoped.bodyAsText(), "Admin role required")
            assertEquals(HttpStatusCode.Forbidden, unknown.status)
            assertContains(unknown.bodyAsText(), "Admin role required")
            assertEquals(listOf(false, true, true), results)
        }

    @Test
    fun `deniedUnlessServerReader passes an admin and a star reader and answers the rest 403`() =
        testApplication {
            val tokens = mapOf("runner" to runToken, "star" to starReadToken, "root" to adminToken)
            val results = mountGate(tokens, authorizerFor(tokens)) { call, authorizer -> call.deniedUnlessServerReader(authorizer) }

            val admin = client.get("/gate") { header(HttpHeaders.Authorization, "Bearer admin-token") }
            val star = client.get("/gate") { header(HttpHeaders.Authorization, "Bearer star-token") }
            val scoped = client.get("/gate") { header(HttpHeaders.Authorization, "Bearer run-token") }
            val unknown = client.get("/gate-open")

            assertEquals(HttpStatusCode.OK, admin.status, "an admin passes without the gate answering")
            assertEquals(HttpStatusCode.OK, star.status, "a read grant on * passes without the gate answering")
            assertEquals(HttpStatusCode.Forbidden, scoped.status)
            assertContains(scoped.bodyAsText(), "Read grant on * required")
            assertEquals(HttpStatusCode.Forbidden, unknown.status)
            assertContains(unknown.bodyAsText(), "Read grant on * required")
            assertEquals(listOf(false, false, true, true), results)
        }

    @Test
    fun `deniedUnlessServerReader passes an unsecured app`() =
        testApplication {
            val results =
                mountGate(emptyMap(), Authorizer(emptyMap(), emptyMap(), enabled = false)) { call, authorizer ->
                    call.deniedUnlessServerReader(authorizer)
                }

            val open = client.get("/gate-open")

            assertEquals(HttpStatusCode.OK, open.status, "unsecured mode passes without the gate answering")
            assertEquals(listOf(false), results)
        }

    // --- visibleAgentNames: the loaded agents a principal's READ grants match (ADR-0037) ---

    private val loadedAgents: Map<String, homer.app.models.Agent> =
        mapOf(
            "deploy" to AgentFixtures.simpleAgent(name = "deploy"),
            "other" to AgentFixtures.simpleAgent(name = "other"),
        )

    private fun agentsServiceWith(agents: Map<String, homer.app.models.Agent>): AgentsService =
        mockk<AgentsService>(relaxed = true) { every { this@mockk.agents } returns agents }

    @Test
    fun `visibleAgentNames keeps only the loaded names a read grant pattern matches`() {
        // "dep*" is a pattern, not a name: it keeps the loaded "deploy" and leaves "other" out, and a
        // RUN-only grant contributes nothing to a READ filter.
        val tokens =
            mapOf(
                "reader" to
                    TokenConfig(
                        token = "read-token",
                        grants = listOf(Grant(listOf("dep*"), listOf(Action.READ)), Grant(listOf("other"), listOf(Action.RUN))),
                    ),
            )

        val visible = visibleAgentNames(authorizerFor(tokens), agentsServiceWith(loadedAgents), "reader")

        assertEquals(setOf("deploy"), visible)
    }

    @Test
    fun `visibleAgentNames is null for an admin and empty for a principal without a read grant`() {
        val tokens = mapOf("root" to adminToken, "runner" to runToken)
        val authorizer = authorizerFor(tokens)
        val agentsService = agentsServiceWith(loadedAgents)

        assertNull(visibleAgentNames(authorizer, agentsService, "root"), "an admin reads unfiltered")
        assertEquals(
            emptySet<String>(),
            visibleAgentNames(authorizer, agentsService, "runner"),
            "a RUN-only principal sees nothing",
        )
        assertEquals(
            emptySet<String>(),
            visibleAgentNames(authorizer, agentsService, "nobody"),
            "an unknown principal fails closed",
        )
    }
}
