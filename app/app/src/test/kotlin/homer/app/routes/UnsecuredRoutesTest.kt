package homer.app.routes

import homer.app.apiJson
import homer.app.auth.installPasswordSessions
import homer.app.configureStatusPages
import homer.app.infra.PasswordVerifier
import homer.app.models.Action
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.ErrorResponse
import homer.app.models.Grant
import homer.app.models.HmacKeyConfig
import homer.app.models.MeResponse
import homer.app.models.PasswordUserConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
import homer.app.services.Authorizer
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

/** `GET /api/v1/auth/me` answered for a bearer token, next to the session-cookie answer `PasswordAuthTest` covers. */
class UnsecuredRoutesTest {
    private val runGrant = Grant(agents = listOf("deploy-*"), actions = listOf(Action.RUN, Action.READ))

    private val bearer =
        AuthConfig.Bearer(
            tokens =
                mapOf(
                    "ops" to TokenConfig(token = ADMIN_TOKEN, role = "admin"),
                    "ci" to TokenConfig(token = CI_TOKEN, grants = listOf(runGrant)),
                ),
        )

    private val password =
        AuthConfig.Password(
            users = mapOf("bob" to PasswordUserConfig(passwordHash = PasswordVerifier().hash("pw", cost = 4), grants = listOf(runGrant))),
            sessionSecret = "me-route-session-secret-aaaaaaaaaa",
            cookieSecure = false,
            bcryptCost = 4,
        )

    private val hmac = AuthConfig.Hmac(keys = mapOf("hook" to HmacKeyConfig(secret = "hmac-secret-aaaaaaaaaaaaaaaaaaaaaa", role = "admin")))

    /** Mounts the production unsecured routes for [schemes], with the [Authorizer] the server builds from them. */
    private fun ApplicationTestBuilder.mount(vararg schemes: AuthConfig) {
        val config =
            AppConfig(
                server = Server(apiAuth = schemes.singleOrNull() ?: AuthConfig.Combined(schemes.toList())),
                agentsSearchPath = "unused",
                workspaceRootPath = "unused",
                databasePath = "unused",
            )
        val passwordScheme = schemes.filterIsInstance<AuthConfig.Password>().singleOrNull()
        val deps =
            mockk<ApiDeps>(relaxed = true) {
                every { this@mockk.config } returns config
                every { authorizer } returns Authorizer.from(config)
                every { this@mockk.passwordScheme } returns passwordScheme
                every { passwordVerifier } returns PasswordVerifier()
                every { isDraining } returns AtomicBoolean(false)
            }
        application {
            passwordScheme?.let { installPasswordSessions(it) }
            configureStatusPages()
            install(RateLimit) {
                register(RateLimitName("login")) { rateLimiter(limit = 100, refillPeriod = 60.seconds) }
            }
            routing {
                install(ContentNegotiation) { json(apiJson) }
                apiV1UnsecuredRoutes(deps)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.me(token: String?): HttpResponse =
        client.get("/api/v1/auth/me") { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    private suspend fun HttpResponse.me(): MeResponse = apiJson.decodeFromString(MeResponse.serializer(), bodyAsText())

    @Test
    fun `an admin bearer token gets its own name and the admin role`() =
        testApplication {
            mount(bearer)
            val response = me(ADMIN_TOKEN)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(MeResponse(username = "ops", role = "admin", grants = emptyList()), response.me())
        }

    @Test
    fun `a non-admin bearer token gets its own grants and no role`() =
        testApplication {
            mount(bearer)
            val response = me(CI_TOKEN)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(MeResponse(username = "ci", role = null, grants = listOf(runGrant)), response.me())
        }

    @Test
    fun `the answer never carries the token itself`() =
        testApplication {
            mount(bearer)
            val response = me(CI_TOKEN)
            assertEquals(HttpStatusCode.OK, response.status, "the token must be answered for the absence to mean anything")
            val body = response.bodyAsText()
            assertFalse(CI_TOKEN in body, body)
            assertFalse(ADMIN_TOKEN in body, body)
        }

    @Test
    fun `an unknown bearer token is refused with 401 and no body`() =
        testApplication {
            mount(bearer)
            val response = me("not-a-token")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `no credential on a bearer-only server is refused with 401 and no body`() =
        testApplication {
            mount(bearer)
            val response = me(null)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `a non-bearer Authorization header on a bearer-only server is refused with 401`() =
        testApplication {
            mount(bearer)
            val response = client.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Basic $CI_TOKEN") }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `an HMAC key is not a bearer token`() =
        testApplication {
            mount(bearer, hmac)
            assertEquals(HttpStatusCode.Unauthorized, me("hmac-secret-aaaaaaaaaaaaaaaaaaaaaa").status)
        }

    @Test
    fun `the route is not mounted when neither a bearer nor a password scheme is configured`() =
        testApplication {
            mount(hmac)
            assertEquals(HttpStatusCode.NotFound, me(null).status)
        }

    @Test
    fun `on a combined server a request with no bearer token keeps the session-cookie answer`() =
        testApplication {
            mount(bearer, password)
            val response = me(null)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                apiJson.encodeToString(ErrorResponse.serializer(), ErrorResponse(msg = "Not authenticated")),
                response.bodyAsText(),
            )
        }

    /** A client holding bob's session cookie on a combined server. */
    private suspend fun ApplicationTestBuilder.signedInAsBob(): HttpClient {
        mount(bearer, password)
        val cookieClient = createClient { install(HttpCookies) }
        val login =
            cookieClient.post("/api/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"bob","password":"pw"}""")
            }
        check(login.status == HttpStatusCode.OK) { "bob's login must succeed, got ${login.status}" }
        return cookieClient
    }

    @Test
    fun `on a combined server a bearer token answers for the token, not for the session cookie`() =
        testApplication {
            val withToken = signedInAsBob().get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN") }
            assertEquals("ops", withToken.me().username)
        }

    @Test
    fun `on a combined server a stale bearer token is refused, with no fallback to the session cookie`() =
        testApplication {
            val withStaleToken = signedInAsBob().get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer not-a-token") }
            assertEquals(HttpStatusCode.Unauthorized, withStaleToken.status)
        }

    private companion object {
        const val ADMIN_TOKEN = "admin-token-aaaaaaaaaaaaaaaaaaaaaa"
        const val CI_TOKEN = "ci-token-aaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
