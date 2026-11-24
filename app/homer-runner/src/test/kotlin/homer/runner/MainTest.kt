package homer.runner

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import homer.app.infra.Dumpability
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import ch.qos.logback.classic.Logger as LogbackLogger

class MainTest {
    // Homer rejects a registration whose name does not match this, so the default name —
    // which the operator never typed — must be shaped locally rather than refused remotely.
    private val serverPattern = Regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}$")

    @Test
    fun `sanitizeInstanceName keeps a hostname the server already accepts`() {
        assertEquals("host-1.example.com", sanitizeInstanceName("host-1.example.com"))
    }

    @Test
    fun `sanitizeInstanceName replaces disallowed characters, drops a leading non-alphanumeric and truncates`() {
        assertEquals("build-box-1", sanitizeInstanceName("build box/1"))
        assertEquals("box", sanitizeInstanceName("--box"))
        assertEquals("a".repeat(64), sanitizeInstanceName("a".repeat(100)))
        assertEquals("homer-runner", sanitizeInstanceName("---"))
        assertEquals("homer-runner", sanitizeInstanceName(""))
    }

    @Test
    fun `every sanitized name matches the server pattern`() {
        listOf("host name", "\u00dcn\u00efcode-box", "--", "", "9lives", "x".repeat(200), "a\nb")
            .map(::sanitizeInstanceName)
            .forEach { assertTrue(serverPattern.matches(it), "'$it' must satisfy the server pattern") }
    }

    @Test
    fun `hostName is always a name the server accepts`() {
        assertTrue(serverPattern.matches(hostName()))
    }

    @Test
    fun `a plaintext non-loopback url exits 2 before anything is sent`() {
        val result = HomerRunnerCli().test("--url http://homer.internal", envvars = mapOf("HOMER_RUNNER_TOKEN" to "t".repeat(32)))
        assertEquals(2, result.statusCode)
        assertTrue("https://" in result.stderr, "got: ${result.stderr}")
    }

    @Test
    fun `the token is never accepted as a flag, only from HOMER_RUNNER_TOKEN (CWE-522)`() {
        val flag = HomerRunnerCli().test("--url https://homer.internal --token secret-token-value")
        assertEquals(1, flag.statusCode)
        assertTrue("--token" in flag.stderr, "got: ${flag.stderr}")
        assertTrue("secret-token-value" !in flag.stderr)

        val missing = HomerRunnerCli().test("--url https://homer.internal")
        assertEquals(1, missing.statusCode)
        assertTrue("HOMER_RUNNER_TOKEN" in missing.stderr, "got: ${missing.stderr}")
    }

    @Test
    fun `a failed hardening refuses to start with its reason and without the token (CWE-526)`() {
        val sentinel = "runner-token-sentinel"
        val refusal =
            assertFailsWith<CliktError> {
                runnerMain(
                    arrayOf("--url", "https://homer.internal"),
                    harden = { Dumpability.Result.Failed("prctl(PR_SET_DUMPABLE, 0) returned -1") },
                    execute = { _, _ -> fail("a refused start must never build or run the command") },
                )
            }
        assertEquals(
            "homer-runner refuses to start: prctl(PR_SET_DUMPABLE, 0) returned -1",
            refusal.message,
            "HOMER_RUNNER_TOKEN holds $sentinel: the refusal must not carry it",
        )
    }

    @Test
    fun `a failed hardening refuses --help and a usage error before the parse (CWE-526)`() {
        for (argv in listOf(arrayOf("--help"), arrayOf("--no-such-option"))) {
            val refusal =
                assertFailsWith<CliktError> {
                    runnerMain(
                        argv,
                        harden = { Dumpability.Result.Failed("prctl(PR_SET_DUMPABLE, 0) returned -1") },
                        execute = { _, _ -> fail("the command must never be built or run on a failed hardening") },
                    )
                }
            assertTrue(
                "refuses to start" in refusal.message!!,
                "a refused start must refuse ${argv.contentToString()} too, got: ${refusal.message}",
            )
        }
    }

    @Test
    fun `hardening runs before the first environment variable is read`() {
        val events = readsWhileHardening(Dumpability.Result.Hardened)
        assertEquals("harden", events.first(), "got: $events")
        assertTrue(events.indexOf("HOMER_RUNNER_TOKEN") > 0, "the token read must follow the hardening; got: $events")
    }

    @Test
    fun `a failed hardening through the real main exits non-zero with a one-line refusal and without the token (CWE-526)`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val sentinel = "runner-token-sentinel"
        val handle = ProcessHandle.current()
        val java = handle.info().command().orElseThrow()
        // `--illegal-native-access=deny` makes the forked JVM's own prctl call fail, so the refusal
        // travels the real `fun main` — the path the in-process seams above never reach.
        val launch =
            ProcessBuilder(
                listOf(
                    java,
                    "--illegal-native-access=deny",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "homer.runner.MainKt",
                    "--url",
                    "http://127.0.0.1:1",
                ),
            )
        launch.environment()["HOMER_RUNNER_TOKEN"] = sentinel
        launch.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val child = launch.start()
        val exited = child.waitFor(60, TimeUnit.SECONDS)
        val stderr = child.errorStream.readAllBytes().decodeToString()
        child.destroy()
        assertTrue(exited, "a refused start must exit on its own")
        assertTrue(child.exitValue() != 0, "a refused start must exit non-zero")
        assertTrue("refuses to start" in stderr, "got: $stderr")
        assertTrue(sentinel !in stderr, "the refusal must not carry the token; got: $stderr")
        assertTrue("Exception" !in stderr, "the refusal must be one line, not a stack trace; got: $stderr")
    }

    @Test
    fun `the entry point's error printer prints one line without a stack trace and exits with the status code`() {
        val err = ByteArrayOutputStream()
        val old = System.err
        System.setErr(PrintStream(err))
        try {
            val exit: (Int) -> Nothing = { status -> fail("exit $status") }
            printCliktError(
                CliktError("homer-runner refuses to start: prctl returned -1", statusCode = 3),
                exit = exit,
            )
            fail("the printer must not return")
        } catch (e: AssertionError) {
            assertEquals("exit 3", e.message)
            assertTrue(
                "error: homer-runner refuses to start: prctl returned -1" in err.toString(),
                "got: ${err.toString().replace('\n', ' ')}",
            )
            assertFalse("Exception" in err.toString(), "got: ${err.toString().replace('\n', ' ')}")
        } finally {
            System.setErr(old)
        }
    }

    @Test
    fun `a failed hardening never reads the token`() {
        val events = readsWhileHardening(Dumpability.Result.Failed("prctl(PR_SET_DUMPABLE, 0) returned -1"))
        assertEquals(listOf("harden"), events, "a refused start must not read the token; got: $events")
    }

    /** What the entry point reads, in order, when hardening answers [hardening]: `harden`, then each env var name. */
    private fun readsWhileHardening(hardening: Dumpability.Result): List<String> {
        val events = mutableListOf<String>()
        val argv = arrayOf("--url", "https://homer.internal")
        val record: () -> Dumpability.Result = {
            events += "harden"
            hardening
        }
        val execute: (HomerRunnerCli, Array<String>) -> Unit = { cli, args ->
            cli.configureContext {
                readEnvvar = { name ->
                    events += name
                    null
                }
            }
            assertFailsWith<CliktError> { cli.parse(args) }
        }
        // A failed hardening refuses before the command exists; anything else parses and reads its env.
        if (hardening is Dumpability.Result.Failed) {
            assertFailsWith<CliktError> { runnerMain(argv, harden = record, execute = execute) }
        } else {
            runnerMain(argv, harden = record, execute = execute)
        }
        return events
    }

    /** Runs the entry point the way `main` does; a null [harden] leaves the entry point's own in place. */
    private fun runnerMainResult(
        argv: Array<String> = arrayOf("--url", "https://homer.internal"),
        harden: (() -> Dumpability.Result)? = null,
        envvars: Map<String, String> = emptyMap(),
    ): CliktCommandTestResult {
        lateinit var result: CliktCommandTestResult
        val execute = { cli: HomerRunnerCli, args: Array<String> ->
            result = cli.test(args.joinToString(" "), envvars = envvars)
        }
        if (harden == null) {
            runnerMain(argv, execute = execute)
        } else {
            runnerMain(argv, harden = harden, execute = execute)
        }
        return result
    }

    @Test
    fun `--help prints help and exits 0 after the hardening ran`() {
        val events = mutableListOf<String>()
        val result =
            runnerMainResult(
                argv = arrayOf("--help"),
                harden = {
                    events += "harden"
                    Dumpability.Result.Hardened
                },
            )
        assertEquals(0, result.statusCode, "stderr: ${result.stderr}")
        assertEquals("harden", events.first(), "the hardening must run even when only help is asked for")
    }

    @Test
    fun `a command built without the entry point still refuses a failed hardening at run`() {
        val result =
            HomerRunnerCli(harden = { Dumpability.Result.Failed("prctl(PR_SET_DUMPABLE, 0) returned -1") })
                .test("--url https://homer.internal", envvars = mapOf("HOMER_RUNNER_TOKEN" to "t".repeat(32)))
        assertEquals(1, result.statusCode, "stderr: ${result.stderr}")
        assertTrue("refuses to start" in result.stderr, "got: ${result.stderr}")
    }

    @Test
    fun `a non-Linux host warns and continues to the token check`() {
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logback = LoggerFactory.getLogger("homer.runner") as LogbackLogger
        logback.addAppender(appender)
        try {
            val result =
                runnerMainResult(
                    harden = { Dumpability.Result.Skipped("prctl(PR_SET_DUMPABLE, 0) is Linux-only; running on Mac OS X") },
                )
            assertEquals(1, result.statusCode)
            assertTrue("HOMER_RUNNER_TOKEN" in result.stderr, "a skipped hardening must not stop the runner; got: ${result.stderr}")
            assertTrue(
                appender.list.any { "left dumpable" in it.formattedMessage },
                "got: ${appender.list.map { it.formattedMessage }}",
            )
        } finally {
            logback.detachAppender(appender)
        }
    }

    @Test
    fun `on Linux the entry point hardens the JVM even when the run is refused`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        // The plaintext URL is refused with exit 2 before any network call, so the run stops there
        // without registering; no `harden` is passed, so the entry point's own default hardens the JVM.
        val result =
            runnerMainResult(
                argv = arrayOf("--url", "http://homer.internal"),
                envvars = mapOf("HOMER_RUNNER_TOKEN" to "t".repeat(32)),
            )
        assertEquals(2, result.statusCode)
        assertEquals(0, Dumpability.dumpableState(), "the entry point must leave the JVM non-dumpable")
    }

    /** A loopback Homer answering `register` with [registerStatus]; claims find nothing. */
    private class FakeHomer(
        registerStatus: Int,
    ) : AutoCloseable {
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val claimed = CountDownLatch(1)
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        private val executor = Executors.newCachedThreadPool()
        val url get() = "http://127.0.0.1:${server.address.port}"

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                requests += "${exchange.requestMethod} ${exchange.requestURI.path}"
                when {
                    exchange.requestURI.path == "/api/v1/runner/register" && registerStatus == 201 -> {
                        exchange.reply(201, """{"instanceId":"inst-1","pollSec":30,"heartbeatTimeoutSec":60}""")
                    }

                    exchange.requestURI.path == "/api/v1/runner/register" -> {
                        exchange.reply(registerStatus, """{"msg":"tags [gpu] are not in allowedTags"}""")
                    }

                    exchange.requestURI.path.endsWith("/claim") -> {
                        claimed.countDown()
                        Thread.sleep(50)
                        exchange.reply(204, null)
                    }

                    else -> {
                        exchange.reply(204, null)
                    }
                }
            }
            server.start()
        }

        private fun HttpExchange.reply(
            status: Int,
            body: String?,
        ) {
            val bytes = body?.toByteArray()
            sendResponseHeaders(status, bytes?.size?.toLong() ?: -1)
            bytes?.let { responseBody.use { out -> out.write(it) } } ?: close()
        }

        override fun close() {
            server.stop(0)
            // stop() leaves a custom executor running; its idle non-daemon threads would hold an IDE test JVM open.
            executor.shutdownNow()
        }
    }

    private fun cli(hooks: MutableList<Thread>) =
        HomerRunnerCli(harden = { Dumpability.Result.Hardened }, addShutdownHook = { hooks += it })

    private fun argv(
        homer: FakeHomer,
        root: Path,
    ) = "--url ${homer.url} --name test-box --tags gpu --root $root --drain-timeout 5"

    @Test
    fun `a registration Homer rejects exits 2 with its reason`(
        @TempDir root: Path,
    ) {
        FakeHomer(registerStatus = 422).use { homer ->
            val hooks = CopyOnWriteArrayList<Thread>()
            val result = cli(hooks).test(argv(homer, root), envvars = mapOf("HOMER_RUNNER_TOKEN" to "t".repeat(32)))

            assertEquals(2, result.statusCode)
            assertTrue("rejected the registration" in result.stderr, "got: ${result.stderr}")
            assertTrue("allowedTags" in result.stderr, "got: ${result.stderr}")
            assertEquals(listOf("homer-runner-drain"), hooks.map { it.name })
        }
    }

    @Test
    fun `an unhealthy host exits 3 with the reason and never registers`(
        @TempDir root: Path,
    ) {
        FakeHomer(registerStatus = 201).use { homer ->
            val cli =
                HomerRunnerCli(
                    addShutdownHook = {},
                    healthCheck = { throw RunnerUnhealthyException("cannot start a process: boom") },
                    harden = { Dumpability.Result.Hardened },
                )
            val result = cli.test(argv(homer, root), envvars = mapOf("HOMER_RUNNER_TOKEN" to "t".repeat(32)))
            assertEquals(3, result.statusCode)
            assertTrue("cannot start a process" in result.stderr, "got: ${result.stderr}")
            assertFalse(homer.requests.any { it.endsWith("/register") })
        }
    }

    @Test
    fun `the shutdown hook drains a registered runner, which then deregisters and exits 0`(
        @TempDir root: Path,
    ) {
        FakeHomer(registerStatus = 201).use { homer ->
            val hooks = CopyOnWriteArrayList<Thread>()
            var result: CliktCommandTestResult? = null
            val runner =
                thread {
                    result = cli(hooks).test(argv(homer, root), envvars = mapOf("HOMER_RUNNER_TOKEN" to "t".repeat(32)))
                }
            assertTrue(homer.claimed.await(10, TimeUnit.SECONDS), "the runner must start claiming; requests: ${homer.requests}")

            hooks.single().run()
            runner.join(10_000)

            val finished = assertNotNull(result, "run() must return once drained")
            assertEquals(0, finished.statusCode, "stderr: ${finished.stderr}")
            assertTrue(
                "DELETE /api/v1/runner/instances/inst-1" in homer.requests,
                "requests: ${homer.requests}; stderr: ${finished.stderr}",
            )
        }
    }
}
