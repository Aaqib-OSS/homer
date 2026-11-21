package homer.e2e

import homer.e2e.support.E2e
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import homer.e2e.support.ReloadResult
import io.ktor.client.request.post
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GET /api/v1/agents/load-errors` serves the last completed scan's errors, so the fixture agent
 * that never loads is readable here without a reload. The `401` half is `AuthBoundaryTest`'s.
 */
class AgentLoadErrorsTest : E2eTestBase() {
    @Test
    fun `an admin reads the startup scan's load error without reloading`() =
        runBlocking {
            val loadErrors = HomerApi.agentLoadErrors()

            assertEquals(0, loadErrors.truncated, "the fixture set is far under the kept cap")
            val message = loadErrors.errors.single { "load-error-sysenv" in it }
            assertTrue("'JAVA_OPTS'" in message, "the refusal must name the variable: $message")
            assertFalse("-Xmx" in message, "the refusal must not carry the variable's value: $message")
            assertFalse(
                HomerApi.agents().agents.any { it.name == "load-error-sysenv" },
                "the refused fixture must not be in the catalog",
            )
        }

    @Test
    fun `a reload replaces the startup errors with the ones it just reported`() =
        runBlocking {
            val reload: ReloadResult = HomerApi.decode(E2e.client.post("/api/v1/agents/reload"))
            val fromReload = reload.errors.single { "load-error-sysenv" in it }

            val served = HomerApi.agentLoadErrors().errors.single { "load-error-sysenv" in it }
            assertEquals(fromReload, served, "the endpoint must serve the reload's own message string")
        }
}
