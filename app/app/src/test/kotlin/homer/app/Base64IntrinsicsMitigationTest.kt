package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every JVM the host launch scripts start runs with `-XX:-UseBASE64Intrinsics` (W-020), the stopgap for a host JDK
 * without the Java4Shell fix; a launch that passes `$JAVA_OPTS` puts the flag before it.
 */
class Base64IntrinsicsMitigationTest {
    private val launch = Regex("""exec (?:su-exec homer )?java\b[^\n]*""")

    private fun launches(path: String): List<String> = launch.findAll(File(repoRoot, path).readText()).map { it.value }.toList()

    @Test
    fun `every JVM launch in the host scripts carries the flag before JAVA_OPTS`() {
        val expected =
            mapOf(
                "app/homer-runner.sh" to 1,
                "app/agentdebug.sh" to 1,
            )
        val all =
            expected.flatMap { (path, count) ->
                launches(path).also { assertEquals(count, it.size, "expected $count JVM launches in $path, found: $it") }
            }
        all.forEach { line ->
            assertTrue("-XX:-UseBASE64Intrinsics" in line, "JVM launch without the flag: $line")
            if ("\$JAVA_OPTS" in line) {
                assertTrue(
                    line.indexOf("-XX:-UseBASE64Intrinsics") < line.indexOf("\$JAVA_OPTS"),
                    "the flag must precede JAVA_OPTS: $line",
                )
            }
        }
    }
}
