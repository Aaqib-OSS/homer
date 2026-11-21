package homer.app

import homer.app.fixtures.DockerfileStages
import homer.app.fixtures.repoRoot
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * `app/require-java4shell-fix.sh` fails an image build whose JRE predates 25.0.5.1, the first 25 build with the
 * Java4Shell fix, and every stage that ships the Temurin JRE runs it (W-020).
 */
class Java4ShellGuardTest {
    @TempDir
    lateinit var bin: Path

    private val guard = "/tmp/require-java4shell-fix.sh"

    /** The guard's exit status against a stub `java` whose settings dump prints [versionLine]. */
    private fun guardExit(versionLine: String): Int {
        val java = bin.resolve("java")
        java.writeText("#!/bin/sh\nprintf '%s\\n' 'Property settings:' '$versionLine' 'openjdk version' >&2\n")
        java.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        val pb = ProcessBuilder("/bin/sh", File(repoRoot, "app/require-java4shell-fix.sh").path).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment()["PATH"] = "$bin:/usr/bin:/bin"
        val process = pb.start()
        process.inputStream.readBytes()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "the guard did not exit" }
        return process.exitValue()
    }

    private fun version(v: String) = "    java.version = $v"

    @Test
    fun `a JRE older than the first fixed 25 build fails the build`() {
        val refused = listOf("25.0.5", "25.0.4.9", "25.0.5.0", "24.0.9", "25", "17.0.99.99")
        assertEquals(refused.associateWith { 1 }, refused.associateWith { guardExit(version(it)) })
    }

    @Test
    fun `a JRE at or after the first fixed 25 build passes the build`() {
        val accepted = listOf("25.0.5.1", "25.0.5.2", "25.0.6", "25.0.10", "25.1", "26", "26.0.1")
        assertEquals(accepted.associateWith { 0 }, accepted.associateWith { guardExit(version(it)) })
    }

    @Test
    fun `a settings dump without a java version fails the build`() {
        assertEquals(1, guardExit("    java.vendor = Eclipse Adoptium"))
    }

    @Test
    fun `the guard passes exactly the versions numerically at or after the fix`() {
        runBlocking {
            checkAll(60, Arb.int(24..26), Arb.list(Arb.int(0..6), 0..3)) { major, rest ->
                val got = listOf(major) + rest
                val padded = got + List(4 - got.size) { 0 }
                val atLeastFix =
                    padded.zip(listOf(25, 0, 5, 1)).firstOrNull { (g, w) -> g != w }.let { it == null || it.first > it.second }
                assertEquals(if (atLeastFix) 0 else 1, guardExit(version(got.joinToString("."))), "java.version $got")
            }
        }
    }

    private fun jreStages(dockerfile: DockerfileStages): List<String> =
        dockerfile.stages.mapNotNull { it.name }.filter {
            dockerfile
                .chain(it)
                .last()
                .base
                .startsWith("eclipse-temurin:25-jre")
        }

    private fun unguarded(dockerfile: DockerfileStages): List<String> =
        jreStages(dockerfile).filterNot { name ->
            dockerfile.chain(name).flatMap { it.instructions }.any { it.startsWith("RUN ") && "sh $guard" in it }
        }

    @Test
    fun `every stage that ships the Temurin JRE runs the guard`() {
        assertEquals(listOf("slim", "runner", "homer-runner"), jreStages(DockerfileStages.app))
        assertEquals(emptyList(), unguarded(DockerfileStages.app))
    }

    @Test
    fun `a JRE stage without the guard is caught`() {
        val stripped = File(repoRoot, "app/Dockerfile").readLines().filterNot { "require-java4shell-fix.sh" in it }
        assertEquals(listOf("slim", "runner", "homer-runner"), unguarded(DockerfileStages(stripped.joinToString("\n"))))
    }
}
