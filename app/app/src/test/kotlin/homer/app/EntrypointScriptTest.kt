package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals

/**
 * The image entrypoint (`app/entrypoint.sh`) hands the JVM `JAVA_OPTS` split into its flags and
 * `CONFIG_PATH` as exactly one argument, or none when it is unset or empty -- in both the root
 * (`su-exec` drop) and the non-root branch. Stubs on PATH stand in for `id`, `stat`, `mkdir`,
 * `chown`, `su-exec` and `java`; the `java` stub prints each argument it got in brackets.
 */
class EntrypointScriptTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var bin: Path
    private lateinit var cwd: Path

    @BeforeEach
    fun stubs() {
        assumeTrue(File("/bin/sh").canExecute(), "needs /bin/sh")
        bin = dir.resolve("bin").createDirectories()
        // Files a glob-expanded `*.yaml` would turn into.
        cwd = dir.resolve("cwd").createDirectories()
        cwd.resolve("a.yaml").createFile()
        cwd.resolve("b.yaml").createFile()
        stub("id", "echo \"\$STUB_UID\"")
        stub("stat", "echo homer")
        stub("mkdir", "exit 0")
        stub("chown", "exit 0")
        stub("su-exec", "[ \"\$1\" = homer ] || { echo \"su-exec to \$1\" >&2; exit 99; }\nshift\nexec \"\$@\"")
        stub("java", "printf '[%s]' \"\$@\"")
    }

    private fun stub(
        name: String,
        body: String,
    ) {
        val file = bin.resolve(name)
        file.writeText("#!/bin/sh\n$body\n")
        file.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** The arguments `java` received, for each of the root and the non-root branch. */
    private fun javaArgs(env: Map<String, String>): Map<String, String> =
        listOf("root" to "0", "non-root" to "1000").associate { (branch, uid) ->
            val pb =
                ProcessBuilder("/bin/sh", File(repoRoot, "app/entrypoint.sh").path)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
            pb.environment().clear()
            pb.environment()["PATH"] = "$bin:/usr/bin:/bin"
            pb.environment()["STUB_UID"] = uid
            pb.environment().putAll(env)
            val process = pb.start()
            val output = process.inputStream.readBytes().decodeToString()
            check(process.waitFor(10, TimeUnit.SECONDS)) { "entrypoint.sh did not exit" }
            assertEquals(0, process.exitValue(), "$branch branch failed: $output")
            branch to output
        }

    private fun assertBothBranches(
        expected: String,
        env: Map<String, String>,
    ) = assertEquals(mapOf("root" to expected, "non-root" to expected), javaArgs(env))

    @Test
    fun `a CONFIG_PATH with a space is one argument`() =
        assertBothBranches(
            "[-XX:-UseBASE64Intrinsics][-jar][/app/homer.jar][/etc/homer conf/config.yaml]",
            mapOf("CONFIG_PATH" to "/etc/homer conf/config.yaml"),
        )

    @Test
    fun `a CONFIG_PATH with a glob character is passed literally`() =
        assertBothBranches("[-XX:-UseBASE64Intrinsics][-jar][/app/homer.jar][*.yaml]", mapOf("CONFIG_PATH" to "*.yaml"))

    @Test
    fun `a CONFIG_PATH with parameter and command substitution is passed literally`() =
        assertBothBranches(
            "[-XX:-UseBASE64Intrinsics][-jar][/app/homer.jar][\$HOME/`id`/\$(id)/config.yaml]",
            mapOf("CONFIG_PATH" to "\$HOME/`id`/\$(id)/config.yaml"),
        )

    @Test
    fun `an empty CONFIG_PATH passes no argument`() =
        assertBothBranches(
            "[-XX:-UseBASE64Intrinsics][-jar][/app/homer.jar]",
            mapOf("CONFIG_PATH" to ""),
        )

    @Test
    fun `an unset CONFIG_PATH passes no argument`() = assertBothBranches("[-XX:-UseBASE64Intrinsics][-jar][/app/homer.jar]", emptyMap())

    @Test
    fun `JAVA_OPTS is split into its flags`() =
        assertBothBranches(
            "[-XX:-UseBASE64Intrinsics][-Xmx1g][-Dfoo=bar][-jar][/app/homer.jar][config/config.json]",
            mapOf("JAVA_OPTS" to "-Xmx1g -Dfoo=bar", "CONFIG_PATH" to "config/config.json"),
        )
}
