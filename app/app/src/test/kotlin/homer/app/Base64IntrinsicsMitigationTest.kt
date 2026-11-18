package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every JVM the images start runs with `-XX:-UseBASE64Intrinsics`, the CVE-2026-114938 (Java4Shell)
 * stopgap, placed before `$JAVA_OPTS` so an operator's `JAVA_OPTS` does not drop it.
 */
class Base64IntrinsicsMitigationTest {
    private val launch = Regex("""exec (?:su-exec homer )?java\b[^\n]*""")

    private fun launches(path: String): List<String> = launch.findAll(File(repoRoot, path).readText()).map { it.value }.toList()

    @Test
    fun `every JVM launch in the server and runner images carries the flag before JAVA_OPTS`() {
        val all = launches("app/entrypoint.sh") + launches("app/Dockerfile")

        // entrypoint.sh: root and non-root branch; Dockerfile: the agentdebug wrapper and homer-runner.
        assertEquals(4, all.size, "expected four JVM launches, found: $all")
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
