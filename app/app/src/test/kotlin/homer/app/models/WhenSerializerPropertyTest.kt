package homer.app.models

import com.charleskorn.kaml.Yaml
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * [WhenSerializer]'s wire contract over generated predicates: any bash expression round-trips through the printed
 * `when`, a scalar string is the `{all: [<string>]}` shorthand, and an object naming both or neither key is refused.
 * The YAML half covers parsing only (Homer writes JSON); control characters stay in the JSON property alone.
 */
class WhenSerializerPropertyTest {
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }
    private val yaml = Yaml.default

    /** The `{all: [...]}` / `{any: [...]}` text a user writes, without a `when` field around it. */
    @Serializable
    private data class AllText(
        val all: List<String>,
    )

    @Serializable
    private data class AnyText(
        val any: List<String>,
    )

    private fun expressionsOf(chars: List<Char>): Arb<String> =
        Arb.list(Arb.element(chars), 1..12).map { it.joinToString("") }.filter { it.isNotBlank() }

    /** A bash predicate can hold anything a shell line can. */
    private val expressions: Arb<String> = expressionsOf(JSON_CHARS)

    private val whens: Arb<When> =
        Arb.bind(
            Arb.element(When.Operator.ALL, When.Operator.ANY),
            Arb.list(expressions, 1..4),
        ) { operator, expressions -> When(operator, expressions) }

    @Test
    fun `a generated when clause survives a JSON print and parse round-trip`() {
        runTest {
            checkAll(200, whens) { clause ->
                val printed = json.encodeToString(WhenSerializer, clause)
                // Always the structured form, never the shorthand, and never both keys.
                val keys = (json.parseToJsonElement(printed) as JsonObject).keys
                assertEquals(setOf(if (clause.operator == When.Operator.ALL) "all" else "any"), keys)
                assertEquals(clause, json.decodeFromString(WhenSerializer, printed))
            }
        }
    }

    @Test
    fun `a generated when clause survives a YAML parse round-trip`() {
        runTest {
            checkAll(200, whens) { clause ->
                val text =
                    if (clause.operator == When.Operator.ALL) {
                        yaml.encodeToString(AllText.serializer(), AllText(clause.expressions))
                    } else {
                        yaml.encodeToString(AnyText.serializer(), AnyText(clause.expressions))
                    }
                assertEquals(clause, yaml.decodeFromString(WhenSerializer, text))
            }
        }
    }

    @Test
    fun `a generated scalar string is the ALL shorthand`() {
        runTest {
            checkAll(200, expressions) { expression ->
                assertEquals(
                    When(When.Operator.ALL, listOf(expression)),
                    json.decodeFromString(WhenSerializer, JsonPrimitive(expression).toString()),
                )
            }
            // A scalar whose content is a bash test: the brackets force kaml to quote it, so the
            // predicate is not read back as a YAML list, and it is never a bare typed literal.
            checkAll(200, shorthands) { expression ->
                val text = yaml.encodeToString(String.serializer(), expression)
                assertEquals(
                    When(When.Operator.ALL, listOf(expression)),
                    yaml.decodeFromString(WhenSerializer, text),
                    "shorthand '$expression' must decode as ALL with one expression",
                )
            }
        }
    }

    @Test
    fun `an object naming both keys or neither is refused`() {
        runTest {
            checkAll(100, expressions) { expression ->
                val element = JsonPrimitive(expression)
                val both =
                    buildJsonObject {
                        put("all", JsonArray(listOf(element)))
                        put("any", JsonArray(listOf(element)))
                    }
                assertThrows<SerializationException>("both keys must be refused") {
                    json.decodeFromString(WhenSerializer, both.toString())
                }
                assertThrows<SerializationException>("a keyless object must be refused") {
                    json.decodeFromString(WhenSerializer, buildJsonObject { put("other", element) }.toString())
                }
            }
        }
    }

    /** A generated scalar string wrapped in a bash test, so it is never a bare typed YAML literal. */
    private val shorthands: Arb<String> =
        Arb.list(Arb.element(YAML_CHARS), 0..12).map { "[ -n \"${it.joinToString("")}\" ]" }

    private companion object {
        val JSON_CHARS =
            listOf(
                'a',
                'Z',
                '0',
                ' ',
                '\t',
                '\n',
                '"',
                '\'',
                '\\',
                '$',
                '[',
                ']',
                '{',
                '}',
                '=',
                '!',
                '-',
                '#',
                ':',
                '/',
                '.',
                'é',
                '☃',
            )

        /** [JSON_CHARS] without the control characters kaml writes as block scalars. */
        val YAML_CHARS =
            listOf(
                'a',
                'Z',
                '0',
                ' ',
                '"',
                '\'',
                '\\',
                '$',
                '[',
                ']',
                '{',
                '}',
                '=',
                '!',
                '-',
                '#',
                ':',
                '/',
                '.',
                'é',
                '☃',
            )
    }
}
