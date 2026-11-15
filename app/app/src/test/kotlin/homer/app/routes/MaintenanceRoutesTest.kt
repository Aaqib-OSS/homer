package homer.app.routes

import homer.app.apiJson
import homer.app.fixtures.ApiRouteTestBase
import homer.app.fixtures.mountApiV1Routes
import homer.app.models.MaintenanceResponse
import homer.app.models.NewRunRecord
import homer.app.models.ProcessStatus
import homer.app.services.Authorizer
import homer.app.services.MaintenanceModeService
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GET` / `POST /maintenance` through the real [apiV1Routes] (ADR-0092): the admin gate and its
 * existence-hiding denial, body validation, and that turning the mode on never stops a run in flight.
 */
class MaintenanceRoutesTest : ApiRouteTestBase() {
    // ========== GET/POST /maintenance Tests ==========

    /** The service's read model for a mode turned on at [since] with [queued] parked starts. */
    private fun maintenanceState(
        enabled: Boolean = true,
        since: Long? = 1_700_000_000L,
        queued: Int = 0,
    ) = MaintenanceResponse(enabled = enabled, since = since, queued = queued)

    @Test
    fun `GET maintenance returns the mode state to an admin, uncached`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>()
            every { maintenance.snapshot() } returns maintenanceState(queued = 3)
            mountApiV1Routes { every { maintenanceModeService } returns maintenance }

            val response = client.get("/api/v1/maintenance")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(maintenanceState(queued = 3), apiJson.decodeFromString<MaintenanceResponse>(response.bodyAsText()))
            // Live operator state: a cached copy could serve a stale "off".
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `POST maintenance turns the mode on for an admin`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            every { maintenance.snapshot() } returns maintenanceState()
            mountApiV1Routes { every { maintenanceModeService } returns maintenance }

            val response =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled": true}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 1) { maintenance.setEnabled(true) }
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `POST maintenance turns the mode off for an admin`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            every { maintenance.snapshot() } returns maintenanceState(enabled = false, since = null)
            mountApiV1Routes { every { maintenanceModeService } returns maintenance }

            val response =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled": false}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            coVerify(exactly = 1) { maintenance.setEnabled(false) }
        }

    @Test
    fun `GET and POST maintenance deny a non-admin with the same 404 as an unknown resource`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            mountApiV1Routes {
                every { maintenanceModeService } returns maintenance
                // Enabled auth with no grants: a non-admin principal (ADR-0047 rule 3).
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
            }

            val unknown = client.get("/api/v1/no-such-resource")
            val getDenied = client.get("/api/v1/maintenance")
            val postDenied =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled": true}""")
                }

            assertEquals(HttpStatusCode.NotFound, getDenied.status)
            assertEquals(unknown.bodyAsText(), getDenied.bodyAsText())
            assertEquals(HttpStatusCode.NotFound, postDenied.status)
            assertEquals(unknown.bodyAsText(), postDenied.bodyAsText())
            // The denial is total: the mode never moves.
            coVerify(exactly = 0) { maintenance.setEnabled(any()) }
        }

    @Test
    fun `POST maintenance answers 404, not 400, for a malformed body from a non-admin`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            mountApiV1Routes {
                every { maintenanceModeService } returns maintenance
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
            }

            val response =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"nonsense": 1}""")
                }

            // The body is parsed after the gate, so a denied caller cannot even probe the request shape.
            assertEquals(HttpStatusCode.NotFound, response.status)
            coVerify(exactly = 0) { maintenance.setEnabled(any()) }
        }

    @Test
    fun `POST maintenance rejects a non-boolean or unknown-key body with 400`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            mountApiV1Routes { every { maintenanceModeService } returns maintenance }

            for (body in listOf("""{"enabled": "yes"}""", """{"enabled": true, "surprise": 1}""", "not json")) {
                val response =
                    client.post("/api/v1/maintenance") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
            }
            coVerify(exactly = 0) { maintenance.setEnabled(any()) }
        }

    @Test
    fun `turning the mode on leaves a run already WORKING untouched`() =
        testApplication {
            val pm = realProcessManager()
            val id = pm.obtainProcessId(NewRunRecord("long-running-agent"))
            assertTrue(pm.compareAndSetStatus(id, setOf(ProcessStatus.CREATED), ProcessStatus.WORKING))
            val maintenance = mockk<MaintenanceModeService>(relaxed = true)
            every { maintenance.snapshot() } returns maintenanceState()
            mountApiV1Routes {
                every { processManager } returns pm
                every { maintenanceModeService } returns maintenance
            }

            val response =
                client.post("/api/v1/maintenance") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled": true}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            // Maintenance is an admission gate, not a kill switch: the run in flight keeps running.
            assertEquals(ProcessStatus.WORKING, pm.getProcessInfo(id)?.status)
        }
}
