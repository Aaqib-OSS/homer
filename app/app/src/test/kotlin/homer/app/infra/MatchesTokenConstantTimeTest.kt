package homer.app.infra

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [matchesTokenConstantTime], plus a source-grep guard that `AuthWiring.kt` still uses it.
 * The helper returns the same boolean as `==`, so behaviour cannot catch a revert and timing is too flaky.
 */
class MatchesTokenConstantTimeTest {
    private val expected = "s3cret-bearer-token-XYZ"

    @Test
    fun `mismatched bearer tokens reject at every position and length`() {
        val mismatchAtStart = "X3cret-bearer-token-XYZ"
        val mismatchInMiddle = "s3cret-Xearer-token-XYZ"
        val mismatchAtEnd = "s3cret-bearer-token-XYX"
        val mismatchDifferentLength = "s3cret-bearer-token"

        assertFalse(
            matchesTokenConstantTime(expected, mismatchAtStart),
            "Mismatch at first char must return false",
        )
        assertFalse(
            matchesTokenConstantTime(expected, mismatchInMiddle),
            "Mismatch in the middle must return false",
        )
        assertFalse(
            matchesTokenConstantTime(expected, mismatchAtEnd),
            "Mismatch at last char must return false",
        )
        assertFalse(
            matchesTokenConstantTime(expected, mismatchDifferentLength),
            "Length mismatch must return false",
        )
    }

    @Test
    fun `correct token still accepted`() {
        assertTrue(
            matchesTokenConstantTime(expected, expected),
            "Identical token strings must compare equal",
        )
    }

    @Test
    fun `empty strings compare equal and empty vs nonempty does not`() {
        assertTrue(matchesTokenConstantTime("", ""))
        assertFalse(matchesTokenConstantTime("", expected))
        assertFalse(matchesTokenConstantTime(expected, ""))
    }

    /** Fails if [matchesTokenConstantTime] leaves the production auth path or the lookup reverts to `==`. */
    @Test
    fun `production auth path uses matchesTokenConstantTime not equality`() {
        val bearerTokenKt = Paths.get("src/main/kotlin/homer/app/auth/BearerToken.kt")
        val authWiringKt = Paths.get("src/main/kotlin/homer/app/AuthWiring.kt")
        check(Files.exists(bearerTokenKt) && Files.exists(authWiringKt)) {
            "BearerToken.kt or AuthWiring.kt not found - did the file move?"
        }
        val lookup = Files.readString(bearerTokenKt)

        assertTrue(
            lookup.contains("matchesTokenConstantTime(it.value.token, credential)"),
            "BearerToken.kt must call matchesTokenConstantTime in the bearer token lookup",
        )
        assertFalse(
            lookup.contains("it.value.token == credential"),
            "BearerToken.kt must not use plain '==' for bearer token compare " +
                "(timing side-channel). Use matchesTokenConstantTime instead.",
        )
        assertTrue(
            Files.readString(authWiringKt).contains("scheme.principalFor(tokenCredential)"),
            "the auth-bearer provider must resolve its token through principalFor",
        )
        assertTrue(
            Files.readString(Paths.get("src/main/kotlin/homer/app/routes/UnsecuredRoutes.kt")).contains("scheme.principalFor(credential)"),
            "GET /auth/me must resolve its bearer token through principalFor",
        )
    }

    @Test
    fun `no production source compares a token with plain equality`() {
        val equality = Regex("""\.token\s*[!=]=|[!=]=\s*\S*\.token\b|\.token\.equals\(""")
        val offenders =
            Files.walk(Paths.get("src/main/kotlin")).use { paths ->
                paths
                    .filter { it.toString().endsWith(".kt") }
                    .toList()
                    .flatMap { file ->
                        Files.readAllLines(file).mapIndexedNotNull {
                            i,
                            line,
                            ->
                            "$file:${i + 1}: ${line.trim()}".takeIf { equality.containsMatchIn(line) }
                        }
                    }
            }
        assertEquals(emptyList(), offenders, "compare tokens with matchesTokenConstantTime, not ==/!=/equals")
    }
}
