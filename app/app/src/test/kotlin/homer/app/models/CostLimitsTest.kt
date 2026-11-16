package homer.app.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The construction-time rules of [CostLimits]: a cap that is not strictly positive is
 * a config error, refused before any run is priced against it. Enforcement of the caps
 * themselves is `CostMeterTest`'s subject. See ADR-0035.
 */
class CostLimitsTest {
    @Test
    fun `globalUsdPerMin of 0 is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { CostLimits(globalUsdPerMin = 0.0) }
        assertTrue("globalUsdPerMin must be > 0" in (ex.message ?: ""), ex.message)
    }

    @Test
    fun `negative globalUsdPerMin is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { CostLimits(globalUsdPerMin = -0.01) }
        assertTrue("globalUsdPerMin must be > 0" in (ex.message ?: ""), ex.message)
    }

    @Test
    fun `a small positive globalUsdPerMin and an omitted one are accepted`() {
        assertEquals(0.01, CostLimits(globalUsdPerMin = 0.01).globalUsdPerMin)
        assertNull(CostLimits().globalUsdPerMin)
    }

    @Test
    fun `perAgentUsdPerDay cap of 0 is rejected naming the agent`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                CostLimits(perAgentUsdPerDay = mapOf("planner" to 0.0))
            }
        assertTrue("perAgentUsdPerDay['planner'] must be > 0" in (ex.message ?: ""), ex.message)
    }

    @Test
    fun `a small positive perAgentUsdPerDay cap is accepted`() {
        val limits = CostLimits(perAgentUsdPerDay = mapOf("planner" to 0.01))
        assertEquals(mapOf("planner" to 0.01), limits.perAgentUsdPerDay)
    }
}
