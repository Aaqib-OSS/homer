package homer.app.models

import homer.app.apiJson
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** Serialization format of the basic response DTOs in `ServerStatus.kt`, through [apiJson]. */
class ServerStatusTest {
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
}
