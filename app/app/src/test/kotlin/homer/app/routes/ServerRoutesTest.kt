package homer.app.routes

import homer.app.apiJson
import homer.app.fixtures.ApiRouteTestBase
import homer.app.fixtures.mountApiV1Routes
import homer.app.models.AppConfig
import homer.app.models.CostByTagResponse
import homer.app.models.HealthResponse
import homer.app.models.HeartbeatResponse
import homer.app.models.MonthlyCostsResponse
import homer.app.models.RunnerConfig
import homer.app.models.RunnerSummary
import homer.app.models.ServerStatusResponse
import homer.app.models.SweepDeletedPodResponse
import homer.app.models.TotalCostsResponse
import homer.app.runners.kubernetes.SweepStatus
import homer.app.services.Authorizer
import homer.app.services.CostMeter
import homer.app.services.MaintenanceModeService
import homer.app.services.ProcessManager
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `ServerRoutes` endpoints through the real [apiV1Routes]: `GET /heartbeat`, `GET /health`,
 * the `GET /costs` family (snapshot, monthly, totals, by-tag) and `GET /status`.
 */
class ServerRoutesTest : ApiRouteTestBase() {
    // ========== GET /heartbeat Tests ==========

    @Test
    fun `GET heartbeat should return ok status`() =
        testApplication {
            mountApiV1Routes()

            val response = client.get("/api/v1/heartbeat")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", apiJson.decodeFromString<HeartbeatResponse>(response.bodyAsText()).status)
        }

    // ========== GET /health Tests ==========

    /** A real [AppConfig] declaring one kubernetes runner, aliased `k8s-dev`. */
    private fun configWithK8sRunner() =
        AppConfig(
            agentsSearchPath = "/agents",
            workspaceRootPath = "/workspaces",
            databasePath = "/homer.db",
            runners = mapOf("k8s-dev" to RunnerConfig.Kubernetes(image = "homer:latest")),
        )

    @Test
    fun `GET health returns runners and null sweep when no sweep has run`() =
        testApplication {
            mountApiV1Routes {
                every { config } returns configWithK8sRunner()
                every { sweepStatus } returns SweepStatus()
            }

            val response = client.get("/api/v1/health")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<HealthResponse>(response.bodyAsText())
            assertEquals("ok", body.status)
            assertEquals(
                listOf(RunnerSummary(alias = "local", type = "local"), RunnerSummary(alias = "k8s-dev", type = "kubernetes")),
                body.runners,
            )
            assertNull(body.sweep)
        }

    @Test
    fun `GET health exposes sweep snapshot when one has run`() =
        testApplication {
            val sweep = SweepStatus()
            sweep.set(
                SweepStatus.Snapshot(
                    ranAt = 1_700_000_000L,
                    scanned = 3,
                    deleted =
                        listOf(
                            SweepStatus.DeletedPod(
                                namespace = "homer",
                                name = "homer-99-xyz",
                                processId = 99,
                                runnerAlias = "k8s",
                            ),
                        ),
                    errors = emptyList(),
                ),
            )
            mountApiV1Routes {
                every { config } returns configWithK8sRunner()
                every { sweepStatus } returns sweep
            }

            val response = client.get("/api/v1/health")

            assertEquals(HttpStatusCode.OK, response.status)
            val snapshot = assertNotNull(apiJson.decodeFromString<HealthResponse>(response.bodyAsText()).sweep)
            assertEquals(1_700_000_000L, snapshot.ranAt)
            assertEquals(3, snapshot.scanned)
            assertEquals(
                listOf(SweepDeletedPodResponse(namespace = "homer", name = "homer-99-xyz", processId = 99, runnerAlias = "k8s")),
                snapshot.deleted,
            )
        }

    @Test
    fun `with only Pod-mode runners the health response has deletedJobs null`() =
        testApplication {
            val sweep = SweepStatus()
            sweep.set(SweepStatus.Snapshot(ranAt = 1_700_000_000L, scanned = 0, deleted = emptyList(), errors = emptyList()))
            mountApiV1Routes {
                every { config } returns configWithK8sRunner()
                every { sweepStatus } returns sweep
            }

            val body = client.get("/api/v1/health").bodyAsText()

            assertTrue(body.contains("\"deletedJobs\": null"), body)
            assertNull(assertNotNull(apiJson.decodeFromString<HealthResponse>(body).sweep).deletedJobs)
        }

    @Test
    fun `GET health exposes the maintenance flag`() =
        testApplication {
            val maintenance = mockk<MaintenanceModeService>()
            every { maintenance.isEnabled() } returns true
            mountApiV1Routes {
                every { config } returns configWithK8sRunner()
                every { sweepStatus } returns SweepStatus()
                every { maintenanceModeService } returns maintenance
            }

            val response = client.get("/api/v1/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(apiJson.decodeFromString<HealthResponse>(response.bodyAsText()).maintenance)
        }

    // ========== GET /costs Tests ==========

    /** Mounts the real routes with a [CostMeter] whose snapshot is [snap] and a real config. */
    private fun ApplicationTestBuilder.mountCostsRoutes(snap: CostMeter.Snapshot) {
        val meter = mockk<CostMeter> { coEvery { snapshot() } returns snap }
        mountApiV1Routes {
            every { costMeter } returns meter
            every { config } returns configWithTrackedTagKeys(emptyList())
        }
    }

    @Test
    fun `GET costs returns CostMeter snapshot serialized as JSON`() =
        testApplication {
            mountCostsRoutes(
                CostMeter.Snapshot(
                    globalUsdLast60s = 0.42,
                    globalCapUsdPerMin = 5.0,
                    perAgentUsdLast24h = mapOf("alpha" to 1.25, "beta" to 0.10),
                    perAgentCapUsdPerDay = mapOf("alpha" to 10.0),
                    snapshotAtSec = 1_700_000_000L,
                ),
            )

            val response = client.get("/api/v1/costs")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"globalUsdLast60s\": 0.42"))
            assertTrue(body.contains("\"globalCapUsdPerMin\": 5.0"))
            assertTrue(body.contains("\"alpha\": 1.25"))
            assertTrue(body.contains("\"beta\": 0.1"))
            assertTrue(body.contains("\"snapshotAtSec\": 1700000000"))
        }

    @Test
    fun `GET costs omits global cap when not configured`() =
        testApplication {
            mountCostsRoutes(
                CostMeter.Snapshot(
                    globalUsdLast60s = 0.0,
                    globalCapUsdPerMin = null,
                    perAgentUsdLast24h = emptyMap(),
                    perAgentCapUsdPerDay = emptyMap(),
                    snapshotAtSec = 1_700_000_001L,
                ),
            )

            val response = client.get("/api/v1/costs")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"globalUsdLast60s\": 0.0"))
            assertTrue(body.contains("\"globalCapUsdPerMin\": null"))
        }

    // ========== GET /costs/monthly and /costs/totals Tests ==========

    private fun seededCostMeter(dir: java.nio.file.Path): CostMeter {
        val db =
            homer.app.services.persistence
                .DatabaseService(dir.resolve("costs.db").toString(), org.slf4j.LoggerFactory.getLogger("t"))
        db.migrate()
        val repo =
            homer.app.services.persistence
                .CostRepository(db)
        val meter = CostMeter(repository = repo)
        kotlinx.coroutines.runBlocking {
            // One June and two July 2026 entries (UTC).
            repo.insert(
                "alpha",
                java.time.LocalDateTime
                    .of(2026, 6, 15, 12, 0)
                    .toEpochSecond(java.time.ZoneOffset.UTC),
                1.0,
            )
            repo.insert(
                "alpha",
                java.time.LocalDateTime
                    .of(2026, 7, 2, 12, 0)
                    .toEpochSecond(java.time.ZoneOffset.UTC),
                2.0,
            )
            repo.insert(
                "beta",
                java.time.LocalDateTime
                    .of(2026, 7, 3, 12, 0)
                    .toEpochSecond(java.time.ZoneOffset.UTC),
                0.5,
            )
        }
        return meter
    }

    @Test
    fun `GET costs monthly returns per-agent sums for the requested UTC month`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        val meter = seededCostMeter(dir)
        mountApiV1Routes { every { costMeter } returns meter }

        val response = client.get("/api/v1/costs/monthly?month=2026-07")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<MonthlyCostsResponse>(response.bodyAsText())
        assertEquals("2026-07", body.month)
        assertEquals(mapOf("alpha" to 2.0, "beta" to 0.5), body.perAgentUsd)
        assertEquals(2.5, body.totalUsd, 1e-9)
        assertEquals(listOf("2026-07", "2026-06"), body.availableMonths)
    }

    @Test
    fun `GET costs monthly with a malformed month returns 400 via StatusPages`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        val meter = seededCostMeter(dir)
        mountApiV1Routes { every { costMeter } returns meter }

        val response = client.get("/api/v1/costs/monthly?month=garbage")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET costs totals returns all-time per-agent sums`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        val meter = seededCostMeter(dir)
        mountApiV1Routes { every { costMeter } returns meter }

        val response = client.get("/api/v1/costs/totals")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<TotalCostsResponse>(response.bodyAsText())
        assertEquals(mapOf("alpha" to 3.0, "beta" to 0.5), body.perAgentUsd)
        assertEquals(3.5, body.totalUsd, 1e-9)
    }

    // ========== GET /costs/by-tag/{key} Tests ==========

    private fun configWithTrackedTagKeys(keys: List<String>) =
        AppConfig(
            agentsSearchPath = "/agents",
            workspaceRootPath = "/workspaces",
            databasePath = "/homer.db",
            costTrackedTagKeys = keys,
        )

    @Test
    fun `GET costs by-tag returns the per-value breakdown for a configured key`() =
        testApplication {
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery { pm.costByTag(eq("ticket_id"), any()) } returns
                ProcessManager.CostByTagResult(mapOf("T-123" to 1.5, "T-456" to 0.25), truncated = false)
            mountApiV1Routes {
                every { processManager } returns pm
                every { config } returns configWithTrackedTagKeys(listOf("ticket_id"))
            }

            val response = client.get("/api/v1/costs/by-tag/ticket_id")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<CostByTagResponse>(response.bodyAsText())
            assertEquals("ticket_id", body.key)
            assertEquals(mapOf("T-123" to 1.5, "T-456" to 0.25), body.valuesUsd)
            assertEquals(1.75, body.totalUsd, 1e-9)
            assertFalse(body.truncated)
        }

    @Test
    fun `GET costs by-tag reports truncated when the repository hit its row bound`() =
        testApplication {
            val pm = mockk<ProcessManager>(relaxed = true)
            coEvery { pm.costByTag(eq("ticket_id"), any()) } returns
                ProcessManager.CostByTagResult(mapOf("T-123" to 1.5), truncated = true)
            mountApiV1Routes {
                every { processManager } returns pm
                every { config } returns configWithTrackedTagKeys(listOf("ticket_id"))
            }

            val response = client.get("/api/v1/costs/by-tag/ticket_id")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<CostByTagResponse>(response.bodyAsText())
            assertTrue(body.truncated)
        }

    @Test
    fun `GET costs by-tag with an unconfigured key returns 400`() =
        testApplication {
            mountApiV1Routes {
                every { config } returns configWithTrackedTagKeys(emptyList())
            }

            val response = client.get("/api/v1/costs/by-tag/ticket_id")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `GET costs by-tag denies a non-admin caller with 403`() =
        testApplication {
            mountApiV1Routes {
                every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = true)
                every { config } returns configWithTrackedTagKeys(listOf("ticket_id"))
            }

            val response = client.get("/api/v1/costs/by-tag/ticket_id")

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    // ========== GET /status Tests ==========

    @Test
    fun `GET status should return server status`() =
        testApplication {
            mountWithServiceMocks()

            val response = client.get("/api/v1/status")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = apiJson.decodeFromString<ServerStatusResponse>(response.bodyAsText())
            assertEquals("1700000000", body.startTime)
            assertTrue(body.currentTime.isNotBlank(), "Response should carry the current time")
            assertEquals(5, body.lastProcessId)
        }
}
