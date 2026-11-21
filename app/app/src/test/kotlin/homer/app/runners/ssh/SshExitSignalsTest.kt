package homer.app.runners.ssh

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SshExitSignalsTest {
    @Test
    fun `maps each RFC 4254 name to its Linux number`() {
        val expected =
            mapOf(
                "HUP" to 1,
                "INT" to 2,
                "QUIT" to 3,
                "ILL" to 4,
                "ABRT" to 6,
                "FPE" to 8,
                "KILL" to 9,
                "USR1" to 10,
                "SEGV" to 11,
                "USR2" to 12,
                "PIPE" to 13,
                "ALRM" to 14,
                "TERM" to 15,
            )
        expected.forEach { (name, number) -> assertEquals(number, SshExitSignals.number(name), name) }
    }

    @Test
    fun `a name outside the RFC set has no number`() {
        assertNull(SshExitSignals.number("BOGUS"))
        assertNull(SshExitSignals.number("SIGKILL"))
        assertNull(SshExitSignals.number("kill"))
    }

    @Test
    fun `quotable keeps a plain name and withholds anything else`() {
        assertEquals("BOGUS", SshExitSignals.quotable("BOGUS"))
        assertEquals("<unprintable>", SshExitSignals.quotable("A\nB"))
        assertEquals("<unprintable>", SshExitSignals.quotable("A".repeat(17)))
    }
}
