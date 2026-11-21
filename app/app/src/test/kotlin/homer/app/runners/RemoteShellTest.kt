package homer.app.runners

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RemoteShellTest {
    @Test
    fun `single-quotes a plain token`() {
        assertEquals("'echo'", RemoteShell.quote("echo"))
    }

    @Test
    fun `escapes embedded single quotes`() {
        // it's -> 'it'\''s'
        assertEquals("'it'\\''s'", RemoteShell.quote("it's"))
    }

    @Test
    fun `envFileContent removes the file first, then exports each value single-quoted`() {
        val content = RemoteShell.envFileContent("/w/.homer/env/a1.env", linkedMapOf("FOO" to "it's", "BAR" to "x"))
        assertEquals(
            "rm -f -- '/w/.homer/env/a1.env' || exit 1\nexport FOO='it'\\''s' || exit 1\nexport BAR='x' || exit 1\n",
            content,
        )
    }

    @Test
    fun `envFileContent refuses a key that is not a shell identifier, naming the key and no value`() {
        for (key in listOf("A-B", "1A", "A B", "A;touch x", "", "A=B")) {
            val e =
                assertThrows<IllegalArgumentException> {
                    RemoteShell.envFileContent("/w/e.env", mapOf("OK" to "v", key to "s3cr3t-Ab9-env-value"))
                }
            assertContains(e.message.orEmpty(), "'$key'")
            assertFalse(e.message.orEmpty().contains("s3cr3t-Ab9-env-value"))
        }
    }

    @Test
    fun `envFileContent refuses a value holding a NUL byte, naming the key and no value`() {
        val e =
            assertThrows<IllegalArgumentException> {
                RemoteShell.envFileContent("/w/e.env", mapOf("OK" to "v", "STEP_VALUE" to "s3cr3t-Ab9\u0000-env-value"))
            }
        assertContains(e.message.orEmpty(), "'STEP_VALUE'")
        assertFalse(e.message.orEmpty().contains("s3cr3t-Ab9"))
    }
}
