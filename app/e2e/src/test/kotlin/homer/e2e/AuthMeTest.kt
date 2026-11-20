package homer.e2e

import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.Me
import homer.e2e.support.MeGrant
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** `GET /api/v1/auth/me` on the bearer-only fixture server: each token gets its own role and grants. */
class AuthMeTest : E2eTestBase() {
    @Test
    fun `the admin token gets its own name and the admin role`() =
        runBlocking {
            val response = E2e.client.get("/api/v1/auth/me")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(Me(username = "e2e", role = "admin"), response.body<Me>())
        }

    @Test
    fun `a scoped token gets its own grants and no role, never the token itself`() =
        runBlocking {
            val response = E2e.unauthClient.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer echo-scoped-token") }
            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse("echo-scoped-token" in response.bodyAsText())
            assertEquals(Me(username = "echoScoped", grants = listOf(MeGrant(listOf("echo"), listOf("run", "read")))), response.body<Me>())
        }

    @Test
    fun `an unknown token and no credential are both refused with 401`() =
        runBlocking {
            val unknown = E2e.unauthClient.get("/api/v1/auth/me") { header(HttpHeaders.Authorization, "Bearer not-a-token") }
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            assertEquals(HttpStatusCode.Unauthorized, E2e.unauthClient.get("/api/v1/auth/me").status)
        }
}
