package homer.app.models

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShellEnvNamesPropertyTest {
    private val heads = ('A'..'Z') + ('a'..'z') + '_'
    private val tails = heads + ('0'..'9')
    private val identifiers: Arb<String> =
        Arb.bind(Arb.element(heads), Arb.list(Arb.element(tails), 0..10)) { head, rest -> head + rest.joinToString("") }

    /** Characters a shell identifier never holds, NUL and multi-byte ones included. */
    private val foreign = listOf('-', '.', ' ', '=', ';', '$', '\'', '"', '\n', '\u0000', 'é', '☃', '/')

    @Test
    fun `every shell identifier is accepted`() =
        runTest {
            checkAll(200, Arb.list(identifiers, 0..5)) { names -> assertNull(ShellEnvNames.firstInvalid(names)) }
        }

    @Test
    fun `a name with any foreign character, or a leading digit, is the one reported`() =
        runTest {
            val broken =
                Arb.bind(identifiers, Arb.element(foreign + ('0'..'9')), Arb.element(listOf(true, false))) { name, c, lead ->
                    if (lead) {
                        "$c$name"
                    } else if (c.isDigit()) {
                        "$c$name"
                    } else {
                        "$name$c"
                    }
                }
            checkAll(200, identifiers, broken) { ok, bad -> assertEquals(bad, ShellEnvNames.firstInvalid(listOf(ok, bad))) }
        }

    @Test
    fun `the empty name is refused`() {
        assertEquals("", ShellEnvNames.firstInvalid(listOf("A", "")))
    }
}
