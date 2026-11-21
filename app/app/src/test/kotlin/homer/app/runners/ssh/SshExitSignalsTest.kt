package homer.app.runners.ssh

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals("<unprintable>", SshExitSignals.quotable("A".repeat(25)))
        assertEquals("SIG@openssh.com", SshExitSignals.quotable("SIG@openssh.com"))
    }

    @Test
    fun `quotable never returns anything but the unprintable marker or a short plain name unchanged`() =
        runTest {
            checkAll<String> { name ->
                val quoted = SshExitSignals.quotable(name)
                assertTrue(
                    quoted == "<unprintable>" ||
                        (
                            quoted == name && quoted.length in 1..24 &&
                                quoted.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "_@.-" }
                        ),
                    "leaked ${quoted.length} chars",
                )
            }
        }

    @Test
    fun `quotable keeps every short alphanumeric name`() =
        runTest {
            checkAll(Arb.string(1..24, Codepoint.alphanumeric())) { name -> assertEquals(name, SshExitSignals.quotable(name)) }
        }
}
