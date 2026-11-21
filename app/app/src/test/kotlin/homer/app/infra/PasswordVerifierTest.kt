package homer.app.infra

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PasswordVerifierTest {
    private val verifier = PasswordVerifier()

    @Test
    fun `hash then verify round-trips`() {
        val hash = verifier.hash("hunter2", cost = 4)
        assertTrue(verifier.verify("hunter2", hash))
    }

    @Test
    fun `wrong password fails verification`() {
        val hash = verifier.hash("hunter2", cost = 4)
        assertFalse(verifier.verify("not-it", hash))
    }

    @Test
    fun `dummyHashAtCost produces a hash that verifies the documented plaintext`() {
        val hash = verifier.dummyHashAtCost(4)
        assertTrue(
            verifier.verify(PasswordVerifier.DUMMY_PLAINTEXT, hash),
            "dummy hash must be a valid bcrypt hash of DUMMY_PLAINTEXT",
        )
    }

    @Test
    fun `dummyHashAtCost rejects arbitrary user passwords`() {
        val hash = verifier.dummyHashAtCost(4)
        assertFalse(verifier.verify("password", hash))
        assertFalse(verifier.verify("admin", hash))
    }

    @Test
    fun `dummyHashAtCost embeds the requested cost in the bcrypt prefix`() {
        // The dummy must match the configured cost, or the unknown-user verify takes a different
        // wall-clock time than a real one.
        val prefix = Regex("""^\$2[aby]\$(\d{2})\$""")
        listOf(4, 6, 10).forEach { cost ->
            val match = prefix.find(verifier.dummyHashAtCost(cost))
            assertNotNull(match, "dummy hash at cost $cost should have a bcrypt prefix")
            assertEquals(cost, match.groupValues[1].toInt(), "dummy hash cost prefix must match requested cost")
        }
    }

    @Test
    fun `dummyHashAtCost memoizes per cost`() {
        // The same cost returns the same String reference: memoization makes the startup pre-warm pay off.
        val first = verifier.dummyHashAtCost(4)
        val second = verifier.dummyHashAtCost(4)
        assertSame(first, second, "dummyHashAtCost(cost) must be memoized")
    }
}
