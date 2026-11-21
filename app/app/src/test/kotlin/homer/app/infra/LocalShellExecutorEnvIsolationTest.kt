package homer.app.infra

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LocalShellExecutorEnvIsolationTest {
    @Test
    fun `child sees only the supplied env - nothing from parent JVM`() =
        runTest {
            // ProcessBuilder.environment() starts as System.getenv(); the executor must clear it so children
            // see only the env they were given.
            val workDir = Files.createTempDirectory("homer-shell-env-iso")
            val stdOut = workDir.resolve("out.log")
            val stdErr = workDir.resolve("err.log")

            val executor = LocalShellExecutor(workDir)
            // An absolute /usr/bin/env runs with an empty PATH, which proves the isolation.
            executor.cmd(
                command = listOf("/usr/bin/env"),
                stdOut = stdOut,
                stdErr = stdErr,
                stdIn = null,
                env =
                    mapOf(
                        "HMR_PROCESS_ID" to "1",
                        "ANTHROPIC_AUTH_TOKEN" to "sk-ant-from-operator-config",
                        "SAFE_VAR" to "ok",
                    ),
                timeout = 5.seconds,
            )

            val printed = stdOut.readText()
            val keys =
                printed
                    .lines()
                    .filter { it.isNotBlank() && it.contains("=") }
                    .map { it.substringBefore("=") }
                    .toSet()

            assertEquals(
                setOf("HMR_PROCESS_ID", "ANTHROPIC_AUTH_TOKEN", "SAFE_VAR"),
                keys,
                "Child env must contain ONLY the supplied keys; got: $keys",
            )
            assertTrue("HMR_PROCESS_ID=1" in printed)
            assertTrue("ANTHROPIC_AUTH_TOKEN=sk-ant-from-operator-config" in printed)
            assertTrue("SAFE_VAR=ok" in printed)
            // Set on most dev machines and CI, yet must not leak in through JVM inheritance.
            assertFalse(keys.contains("PATH"), "PATH must not be inherited via ProcessBuilder")
            assertFalse(keys.contains("HOME"), "HOME must not be inherited via ProcessBuilder")
            assertFalse(keys.contains("USER"), "USER must not be inherited via ProcessBuilder")
            assertFalse(keys.contains("JAVA_HOME"), "JAVA_HOME must not be inherited via ProcessBuilder")
        }

    @Test
    fun `the local runner passes env names that are not shell identifiers`() =
        runTest {
            val workDir = Files.createTempDirectory("homer-shell-env-names")
            val stdOut = workDir.resolve("out.log")
            LocalShellExecutor(workDir).cmd(
                command = listOf("/usr/bin/env"),
                stdOut = stdOut,
                stdErr = workDir.resolve("err.log"),
                stdIn = null,
                env = mapOf("spring.profiles.active" to "dev", "FOO-BAR" to "1"),
                timeout = 5.seconds,
            )
            val printed = stdOut.readText()
            assertTrue("spring.profiles.active=dev" in printed && "FOO-BAR=1" in printed, printed)
        }
}
