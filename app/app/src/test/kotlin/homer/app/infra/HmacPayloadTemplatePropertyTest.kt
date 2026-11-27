package homer.app.infra

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pattern
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals

class HmacPayloadTemplatePropertyTest {
    private val tokens = listOf("body", "method", "path", "query", "timestamp")

    /** A template part: a token name, or literal text that cannot join a neighbour into a token. */
    private data class Part(
        val token: String?,
        val literal: String,
    )

    private val parts =
        Arb.choice(
            Arb.element(tokens).map { Part(it, "{{$it}}") },
            Arb.pattern("[a-z0-9./?=&:-]{0,8}").map { Part(null, it) },
            Arb.element("{{unknown}}", "{{other_name}}").map { Part(null, it) },
        )

    private val placeholderText = Arb.pattern("(\\{\\{(body|method|path|query|timestamp)\\}\\}|[a-z/.]){0,6}")
    private val text = Arb.choice(placeholderText, Arb.string(0..20, Codepoint.printableAscii()))
    private val bytes =
        Arb.choice(text.map { it.toByteArray(Charsets.UTF_8) }, Arb.byteArray(Arb.int(0..16), Arb.byte()))

    private val values =
        Arb.bind(bytes, text, text, text, text) { body, method, path, query, timestamp ->
            mapOf("body" to body) + tokens.drop(1).zip(listOf(method, path, query, timestamp).map { it.toByteArray(Charsets.UTF_8) })
        }

    @Test
    fun `every placeholder is replaced once by its value and a substituted value is never expanded again`() =
        runTest {
            checkAll(Arb.list(parts, 0..8), values) { template, values ->
                val expected =
                    template.fold(byteArrayOf()) { acc, part ->
                        acc + (part.token?.let { values.getValue(it) } ?: part.literal.toByteArray(Charsets.UTF_8))
                    }
                assertContentEquals(expected, expandHmacPayload(template.joinToString("") { it.literal }, values))
            }
        }
}
