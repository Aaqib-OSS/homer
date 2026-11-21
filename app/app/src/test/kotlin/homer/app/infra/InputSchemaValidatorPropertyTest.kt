package homer.app.infra

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * [InputSchemaValidator]'s load-time contracts over generated schemas (ADR-0040, ADR-0047 rule 8): a string schema
 * compiles once and accepts exactly the bodies whose length fits its bounds, and a ReDoS-prone `pattern` is refused at
 * load. Example cases are in `InputSchemaValidatorTest`; values are ASCII, so length is a code point count either way.
 */
class InputSchemaValidatorPropertyTest {
    private val validator = InputSchemaValidator()

    private data class Bounds(
        val min: Int,
        val max: Int,
    )

    private val bounds: Arb<Bounds> =
        Arb.bind(Arb.int(0, 12), Arb.int(0, 12)) { first, second -> Bounds(minOf(first, second), maxOf(first, second)) }

    private val values: Arb<String> = Arb.list(Arb.element(LETTERS), 0..16).map { it.joinToString("") }

    private fun schemaOf(bounds: Bounds): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("value") {
                    put("type", "string")
                    put("minLength", bounds.min)
                    put("maxLength", bounds.max)
                }
            }
            putJsonArray("required") { add(JsonPrimitive("value")) }
        }

    private fun bodyOf(value: String): JsonObject = buildJsonObject { put("value", value) }

    private fun schemaWithPattern(pattern: String): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("value") {
                    put("type", "string")
                    put("pattern", pattern)
                }
            }
        }

    @Test
    fun `a string value is accepted exactly when its length is within the schema bounds`() {
        runTest {
            checkAll(300, bounds, values) { bounds, value ->
                val errors = validator.validate(schemaOf(bounds), bodyOf(value))

                assertEquals(
                    value.length in bounds.min..bounds.max,
                    errors.isEmpty(),
                    "a ${value.length}-character value against ${bounds.min}..${bounds.max}: $errors",
                )
            }
        }
    }

    @Test
    fun `a generated well-formed schema compiles once and is cached`() {
        runTest {
            checkAll(200, bounds) { bounds ->
                val schema = schemaOf(bounds)
                assertSame(
                    validator.precompile(schema),
                    validator.precompile(schema),
                    "a schema must be compiled once and cached by its canonical text",
                )
            }
        }
    }

    /** `(X+)+`, `(X*)*`, `(X+)*`, `(X*)+` — the nested-unbounded-quantifier shapes the lint names. */
    private val nestedQuantifiers: Arb<String> =
        Arb.bind(Arb.element("a", "[a-z]", "\\d", "."), Arb.element("+", "*"), Arb.element("+", "*")) { body, inner, outer ->
            "($body$inner)$outer"
        }

    @Test
    fun `a generated ReDoS-prone pattern is refused at load`() {
        runTest {
            checkAll(100, nestedQuantifiers) { pattern ->
                assertFailsWith<IllegalArgumentException>("pattern '$pattern' must be refused at load") {
                    validator.precompile(schemaWithPattern(pattern))
                }
            }
        }
    }

    private companion object {
        val LETTERS = listOf('a', 'B', 'z', '0', '9', '_', ' ', '-')
    }
}
