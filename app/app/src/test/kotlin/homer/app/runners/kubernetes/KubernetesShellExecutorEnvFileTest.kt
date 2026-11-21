package homer.app.runners.kubernetes

import homer.app.fixtures.waitUnfinished
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The step env reaches the pod as a sourced 0600 file, never in the exec request. "Pod" execs run locally, as in
 * KubernetesWorkspaceSyncTest, except the pod-wide kill and the group signal, which are answered without running.
 */
class KubernetesShellExecutorEnvFileTest {
    @TempDir
    lateinit var tmp: Path

    private val envDir by lazy { tmp.resolve("ws/.homer/env") }
    private val started = mutableListOf<List<String>>()

    /** Runs before the command's own exec starts. */
    private var beforeCommand: (List<String>) -> Unit = {}

    /** Stands in for the command's own exec; null runs it locally. */
    private var command: ((List<String>) -> Process)? = null

    /** Whether the pod has `setsid`; its launch then runs locally without the `setsid -w` prefix. */
    private var podHasSetsid = false

    private val localPod =
        KubernetesExecClient { _, _, argv, _ ->
            synchronized(started) { started += argv }
            when {
                argv == KILL_ARGV || isSignalling(argv) -> exited(0)
                argv.first() == "setsid" && argv.size == 3 -> exited(if (podHasSetsid) 0 else 1)
                isEnvUpload(argv) || isEnvRemoval(argv) -> runLocally(argv)
                else -> commandExec(argv)
            }
        }

    private fun commandExec(argv: List<String>): Process {
        beforeCommand(argv)
        return command?.invoke(argv) ?: runLocally(if (argv.first() == "setsid") argv.drop(2) else argv)
    }

    private fun executor(api: CoreV1Api = mockk(relaxed = true)) =
        KubernetesShellExecutor(localPod, api, "homer", "pod-1", maxRunSeconds = 86_400, envDir = envDir.toString())

    @Test
    fun `no exec request carries an env value, raw or query-encoded, and the value still reaches the command`() =
        runTest {
            val result =
                executor().cmd(
                    listOf("sh", "-c", "printf '%s' \"\$STEP_SECRET\""),
                    tmp.resolve("out"),
                    tmp.resolve("err"),
                    env = mapOf("STEP_SECRET" to SECRET),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode, tmp.resolve("err").readText())
            assertEquals(SECRET, tmp.resolve("out").readText())
            assertNoEnvValueIn(started)
            val commandArgv = started.single { it.first() == "sh" && it.getOrNull(3)?.startsWith("$envDir/") == true }
            assertEquals(listOf("sh", "-c", "printf '%s' \"\$STEP_SECRET\""), commandArgv.drop(4))
            assertTrue(started.none(::isEnvRemoval), "a successful step needs no removal exec: $started")
        }

    @Test
    fun `the setsid launch records the pgid, sources the env file, and no exec request carries the value`() =
        runTest {
            podHasSetsid = true
            val workDir = Files.createDirectories(tmp.resolve("ws/workdir"))

            val result =
                executor().cmd(
                    listOf("sh", "-c", "printf '%s' \"\$STEP_SECRET\""),
                    tmp.resolve("out"),
                    tmp.resolve("err"),
                    env = mapOf("STEP_SECRET" to SECRET, "HMR_WORK_DIR" to workDir.toString()),
                    timeout = 10.seconds,
                    interruptGrace = 5.seconds,
                )

            assertEquals(0, result.exitCode, tmp.resolve("err").readText())
            assertEquals(SECRET, tmp.resolve("out").readText())
            assertNoEnvValueIn(started)
            assertTrue(started.any { it.take(2) == listOf("setsid", "-w") && it.size > 3 }, "launched under setsid: $started")
            assertTrue(
                workDir
                    .resolve("homer-exec-1.pgid")
                    .readText()
                    .trim()
                    .toInt() > 1,
                "the group leader recorded its pid",
            )
            assertEquals(emptyList(), envDir.listDirectoryEntries())
        }

    @Test
    fun `the env file is 0600 in a 0700 directory and is removed before the command runs`() =
        runTest {
            val seen = mutableListOf<String>()
            beforeCommand = {
                val files = envDir.listDirectoryEntries()
                seen += "files=${files.size}"
                seen += PosixFilePermissions.toString(files.single().getPosixFilePermissions())
                seen += PosixFilePermissions.toString(envDir.getPosixFilePermissions())
            }

            val result =
                executor().cmd(
                    listOf("ls", "-A", envDir.toString()),
                    tmp.resolve("out"),
                    tmp.resolve("err"),
                    env = mapOf("A" to "1"),
                    timeout = 10.seconds,
                )

            assertEquals(0, result.exitCode)
            assertEquals(listOf("files=1", "rw-------", "rwx------"), seen)
            assertEquals("", tmp.resolve("out").readText(), "the command saw the env file")
        }

    @Test
    fun `values with quotes, newlines and dollar signs reach the command intact`() =
        runTest {
            val env =
                linkedMapOf(
                    "QUOTES" to "it's \"double\" and 'single'",
                    "LINES" to "first\nsecond\r\nthird\n",
                    "DOLLARS" to "\$HOME \${PATH} \$(touch $tmp/pwned) `touch $tmp/pwned` \\ ; * ?",
                    "EMPTY" to "",
                    "UNICODE" to "žluťoučký kůň 😀",
                )

            executor().cmd(
                listOf("sh", "-c", "printf '%s\\0' \"\$QUOTES\" \"\$LINES\" \"\$DOLLARS\" \"\$EMPTY\" \"\$UNICODE\""),
                tmp.resolve("out"),
                tmp.resolve("err"),
                env = env,
                timeout = 10.seconds,
            )

            assertEquals(env.values.joinToString("") { "$it\u0000" }, tmp.resolve("out").readText())
            assertFalse(tmp.resolve("pwned").exists(), "a value was evaluated by the shell")
        }

    @Test
    fun `a value holding a NUL byte fails a graced step before any exec, the setsid probe included`() =
        runTest {
            podHasSetsid = true
            val e =
                assertThrows<IllegalArgumentException> {
                    executor().cmd(
                        listOf("true"),
                        tmp.resolve("out"),
                        tmp.resolve("err"),
                        env = mapOf("STEP_VALUE" to "s3cr3t-Ab9\u0000-env-value"),
                        timeout = 10.seconds,
                        interruptGrace = 5.seconds,
                    )
                }

            assertContains(e.message.orEmpty(), "'STEP_VALUE'")
            assertFalse(e.message.orEmpty().contains("s3cr3t-Ab9"))
            assertEquals(emptyList(), started)
        }

    @Test
    fun `an env name the shell cannot export fails the step before any exec, naming the key and no value`() =
        runTest {
            val e =
                assertThrows<IllegalArgumentException> {
                    executor().cmd(
                        listOf("true"),
                        tmp.resolve("out"),
                        tmp.resolve("err"),
                        env = mapOf("FOO-BAR" to SECRET),
                        timeout = 10.seconds,
                    )
                }

            assertContains(e.message.orEmpty(), "'FOO-BAR'")
            assertFalse(e.message.orEmpty().contains(SECRET))
            assertEquals(emptyList(), started)
        }

    @Test
    fun `an empty env uploads no file and runs the argv unshelled`() =
        runTest {
            executor().cmd(listOf("true"), tmp.resolve("out"), tmp.resolve("err"), timeout = 10.seconds)

            assertEquals(listOf(listOf("true")), started)
        }

    @Test
    fun `a step whose command exec fails leaves no env file in the kept workspace`() =
        runTest {
            command = { throw IllegalStateException("exec refused") }

            assertThrows<IllegalStateException> {
                executor().cmd(listOf("true"), tmp.resolve("out"), tmp.resolve("err"), env = mapOf("A" to SECRET), timeout = 10.seconds)
            }

            assertEquals(emptyList(), envDir.listDirectoryEntries())
            assertTrue(started.any(::isEnvRemoval), "the executor itself removes the file: $started")
        }

    @Test
    fun `a non-zero exit before the env file was sourced leaves no env file`() =
        runTest {
            command = { exited(2) }

            val result =
                executor(podStatusApi(podWithStatus("Running"))).cmd(
                    listOf("true"),
                    tmp.resolve("out"),
                    tmp.resolve("err"),
                    env = mapOf("A" to SECRET),
                    timeout = 10.seconds,
                )

            assertEquals(2, result.exitCode)
            assertEquals(emptyList(), envDir.listDirectoryEntries())
        }

    @Test
    fun `a timed-out step leaves no env file in the kept workspace`() =
        runTest {
            command = { neverFinishing() }

            assertThrows<CancellationException> {
                executor().cmd(listOf("sleep"), tmp.resolve("out"), tmp.resolve("err"), env = mapOf("A" to SECRET), timeout = 1.seconds)
            }

            assertEquals(emptyList(), envDir.listDirectoryEntries())
            assertTrue(KILL_ARGV in started, "the command was killed first: $started")
        }

    @Test
    fun `a failed env upload fails the step before the command, naming the directory and no value`() =
        runTest {
            val elsewhere = Files.createDirectories(tmp.resolve("elsewhere"))
            Files.createDirectories(envDir.parent)
            Files.createSymbolicLink(envDir, elsewhere)

            val e =
                assertThrows<IllegalStateException> {
                    executor().cmd(listOf("true"), tmp.resolve("out"), tmp.resolve("err"), env = mapOf("A" to SECRET), timeout = 10.seconds)
                }

            assertContains(e.message.orEmpty(), envDir.toString())
            assertFalse(e.message.orEmpty().contains(SECRET), "the error quoted the env value")
            assertTrue(started.none { it == listOf("true") }, "the command ran without its env: $started")
            assertTrue(started.any(::isEnvRemoval), "a removal is attempted: $started")
            assertEquals(emptyList(), elsewhere.listDirectoryEntries())
        }

    @Test
    fun `a graced timeout under setsid leaves no env file in the kept workspace`() =
        runTest {
            podHasSetsid = true
            command = { neverFinishing() }
            val workDir = Files.createDirectories(tmp.resolve("ws/workdir"))

            assertThrows<CancellationException> {
                executor().cmd(
                    listOf("sleep", "1000"),
                    tmp.resolve("out"),
                    tmp.resolve("err"),
                    env = mapOf("A" to SECRET, "HMR_WORK_DIR" to workDir.toString()),
                    timeout = 1.seconds,
                    interruptGrace = 1.seconds,
                )
            }

            assertTrue(started.any(::isSignalling), "the group was interrupted first: $started")
            assertTrue(started.any(::isEnvRemoval), "then the file was removed: $started")
            assertEquals(emptyList(), envDir.listDirectoryEntries())
        }

    @Test
    fun `a cancelled step leaves no env file in the kept workspace`() =
        runTest {
            val running = CountDownLatch(1)
            command = {
                running.countDown()
                neverFinishing()
            }

            val job =
                launch {
                    executor().cmd(
                        listOf("sleep"),
                        tmp.resolve("out"),
                        tmp.resolve("err"),
                        env = mapOf("A" to SECRET),
                        timeout = 60.seconds,
                    )
                }
            assertTrue(withContext(Dispatchers.IO) { running.await(10, TimeUnit.SECONDS) }, "the command never started")
            job.cancel()
            job.join()

            assertEquals(emptyList(), envDir.listDirectoryEntries())
        }

    private fun assertNoEnvValueIn(execs: List<List<String>>) {
        val encoded = URLEncoder.encode(SECRET, StandardCharsets.UTF_8)
        val leaking = execs.filter { argv -> argv.any { it.contains(SECRET) || it.contains(encoded) } }
        assertTrue(leaking.isEmpty(), "an exec request carried the env value: $leaking")
        assertTrue(execs.any(::isEnvUpload), "the env went through the upload: $execs")
    }

    private fun isEnvUpload(argv: List<String>) = argv.getOrNull(3) == KubernetesWorkspaceSync.PRIVATE_EXTRACT_NAME

    private fun isSignalling(argv: List<String>) = argv.getOrNull(3) == "homer-signal"

    private fun isEnvRemoval(argv: List<String>) = argv.take(3) == listOf("rm", "-f", "--")

    private fun runLocally(argv: List<String>): Process = ProcessBuilder(argv).start()

    private fun exited(code: Int): Process =
        mockk(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { errorStream } returns ByteArrayInputStream(ByteArray(0))
            every { outputStream } returns ByteArrayOutputStream()
            every { waitFor(any<Long>(), any<TimeUnit>()) } returns true
            every { exitValue() } returns code
        }

    private fun neverFinishing(): Process =
        mockk(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(ByteArray(0))
            every { errorStream } returns ByteArrayInputStream(ByteArray(0))
            every { outputStream } returns ByteArrayOutputStream()
            every { waitFor(any<Long>(), any<TimeUnit>()) } answers {
                waitUnfinished(firstArg(), secondArg())
                false
            }
            every { exitValue() } returns 137
            every { destroyForcibly() } returns this
        }

    private companion object {
        const val SECRET = "s3cr3t/Ab9+env=value"

        /** Never run locally: `kill -KILL -1` would kill every process of the test's user. */
        val KILL_ARGV = listOf("sh", "-c", "kill -KILL -1")
    }
}
