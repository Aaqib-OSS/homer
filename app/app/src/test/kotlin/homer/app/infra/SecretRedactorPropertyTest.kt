package homer.app.infra

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [SecretRedactor.redact]'s no-leak property over generated secret values (ADR-0047 rule 7,
 * CWE-532): a declared secret must not survive in the text Homer emits to telemetry or logs —
 * neither verbatim nor in the JSON-escaped spelling the encoders write. The example cases live in
 * `SecretRedactorTest`; these drive the quoting, escaping and whitespace combinations an author
 * would not think of (root CLAUDE.md §6).
 *
 * Every value here is generated: no real credential is read, and a failure message names only
 * lengths and indexes, never the value that leaked.
 */
class SecretRedactorPropertyTest {
    /**
     * Credentials carry quotes, backslashes, control characters and non-ASCII, so the generated
     * values do too. `*` is excluded: `***` is the mask itself, so a value made of `*` would be
     * indistinguishable from an already-redacted one.
     */
    private val secretChars =
        listOf(
            'a',
            'Z',
            '0',
            '_',
            '-',
            ' ',
            '\t',
            '\n',
            '"',
            '\\',
            '\'',
            '<',
            '>',
            '&',
            '/',
            '$',
            '{',
            '}',
            ':',
            '=',
            'é',
            '☃',
        )

    private val secrets: Arb<String> = Arb.list(Arb.element(secretChars), 1..64).map { it.joinToString("") }

    /** The JSON-escaped spelling kotlinx writes for [value] inside a document, without the quotes. */
    private fun escapedForm(value: String): String = JsonPrimitive(value).toString().removeSurrounding("\"")

    @Test
    fun `no occurrence of a generated secret survives redaction`() {
        runTest {
            checkAll(200, secrets) { secret ->
                val redactor = SecretRedactor.from(mapOf(SECRET_NAME to secret), setOf(SECRET_NAME), hostEnv = { null })
                val redacted = redactor.redact("before $secret after")
                assertFalse(
                    redacted.contains(secret),
                    "a ${secret.length}-character secret survived redaction at index ${redacted.indexOf(secret)}",
                )
            }
        }
    }

    /**
     * Callers redact encoded text (`JsonElement.toString()`), where a secret's quotes, backslashes
     * and newlines appear escaped — the form `from` adds needles for. The escaped spelling must not
     * survive in a redacted document either.
     */
    @Test
    fun `no escaped occurrence of a generated secret survives redaction of a JSON document`() {
        runTest {
            checkAll(200, secrets) { secret ->
                val redactor = SecretRedactor.from(mapOf(SECRET_NAME to secret), setOf(SECRET_NAME), hostEnv = { null })
                val encoded = buildJsonObject { put("result", "before $secret after") }.toString()
                val redacted = redactor.redact(encoded)
                val escaped = escapedForm(secret)
                assertFalse(
                    redacted.contains(escaped),
                    "the ${escaped.length}-character escaped form of a ${secret.length}-character secret " +
                        "survived redaction at index ${redacted.indexOf(escaped)}",
                )
            }
        }
    }

    /**
     * The redactor's other half of the contract (see the class KDoc and SERVER.md's content-egress
     * note): a value that is *not* flagged secret is left alone. The two pools are disjoint — the
     * secret is digits, the public value letters — so the public value can never contain it.
     */
    @Test
    fun `a value that is not flagged secret survives verbatim`() {
        val digits: Arb<String> = Arb.list(Arb.element('0', '1', '2', '3', '4', '5', '6', '7', '8', '9'), 1..32).map { it.joinToString("") }
        val public: Arb<String> = Arb.list(Arb.element('a', 'Z', ' ', '-'), 1..32).map { it.joinToString("") }

        runTest {
            checkAll(100, digits, public) { secret, value ->
                val redactor =
                    SecretRedactor.from(
                        env = mapOf(SECRET_NAME to secret, PUBLIC_NAME to value),
                        secretEnvNames = setOf(SECRET_NAME),
                        hostEnv = { null },
                    )
                assertEquals(value, redactor.redact(value), "a value not flagged secret must survive verbatim")
            }
        }
    }

    private companion object {
        const val SECRET_NAME = "SECRET"
        const val PUBLIC_NAME = "PUBLIC"
    }
}
