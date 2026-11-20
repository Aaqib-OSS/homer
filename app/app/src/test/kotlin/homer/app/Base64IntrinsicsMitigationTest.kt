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
        val all =
            launches("app/entrypoint.sh") + launches("app/Dockerfile") + launches("app/homer-runner.sh") +
                launches("app/agentdebug.sh")

        // entrypoint.sh: root and non-root branch; Dockerfile: the agentdebug wrapper and homer-runner; one per host script.
        assertEquals(6, all.size, "expected six JVM launches, found: $all")
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
