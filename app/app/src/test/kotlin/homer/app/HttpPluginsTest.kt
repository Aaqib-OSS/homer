package homer.app

import homer.app.executors.Process
import homer.app.models.ErrorResponse
import homer.app.models.ExecuteResponse
import homer.app.models.HeartbeatResponse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit cases of the plugins in `HttpPlugins.kt`: CORS (ADR-0016), the hardening headers, and the
 * [apiJson] shape of the basic response DTOs. `HttpPluginsWiringTest` proves `configureServer`
 * installs them.
 */
class HttpPluginsTest {
    // ========== Response Format Tests ==========

    @Test
    fun `HeartbeatResponse should have correct format`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                routing {
                    get("/test") {
                        call.respond(HeartbeatResponse())
                    }
                }
            }

            val response = client.get("/test")
            val body = response.bodyAsText()

            assertTrue(body.contains("\"status\""), "Response should contain status field")
            assertTrue(body.contains("\"ok\""), "Status should be ok")
        }

    @Test
    fun `ErrorResponse should have correct format`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                routing {
                    get("/test") {
                        call.respond(ErrorResponse(msg = "Test error message"))
                    }
                }
            }

            val response = client.get("/test")
            val body = response.bodyAsText()

            assertTrue(body.contains("\"err\""), "Response should contain err field")
            assertTrue(body.contains("\"msg\""), "Response should contain msg field")
            assertTrue(body.contains("Test error message"), "Response should contain error message")
        }

    @Test
    fun `ExecuteResponse should have correct format`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(apiJson)
                }
                routing {
                    get("/test") {
                        call.respond(ExecuteResponse(processId = 123))
                    }
                }
            }

            val response = client.get("/test")
            val body = response.bodyAsText()

            assertTrue(body.contains("\"processId\""), "Response should contain processId field")
            assertTrue(body.contains("123"), "Response should contain process ID value")
        }

    // ========== CORS Tests ==========

    @Test
    fun `CORS allows request from configured allowed origin`() =
        testApplication {
            application {
                configureCors(listOf("http://console.example.com"))
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://console.example.com")
                }
            assertEquals(
                "http://console.example.com",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
        }

    @Test
    fun `CORS rejects request from origin not in allowed list`() =
        testApplication {
            application {
                configureCors(listOf("http://console.example.com"))
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://evil.example.com")
                }
            assertEquals(null, response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `CORS uses anyHost wildcard when allowed origins list is empty`() =
        testApplication {
            application {
                configureCors(emptyList())
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            // Empty origins keep anyHost() but without credential reflection, so
            // Ktor answers `*` rather than echoing the request Origin (H3 / ADR-0016).
            val response1 =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://anything.example.com")
                }
            assertEquals("*", response1.headers[HttpHeaders.AccessControlAllowOrigin])
            val response2 =
                client.get("/test") {
                    header(HttpHeaders.Origin, "http://other.example.com")
                }
            assertEquals("*", response2.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    // ========== Security headers (audit P1 #13) ==========

    @Test
    fun `responses include hardening headers on every request`() =
        testApplication {
            application {
                configureSecurityHeaders()
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response = client.get("/test")

            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("same-origin", response.headers["Referrer-Policy"])
            assertEquals("default-src 'self'", response.headers["Content-Security-Policy"])
        }

    @Test
    fun `responses include hardening headers even on error responses`() =
        testApplication {
            application {
                configureSecurityHeaders()
                routing {
                    get("/boom") {
                        call.respond(HttpStatusCode.InternalServerError, "fail")
                    }
                }
            }
            val response = client.get("/boom")

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("DENY", response.headers["X-Frame-Options"])
            assertEquals("default-src 'self'", response.headers["Content-Security-Policy"])
        }

    @Test
    fun `CORS preflight does not break when SecurityHeaders runs after CORS completes the response`() =
        testApplication {
            // Real install order: CORS first, then SecurityHeaders. CORS completes the
            // preflight response before SecurityHeaders' onCall runs; the plugin must
            // skip header append on the already-committed response instead of throwing.
            application {
                configureCors(listOf("http://console.example.com"))
                configureSecurityHeaders()
                routing { get("/test") { call.respond(HttpStatusCode.OK) } }
            }
            val response =
                client.options("/test") {
                    header(HttpHeaders.Origin, "http://console.example.com")
                    header(HttpHeaders.AccessControlRequestMethod, "GET")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "http://console.example.com",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
            // CORS already committed the preflight response, so SecurityHeaders must
            // skip the append (in Netty the unguarded append throws and logs ERROR).
            assertNull(response.headers["X-Frame-Options"])
        }

    // ========== CORS exposed-headers Tests ==========

    @Test
    fun `CORS exposes artifact completeness headers to cross-origin clients`() =
        testApplication {
            application {
                configureCors(emptyList())
                routing {
                    get("/api/v1/artifacts") {
                        call.response.headers.append("X-Process-Status", "FINISHED")
                        call.response.headers.append("X-File-Complete", "true")
                        call.respondText("done")
                    }
                }
            }

            val response =
                client.get("/api/v1/artifacts") {
                    header(HttpHeaders.Origin, "http://localhost:3000")
                }

            // Without Access-Control-Expose-Headers the browser hides these
            // custom headers from JS, so the console reads `null` and treats
            // every finished artifact as still streaming (stuck Live badge).
            val exposed = response.headers["Access-Control-Expose-Headers"] ?: ""
            assertTrue(
                exposed.contains("X-File-Complete"),
                "Access-Control-Expose-Headers must list X-File-Complete, was: '$exposed'",
            )
            assertTrue(
                exposed.contains("X-Process-Status"),
                "Access-Control-Expose-Headers must list X-Process-Status, was: '$exposed'",
            )
        }
}
