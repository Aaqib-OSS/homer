package homer.app.runners.kubernetes

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `unhandledErrorLine`'s contract: no exec URL, one bounded line. */
class UnhandledErrorLinePropertyTest {
    private val values = Arb.string(1..40, Arb.element("0123456789/+=_".toList()).map { Codepoint(it.code) })
    private val filler = Arb.string(0..12, Codepoint.alphanumeric()).map { it.filter(Char::isLetter) }
    private val separators = Arb.element(" ", "\n", "\t ", " : ")

    @Test
    fun `the output is one line of at most 512 chars without the exec URL`() =
        runTest {
            val cases =
                Arb.bind(Arb.list(values, 1..5), Arb.list(filler, 0..6), separators, Arb.long()) { env, words, separator, seed ->
                    Case(env, words, separator, seed)
                }
            checkAll(cases) { (env, words, separator, seed) ->
                val encoded = env.map { URLEncoder.encode(it, StandardCharsets.UTF_8) }
                val url =
                    "wss://api/api/v1/namespaces/ns/pods/p/exec?stdin=false&command=env&" +
                        env.indices.joinToString("&") { "command=" + URLEncoder.encode("K$it=${env[it]}", StandardCharsets.UTF_8) } +
                        "&command=true"
                val parts = (words + env + encoded + url).shuffled(Random(seed))

                val line = unhandledErrorLine(IllegalStateException(parts.joinToString(separator)))

                assertFalse(line.contains("command="), "the exec URL must not survive: $line")
                assertTrue(line.length <= TERMINATION_MESSAGE_MAX_CHARS, "the line is bounded: ${line.length}")
                assertFalse(line.any { it == '\n' || it == '\r' }, "the output is one line: $line")
            }
        }

    private data class Case(
        val env: List<String>,
        val words: List<String>,
        val separator: String,
        val seed: Long,
    )
}
