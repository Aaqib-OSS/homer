package homer.app.models

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The owner-to-segment rule decides which principal's checkpoints a run can reach, so its
 * character classes are the security boundary of ADR-0046: a name accepted verbatim that a
 * second name can also produce merges two principals' state, and a name keeping a separator,
 * a leading dot or the reserved leading `_` reaches a bucket it must not. `AppConfigSerializerTest`
 * pins the named edges by example; these properties range over the classes themselves, mixing
 * valid characters with their boundary neighbours. The rule lives in a private function, so
 * every assertion goes through [AppConfig.resolveLangGraphStateDir].
 */
class OwnerStateSegmentPropertyTest {
    private val root = "/srv/checkpoints"

    // Valid characters and their boundary neighbours ('A'/'`' around a-z, ':'/'/' around 0-9),
    // the allowed punctuation, separators, a non-ASCII letter and control characters.
    private val alphabet = "azm09._-AZ`{:/\\ é\u0000\u007F".toList()

    private val owners = Arb.list(Arb.element(alphabet), 0..40).map { it.joinToString("") }

    private fun config() =
        AppConfig(
            server = Server(langGraph = LangGraphConfig(stateDir = root)),
            agentsSearchPath = "/agents",
            workspaceRootPath = "/workspaces",
            databasePath = "/var/lib/homer/homer.db",
        )

    private fun segment(
        config: AppConfig,
        owner: String,
    ): String = config.resolveLangGraphStateDir(owner).removePrefix("$root/")

    @Test
    fun `an owner is kept verbatim exactly when it is a lowercase name within the cap`() =
        runTest {
            val config = config()
            checkAll(owners) { owner ->
                val verbatim =
                    owner.length in 1..64 &&
                        owner.all { it in 'a'..'z' || it in '0'..'9' || it in "._-" } &&
                        !owner.startsWith("_") &&
                        !owner.startsWith(".")
                val resolved = segment(config, owner)
                assertEquals(
                    verbatim,
                    resolved == owner,
                    "owner codepoints: ${owner.map { it.code }} resolved to '$resolved'",
                )
            }
        }

    @Test
    fun `a non-empty owner always lands on one segment under the root, never on the unowned one`() =
        runTest {
            val config = config()
            checkAll(owners.filter { it.isNotEmpty() }) { owner ->
                val resolved = config.resolveLangGraphStateDir(owner)
                val message = "owner codepoints: ${owner.map { it.code }}"
                assertTrue(resolved.startsWith("$root/"), "$message escaped the root: $resolved")
                val resolvedSegment = segment(config, owner)
                assertEquals(1, resolvedSegment.split("/").size, "$message resolved to '$resolvedSegment'")
                assertNotEquals("_unowned", resolvedSegment, message)
                assertTrue(
                    resolvedSegment == owner || resolvedSegment.startsWith("_h-"),
                    "$message resolved to '$resolvedSegment'",
                )
            }
        }

    @Test
    fun `two distinct owners never meet on one segment`() =
        runTest {
            val config = config()
            checkAll(owners.filter { it.isNotEmpty() }, owners.filter { it.isNotEmpty() }) { one, other ->
                if (one != other) {
                    assertNotEquals(
                        segment(config, one),
                        segment(config, other),
                        "owners ${one.map { it.code }} and ${other.map { it.code }} met",
                    )
                }
            }
        }
}
