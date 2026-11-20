package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every JVM the images and the host launch scripts start runs with `-XX:-UseBASE64Intrinsics`, the
 * CVE-2026-114938 (Java4Shell) stopgap, placed before `$JAVA_OPTS` so an operator's `JAVA_OPTS` does not drop it.
 */
class Base64IntrinsicsMitigationTest {
    private val launch = Regex("""exec (?:su-exec homer )?java\b[^\n]*""")

    private fun launches(path: String): List<String> = launch.findAll(File(repoRoot, path).readText()).map { it.value }.toList()

    @Test
    fun `every JVM launch in the images and host scripts carries the flag before JAVA_OPTS`() {
        val expected =
            mapOf(
                // The root and the non-root branch.
                "app/entrypoint.sh" to 2,
                // The agentdebug wrapper and the homer-runner entrypoint.
                "app/Dockerfile" to 2,
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
