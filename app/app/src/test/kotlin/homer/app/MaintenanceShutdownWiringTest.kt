package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.runners.queue.RunnerClaimQueue
import homer.app.services.MaintenanceModeService
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Job
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * `ApplicationStopping` must call `MaintenanceModeService.stop()`, so parked starts fail with their wait-timeout error
 * instead of holding requests for the whole `queueWaitSec` and burning the drain window (ADR-0092). Koin builds the
 * service during boot, so its constructor is intercepted and the call verified on the constructed instance.
 */
class MaintenanceShutdownWiringTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    @AfterEach
    fun cleanup() {
        unmockkAll()
    }

    private fun config() =
        AppConfig(
            server = Server(apiAuth = AuthConfig.Unsecured),
            agentsSearchPath = agentsDir.absolutePath,
            workspaceRootPath = workspaceDir.absolutePath,
            databasePath = File(workspaceDir, "homer.db").absolutePath,
            langfuse =
                LangfuseConfig(
                    serverUrl = "http://localhost:9",
                    publicKey = "pk-test",
                    secretKey = "sk-test",
                ),
            openTelemetryExport = OpenTelemetryExport.LOG,
        )

    @Test
    fun `the shutdown drain stops the maintenance mode service`() {
        mockkConstructor(MaintenanceModeService::class)
        every { anyConstructed<MaintenanceModeService>().isEnabled() } returns false

        testApplication {
            application { configureServer(config()) }
            // Leaving the block stops the engine, which fires ApplicationStopping — the drain.
        }

        verify(exactly = 1) { anyConstructed<MaintenanceModeService>().stop() }
    }

    @Test
    fun `the drain joins the run jobs before the runner consumers are stopped`() {
        // Consumers must stop only after the drain, whose end is `ActiveSessionsRegistry.closeAll` (ADR-0066,
        // ADR-0102); recorded through `answers` because MockK refuses `verifyOrder` with `anyConstructed`.
        val order = mutableListOf<String>()
        mockkConstructor(MaintenanceModeService::class)
        every { anyConstructed<MaintenanceModeService>().isEnabled() } returns false
        every { anyConstructed<MaintenanceModeService>().stop() } answers { order += "maintenance-stop" }
        mockkConstructor(ActiveSessionsRegistry::class)
        every { anyConstructed<ActiveSessionsRegistry>().closeAll() } answers { order += "drain-end" }
        mockkConstructor(RunnerRegistry::class)
        every { anyConstructed<RunnerRegistry>().externalDomains } returns emptyList()
        every { anyConstructed<RunnerRegistry>().queue } returns RunnerClaimQueue(maxPending = 16)
        every { anyConstructed<RunnerRegistry>().aliases() } returns emptySet()
        every { anyConstructed<RunnerRegistry>().start(any()) } returns emptyList<Job>()
        every { anyConstructed<RunnerRegistry>().consumerJobs() } returns emptyList()
        every { anyConstructed<RunnerRegistry>().stop() } answers { order += "consumers-stop" }

        testApplication {
            application { configureServer(config()) }
            // Leaving the block stops the engine, which fires ApplicationStopping — the drain.
        }

        assertEquals(
            listOf("maintenance-stop", "drain-end", "consumers-stop"),
            order,
            "the parked starts are failed first, then the run jobs are joined, and only then are the consumers stopped",
        )
    }

    @Test
    fun `configureServer clears a drain flag left set in the JVM`() {
        // `isDraining` is JVM-global and never cleared on stop, so a second application in the JVM would start
        // draining unless configureServer clears it, which this test pins (ADR-0102).
        isDraining.set(true)
        try {
            testApplication {
                application { configureServer(config()) }
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("/api/v1/readyz").status,
                    "a freshly configured application must not report draining",
                )
            }
        } finally {
            isDraining.set(false)
        }
    }
}
