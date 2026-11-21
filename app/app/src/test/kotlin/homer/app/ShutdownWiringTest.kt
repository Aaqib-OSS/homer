package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.RunnerRegistry
import homer.app.services.ContinuationSweepService
import homer.app.services.CronSchedulerService
import homer.app.services.DispatchSweepService
import homer.app.services.MaintenanceModeService
import homer.app.services.WorkspacePurgeService
import homer.app.services.persistence.DatabaseService
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The shutdown drain's wiring (`subscribeShutdownDrain`): `ApplicationStopping` must stop the background services,
 * drain the in-flight jobs and close the database last (ADR-0020, ADR-0066, ADR-0092). Drain timing lives in
 * `ShutdownDrainTest`; the unmocked maintenance case stays so a mock leak cannot mask the other verifications.
 */
@ExtendWith(ResetDrainingExtension::class)
class ShutdownWiringTest {
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

    /**
     * Intercepts the constructors of every service the drain stops. MockK records calls only on a constructor mock
     * that exists when the object is built, so each is touched before boot; `callOriginal()` keeps `stop()` real,
     * as a stubbed one would leave its loop running and burn the drain window.
     */
    private fun interceptServiceConstructors() {
        mockkConstructor(RunnerRegistry::class)
        every { anyConstructed<RunnerRegistry>().stop() } answers { callOriginal() }
        mockkConstructor(CronSchedulerService::class)
        every { anyConstructed<CronSchedulerService>().stop() } answers { callOriginal() }
        mockkConstructor(MaintenanceModeService::class)
        every { anyConstructed<MaintenanceModeService>().isEnabled() } returns false
        mockkConstructor(WorkspacePurgeService::class)
        every { anyConstructed<WorkspacePurgeService>().stop() } answers { callOriginal() }
        mockkConstructor(DispatchSweepService::class)
        every { anyConstructed<DispatchSweepService>().stop() } answers { callOriginal() }
        mockkConstructor(ContinuationSweepService::class)
        every { anyConstructed<ContinuationSweepService>().stop() } answers { callOriginal() }
        mockkConstructor(ActiveSessionsRegistry::class)
        every { anyConstructed<ActiveSessionsRegistry>().closeAll() } answers { callOriginal() }
        mockkConstructor(DatabaseService::class)
        every { anyConstructed<DatabaseService>().close() } answers { callOriginal() }
    }

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
    fun `the shutdown drain stops every background service, drains and closes the database last`() {
        interceptServiceConstructors()

        testApplication {
            application { configureServer(config()) }
            // Leaving the block stops the engine, which fires ApplicationStopping — the drain.
        }

        verify(exactly = 1) { anyConstructed<RunnerRegistry>().stop() }
        verify(exactly = 1) { anyConstructed<CronSchedulerService>().stop() }
        verify(exactly = 1) { anyConstructed<MaintenanceModeService>().stop() }
        verify(exactly = 1) { anyConstructed<WorkspacePurgeService>().stop() }
        verify(exactly = 1) { anyConstructed<DispatchSweepService>().stop() }
        verify(exactly = 1) { anyConstructed<ContinuationSweepService>().stop() }
        // closeAll() is the drain's observable end: it runs after the in-flight join.
        verify(exactly = 1) { anyConstructed<ActiveSessionsRegistry>().closeAll() }
        verify(exactly = 1) { anyConstructed<DatabaseService>().close() }

        verifyOrder {
            // The drain's end is `closeAll`: the claim-queue consumers keep claiming until it
            // runs, and only then are they stopped (ADR-0066, ADR-0102).
            anyConstructed<ActiveSessionsRegistry>().closeAll()
            anyConstructed<RunnerRegistry>().stop()
            // The database closes last, once in-flight writes have drained (ADR-0020).
            anyConstructed<DatabaseService>().close()
        }
    }
}
