package homer.app.di

import homer.app.fixtures.TestFixtures
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class AppModuleTest {
    @Test
    fun `starting the graph logs whether descriptor-checked reads work`() {
        val logger = TestFixtures.mockLogger()
        val app =
            koinApplication {
                allowOverride(true)
                modules(appModule, module { factory { (_: String) -> logger } })
            }
        try {
            // The Ktor Koin plugin does this at boot; a bare koinApplication does not.
            app.createEagerInstances()

            verify(exactly = 1) { logger.info(match<String> { "Descriptor-checked file reads ready" in it }, any<String>()) }
        } finally {
            app.close()
        }
    }
}
