package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.RateLimitConfig
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A runner-protocol request whose domain token does not verify is charged to one aggregate unverified-runner bucket,
 * never to the global bucket authenticated operator requests ride (ADR-0014). Each test asserts a denial path
 * (ADR-0047 rule 8): the flood is still refused and still bounded, and it takes nothing from anyone else.
 */
@ExtendWith(ResetDrainingExtension::class)
class GlobalRateLimitUnverifiedRunnerTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    private fun config(globalLimit: Int) =
        AppConfig(
            server =
                Server(
                    apiAuth = AuthConfig.Bearer(tokens = mapOf("operator" to TokenConfig(token = OPERATOR_TOKEN, role = "admin"))),
                    rateLimit = RateLimitConfig(limit = globalLimit, refillPeriod = 60),
                ),
            agentsSearchPath = agentsDir.absolutePath,
            workspaceRootPath = workspaceDir.absolutePath,
            databasePath = File(workspaceDir, "homer.db").absolutePath,
            langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
            openTelemetryExport = OpenTelemetryExport.LOG,
            runners = mapOf("workers" to RunnerConfig.External(token = WORKERS_TOKEN)),
        )

    private suspend fun ApplicationTestBuilder.register(token: String?): HttpResponse =
        client.post("/api/v1/runner/register") {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody("""{"name":"host-1","concurrency":1}""")
        }

    private suspend fun ApplicationTestBuilder.operatorHeartbeat(): HttpResponse =
        client.get("/api/v1/heartbeat") { header(HttpHeaders.Authorization, "Bearer $OPERATOR_TOKEN") }

    @Test
    fun `an invalid runner token flood is shed on its own bucket and never 429s an operator request (CWE-770, CWE-799)`() =
        testApplication {
            application { configureServer(config(globalLimit = 3)) }

            // The flood is refused by the route while its bucket lasts, then shed with 429.
            repeat(2) { assertEquals(HttpStatusCode.Unauthorized, register(null).status) }
            assertEquals(HttpStatusCode.Unauthorized, register("not-the-workers-token-0123456789ab").status)
            assertEquals(HttpStatusCode.TooManyRequests, register("not-the-workers-token-0123456789ab").status)
            assertEquals(HttpStatusCode.TooManyRequests, register(null).status, "unverified runner traffic must stay bounded")

            // The operators' global bucket is untouched: all three of its requests are served.
            repeat(3) {
                val heartbeat = operatorHeartbeat()
                assertEquals(HttpStatusCode.OK, heartbeat.status, heartbeat.bodyAsText())
            }
            // A valid domain token still registers from its own domain's bucket.
            assertEquals(HttpStatusCode.Created, register(WORKERS_TOKEN).status)
        }

    @Test
    fun `a drained global bucket leaves an unverified runner request refused 401, not let through`() =
        testApplication {
            application { configureServer(config(globalLimit = 1)) }

            assertEquals(HttpStatusCode.OK, operatorHeartbeat().status)
            assertEquals(HttpStatusCode.TooManyRequests, operatorHeartbeat().status)

            assertEquals(HttpStatusCode.Unauthorized, register("not-the-workers-token-0123456789ab").status)
        }

    @Test
    fun `no runner token or path can name a limiter key outside the bounded set`() =
        runTest {
            val domains = listOf(RunnerDomainToken("workers", WORKERS_TOKEN))
            val bounded =
                setOf(PROBE_LIMIT_KEY, LOGIN_LIMIT_KEY, GLOBAL_LIMIT_KEY, RUNNER_UNVERIFIED_LIMIT_KEY, RunnerDomainLimitKey("workers"))
            val prefixes = Arb.element("/api/v1/runner/", "/api/v1/", "/", "/api/v1/runnerx/", "/api/v1/auth/")
            val text = Arb.string(0..48, Codepoint.ascii())
            val methods = Arb.element(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put)
            checkAll(prefixes, text, text.orNull(), methods) { prefix, rest, token, method ->
                val key = globalRateLimitKey(method, prefix + rest, token, domains, loginMounted = true)
                assertTrue(key in bounded, "key $key for $prefix$rest is outside the bounded set")
                if (prefix == "/api/v1/runner/" && token != WORKERS_TOKEN) assertEquals(RUNNER_UNVERIFIED_LIMIT_KEY, key)
            }
        }

    private companion object {
        const val WORKERS_TOKEN = "workers-token-0123456789abcdef01"
        const val OPERATOR_TOKEN = "operator-token-0123456789abcdef0"
    }
}
