package homer.app.auth

import homer.app.models.AuthConfig
import homer.app.models.TokenConfig
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BearerTokenTest {
    private val scheme =
        AuthConfig.Bearer(
            tokens =
                mapOf(
                    "ops" to TokenConfig(token = "ops-token", role = "admin"),
                    "ci" to TokenConfig(token = "ci-token", role = "admin"),
                ),
        )

    @Test
    fun `a Bearer header in any case yields its trimmed token`() {
        assertEquals("abc", bearerCredential("Bearer abc"))
        assertEquals("abc", bearerCredential("bearer   abc  "))
    }

    @Test
    fun `no header, another scheme and a bare token yield no credential`() {
        assertNull(bearerCredential(null))
        assertNull(bearerCredential("Basic abc"))
        assertNull(bearerCredential("abc"))
        assertNull(bearerCredential("Bearer"))
    }

    @Test
    fun `any header not starting with the Bearer scheme yields no credential`() =
        runTest {
            checkAll(Arb.string(0..40, Codepoint.printableAscii()).filter { !it.startsWith("bearer ", ignoreCase = true) }) { header ->
                assertNull(bearerCredential(header))
            }
        }

    @Test
    fun `a token round-trips through its header`() =
        runTest {
            checkAll(Arb.string(1..40, Codepoint.alphanumeric())) { token ->
                assertEquals(token, bearerCredential("Bearer $token"))
            }
        }

    @Test
    fun `principalFor names the token's own principal and nothing else`() {
        assertEquals("ops", scheme.principalFor("ops-token"))
        assertEquals("ci", scheme.principalFor("ci-token"))
        assertNull(scheme.principalFor("ci-token "))
        assertNull(scheme.principalFor(""))
    }

    @Test
    fun `a credential that is no configured token names no principal`() =
        runTest {
            checkAll(Arb.string(0..40, Codepoint.printableAscii()).filter { it != "ops-token" && it != "ci-token" }) { credential ->
                assertNull(scheme.principalFor(credential))
            }
        }
}
