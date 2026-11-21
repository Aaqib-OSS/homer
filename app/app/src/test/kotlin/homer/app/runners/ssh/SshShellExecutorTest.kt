package homer.app.runners.ssh

import homer.app.exceptions.OutputCapExceededException
import homer.app.exceptions.SshConnectionLostException
import homer.app.exceptions.SshExecRefusedException
import homer.app.exceptions.SshUnknownExitSignalException
import homer.app.fixtures.pollUntil
import homer.app.fixtures.waitUnfinished
import homer.app.infra.OutputLineFilter
import homer.app.models.CommandResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.future.OpenFuture
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.ClientSessionImpl
import org.apache.sshd.common.channel.exception.SshChannelOpenException
import org.apache.sshd.sftp.client.SftpClientFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SshShellExecutorTest {
    private lateinit var server: EmbeddedSshServer
    private lateinit var client: SshClient
    private lateinit var workdir: Path

    @BeforeEach
    fun setup() {
        server = EmbeddedSshServer().also { it.start() }
        client = SshClient.setUpDefaultClient().also { it.start() }
        workdir = server.rootDir.resolve("wd").createDirectories()
    }

    @AfterEach
    fun teardown() {
        client.stop()
        server.close()
    }

    private val envDir: Path get() = server.rootDir.resolve("p1/.homer/env")

    /** An executor over [session] whose env files land in [envDir], written over a sync on the same session. */
    private fun executorOn(
        session: ClientSession,
        maxOutputBytes: Long = Long.MAX_VALUE,
        reapProcessGroup: Boolean = false,
    ) = SshShellExecutor(
        session = session,
        remoteWorkdir = workdir.toString(),
        sync = WorkspaceSync(session, 30.seconds),
        envDir = envDir.toString(),
        maxOutputBytes = maxOutputBytes,
        reapProcessGroup = reapProcessGroup,
        reapDir = if (reapProcessGroup) workdir.resolve(".homer/reap").toString() else null,
    )

    private fun executor() = executorOn(server.newClientSession(client))

    private fun reapingExecutor() = executorOn(server.newClientSession(client), reapProcessGroup = true)

    /**
     * The embedded server runs commands on this host, so local `setsid -w` availability mirrors what remote reaping
     * needs. Absent (e.g. macOS dev), the reaping tests are skipped; SshRunner's probe would disable reaping there
     * anyway.
     */
    private fun setsidAvailable(): Boolean =
        runCatching { ProcessBuilder("setsid", "-w", "true").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun `runs a command remotely and captures stdout + exit code`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        val err = local.resolve("e.txt")
        val result =
            executor().cmd(
                command = listOf("echo", "hello world"),
                stdOut = out,
                stdErr = err,
                stdIn = null,
                env = emptyMap(),
                timeout = 10.seconds,
            )
        assertEquals(0, result.exitCode)
        assertEquals("hello world", out.readText().trim())
    }

    @Test
    fun `stdOutFilter drops remote stdout lines before they reach the file`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        val err = local.resolve("e.txt")
        val result =
            executor().cmd(
                command = listOf("printf", "keep 1\\ndrop me\\nkeep 2\\n"),
                stdOut = out,
                stdErr = err,
                stdIn = null,
                env = emptyMap(),
                timeout = 10.seconds,
                stdOutFilter = OutputLineFilter { !it.contains("drop") },
            )
        assertEquals(0, result.exitCode)
        assertEquals("keep 1\nkeep 2\n", out.readText())
    }

    @Test
    fun `pipes stdin to the remote command`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        executor().cmd(listOf("cat"), out, local.resolve("e.txt"), stdIn = "piped-input", env = emptyMap(), timeout = 10.seconds)
        assertEquals("piped-input", out.readText().trim())
    }

    @Test
    fun `propagates the remote non-zero exit code`(
        @TempDir local: Path,
    ) = runTest {
        val result =
            executor().cmd(listOf("sh", "-c", "exit 7"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 10.seconds)
        assertEquals(7, result.exitCode)
    }

    @Test
    fun `env values with quotes, newlines and dollar signs reach the command intact`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        val env = linkedMapOf("FOO" to "bar", "TRICKY" to TRICKY_VALUE)
        executor().cmd(listOf("sh", "-c", "printf '%s|%s' \"\$FOO\" \"\$TRICKY\""), out, local.resolve("e"), null, env, 10.seconds)
        assertEquals("bar|$TRICKY_VALUE", out.readText())
    }

    @Test
    fun `the exec request carries the env file path but no env value`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        executor().cmd(listOf("sh", "-c", "printf %s \"\$STEP_VALUE\""), out, local.resolve("e"), null, SECRET_ENV, 10.seconds)
        assertEquals(SECRET_VALUE, out.readText(), "the value still reaches the command")
        assertExecRequestsQuoteNoEnvValue()
    }

    @Test
    fun `the reaping exec request carries no env value and the env still reaches the command`(
        @TempDir local: Path,
    ) = runTest {
        assumeTrue(setsidAvailable())
        val out = local.resolve("o.txt")
        reapingExecutor().cmd(listOf("sh", "-c", "printf %s \"\$STEP_VALUE\""), out, local.resolve("e"), null, SECRET_ENV, 10.seconds)
        assertEquals(SECRET_VALUE, out.readText())
        assertExecRequestsQuoteNoEnvValue()
    }

    @Test
    fun `the env file is removed before the command runs`(
        @TempDir local: Path,
    ) = runTest {
        val out = local.resolve("o.txt")
        val listing = "ls -A '$envDir' | wc -l | tr -d ' '"
        val result = executor().cmd(listOf("sh", "-c", listing), out, local.resolve("e"), null, SECRET_ENV, 10.seconds)
        assertEquals(0, result.exitCode)
        assertEquals("0", out.readText().trim(), "the command saw an env file still in place")
        assertTrue(Files.isDirectory(envDir), "the env file was written to the env dir")
    }

    @Test
    fun `an env name the shell cannot export fails the step before any upload or exec`(
        @TempDir local: Path,
    ) = runTest {
        assertThrows<IllegalArgumentException> {
            executor().cmd(listOf("true"), local.resolve("o"), local.resolve("e"), null, mapOf("BAD-NAME" to SECRET_VALUE), 10.seconds)
        }
        assertTrue(server.execRequests.isEmpty(), "no exec request may be sent: ${server.execRequests.size}")
        assertFalse(envDir.exists(), "no env file may be written")
    }

    @Test
    fun `an empty env sends no env file`(
        @TempDir local: Path,
    ) = runTest {
        executor().cmd(listOf("true"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 10.seconds)
        assertFalse(server.execRequests.single().contains(".homer/env"), server.execRequests.single())
        assertFalse(envDir.exists(), "no env dir without an env")
    }

    /** A mocked exec session that never runs the command, with env files written over the real fixture's SFTP. */
    private fun neverRunningExecutor(): SshShellExecutor {
        val (stalled, _) = stalledSession()
        return SshShellExecutor(
            session = stalled,
            remoteWorkdir = workdir.toString(),
            sync = WorkspaceSync(server.newClientSession(client), 30.seconds),
            envDir = envDir.toString(),
        )
    }

    private fun envFilesLeft(): List<Path> = if (envDir.exists()) Files.list(envDir).use { it.toList() } else emptyList()

    @Test
    fun `a step that times out before sourcing its env file leaves none behind`(
        @TempDir local: Path,
    ) = runTest {
        assertThrows<CancellationException> {
            neverRunningExecutor().cmd(listOf("true"), local.resolve("o"), local.resolve("e"), null, SECRET_ENV, 50.milliseconds)
        }
        assertTrue(envDir.exists(), "the step uploaded its env file")
        assertEquals(emptyList(), envFilesLeft())
    }

    @Test
    fun `a step cancelled before sourcing its env file leaves none behind`(
        @TempDir local: Path,
    ) = runBlocking {
        val job =
            launch(Dispatchers.IO) {
                neverRunningExecutor().cmd(listOf("true"), local.resolve("o"), local.resolve("e"), null, SECRET_ENV, 30.seconds)
            }
        withTimeout(10_000) { while (envFilesLeft().isEmpty()) delay(20) }
        withTimeout(10_000) { job.cancelAndJoin() }
        assertEquals(emptyList(), envFilesLeft())
    }

    @Test
    fun `a step that exits non-zero leaves no env file behind`(
        @TempDir local: Path,
    ) = runTest {
        val result = executor().cmd(listOf("sh", "-c", "exit 7"), local.resolve("o"), local.resolve("e"), null, SECRET_ENV, 10.seconds)
        assertEquals(7, result.exitCode)
        assertEquals(emptyList(), envFilesLeft())
    }

    @Test
    fun `a failing env-file removal keeps the step's outcome and logs the path, never the value`(
        @TempDir local: Path,
    ) = runTest {
        val session = server.newClientSession(client)
        val failingRemove = { s: ClientSession ->
            val real = SftpClientFactory.instance().createSftpClient(s)
            spyk(real) { every { remove(any()) } throws IOException("remove failed") }
        }
        val executor =
            SshShellExecutor(session, workdir.toString(), WorkspaceSync(session, 30.seconds, openSftp = failingRemove), envDir.toString())

        val (result, events) =
            captureAllEvents {
                executor.cmd(listOf("sh", "-c", "exit 7"), local.resolve("o"), local.resolve("e"), null, SECRET_ENV, 10.seconds)
            }

        assertEquals(7, result.exitCode, "a failed removal must not replace the step's outcome")
        val warning = events.single { it.formattedMessage.startsWith("Could not remove env file") }
        assertContains(warning.formattedMessage, envDir.toString())
        // The fixture's sshd logs in this JVM; the remote one whose log reaches app.log does not.
        val leaking =
            events
                .filterNot { it.loggerName.startsWith("org.apache.sshd.server") }
                .filter { it.formattedMessage.contains(SECRET_VALUE) }
        assertTrue(leaking.isEmpty(), "log events quoting the env value: ${leaking.map { it.loggerName }}")
    }

    /** Every exec request the fixture saw quotes [SECRET_VALUE] nowhere, and at least one sourced an env file. */
    private fun assertExecRequestsQuoteNoEnvValue() {
        val requests = server.execRequests.toList()
        assertTrue(requests.any { it.contains(envDir.toString()) }, "the step sourced no env file: $requests")
        assertTrue(requests.none { it.contains(SECRET_VALUE) }, "an exec request quoted the env value")
    }

    private fun cappedExecutor(maxOutputBytes: Long) = executorOn(server.newClientSession(client), maxOutputBytes)

    @Test
    fun `a command still writing past the output cap is killed while it runs`(
        @TempDir local: Path,
    ) = runTest {
        val err = local.resolve("e")
        assertThrows<OutputCapExceededException> {
            cappedExecutor(maxOutputBytes = 1_000).cmd(
                listOf("sh", "-c", "while true; do echo 0123456789; done"),
                local.resolve("o"),
                err,
                null,
                emptyMap(),
                20.seconds,
            )
        }
        assertContains(err.readText(), "Output cap exceeded (1000 bytes)")
    }

    @Test
    fun `a command that exits after overshooting the cap still fails on the post-close check`(
        @TempDir local: Path,
    ) = runTest {
        val err = local.resolve("e")
        assertThrows<OutputCapExceededException> {
            cappedExecutor(maxOutputBytes = 100).cmd(
                listOf("head", "-c", "100000", "/dev/zero"),
                local.resolve("o"),
                err,
                null,
                emptyMap(),
                20.seconds,
            )
        }
        assertContains(err.readText(), "Output cap exceeded (100 bytes)")
    }

    @Test
    fun `cancelling the caller stops waiting for the remote command`(
        @TempDir local: Path,
    ) = runBlocking {
        val job =
            launch(Dispatchers.IO) {
                executor().cmd(listOf("sleep", "30"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 30.seconds)
            }
        delay(1_000)
        withTimeout(10_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `times out a long command and does not report success`(
        @TempDir local: Path,
    ) = runTest {
        val exception =
            assertThrows<CancellationException> {
                executor().cmd(listOf("sleep", "30"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 1.seconds)
            }
        assertContains(exception.message.orEmpty(), "Timeout")
    }

    @Test
    fun `reaping wrapper preserves stdout and exit code`(
        @TempDir local: Path,
    ) = runTest {
        assumeTrue(setsidAvailable())
        val out = local.resolve("o.txt")
        val ok = reapingExecutor().cmd(listOf("echo", "hello world"), out, local.resolve("e"), null, emptyMap(), 10.seconds)
        assertEquals(0, ok.exitCode)
        assertEquals("hello world", out.readText().trim())

        val bad =
            reapingExecutor().cmd(
                listOf("sh", "-c", "exit 7"),
                local.resolve("o2"),
                local.resolve("e2"),
                null,
                emptyMap(),
                10.seconds,
            )
        assertEquals(7, bad.exitCode)
    }

    @Test
    fun `a reaping command SIGKILLed from outside reports 137`(
        @TempDir local: Path,
    ) {
        assumeTrue(setsidCanFormGroup())
        // The shell leads its session, as sshd's does, so `setsid -w` forks the wrapper and waits for it.
        val leading = EmbeddedSshServer(shellLeadsSession = true).also { it.start() }
        val childPid = workdir.resolve("child.pid")
        val result =
            try {
                leading.newClientSession(client).use { session ->
                    runBlocking {
                        val killer =
                            launch(Dispatchers.IO) {
                                assertTrue(pollUntil { childPid.exists() && childPid.readText().isNotBlank() }, "the command never started")
                                ProcessBuilder("kill", "-KILL", childPid.readText().trim()).start().waitFor()
                            }
                        executorOn(session, reapProcessGroup = true)
                            .cmd(
                                listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30"),
                                local.resolve("o"),
                                local.resolve("e"),
                                null,
                                emptyMap(),
                                20.seconds,
                            ).also { killer.join() }
                    }
                }
            } finally {
                leading.close()
            }
        assertEquals(137, result.exitCode)
    }

    @Test
    fun `reaps a backgrounded remote child on timeout`(
        @TempDir local: Path,
    ) {
        assumeTrue(setsidAvailable())
        // The command backgrounds a child that writes `marker` after 3s, then blocks; a 1s timeout must kill the whole
        // process group first — if reaping failed the detached child would survive close(true) and create it.
        val marker = workdir.resolve("orphan-marker")
        val script = "{ sleep 3; : > $marker; } & echo bg; sleep 30"
        runBlocking {
            assertThrows<CancellationException> {
                reapingExecutor().cmd(listOf("sh", "-c", script), local.resolve("o"), local.resolve("e"), null, emptyMap(), 1.seconds)
            }
        }
        Thread.sleep(4_000) // past the child's 3s timer
        assertFalse(marker.exists(), "backgrounded remote child was not reaped with its process group")
    }

    /**
     * A mocked session whose exec channel never reports CLOSED, so a short timeout always takes the kill path.
     * Every command string given to `createExecChannel` is recorded; the second is the reaper script the interrupt
     * tests assert on without needing a remote `setsid -w`.
     */
    private fun stalledSession(): Pair<ClientSession, MutableList<String>> {
        val commands = mutableListOf<String>()
        val channel = mockk<ChannelExec>(relaxed = true)
        // A relaxed OpenFuture cannot answer `verify`: its generated subclass returns Object
        // where the executor expects an OpenFuture back, so stub the one call the open does.
        val openFuture = mockk<OpenFuture>()
        every { openFuture.verify(any<Long>()) } returns openFuture
        every { channel.open() } returns openFuture
        every { channel.waitFor(any<Collection<ClientChannelEvent>>(), any<Long>()) } answers {
            waitUnfinished(secondArg(), TimeUnit.MILLISECONDS)
            emptySet()
        }
        val session = mockk<ClientSession>()
        every { session.createExecChannel(capture(commands)) } returns channel
        return session to commands
    }

    private fun mockedReapingExecutor(session: ClientSession) = executorOn(session, reapProcessGroup = true)

    @Test
    fun `a timeout with an interrupt grace reaps the remote group with SIGINT before the kill`(
        @TempDir local: Path,
    ) = runTest {
        val (session, commands) = stalledSession()
        assertThrows<CancellationException> {
            mockedReapingExecutor(session).cmd(
                listOf("sleep", "30"),
                local.resolve("o"),
                local.resolve("e"),
                null,
                emptyMap(),
                50.milliseconds,
                interruptGrace = 2.seconds,
            )
        }
        val reaper = commands.last()
        assertContains(reaper, "kill -INT")
        assertContains(reaper, "sleep 2;")
        assertContains(reaper, "kill -KILL")
    }

    @Test
    fun `a timeout without an interrupt grace reaps the remote group with the immediate kill`(
        @TempDir local: Path,
    ) = runTest {
        val (session, commands) = stalledSession()
        assertThrows<CancellationException> {
            mockedReapingExecutor(session).cmd(
                listOf("sleep", "30"),
                local.resolve("o"),
                local.resolve("e"),
                null,
                emptyMap(),
                50.milliseconds,
            )
        }
        val reaper = commands.last()
        assertFalse(reaper.contains("kill -INT"), "without a grace no SIGINT is sent")
        assertContains(reaper, "kill -KILL")
    }

    @Test
    fun `a timeout with an interrupt grace lets the remote group exit on SIGINT`(
        @TempDir local: Path,
    ) {
        assumeTrue(setsidAvailable())
        // The remote command traps SIGINT, records that the trap ran and exits, so the
        // marker proves the graceful reaper SIGINTed the group (a SIGKILL runs no trap).
        val marker = workdir.resolve("interrupt-marker")
        val script = "trap 'echo int > $marker; exit 0' INT; sleep 30"
        runBlocking {
            assertThrows<CancellationException> {
                reapingExecutor().cmd(
                    listOf("sh", "-c", script),
                    local.resolve("o"),
                    local.resolve("e"),
                    null,
                    emptyMap(),
                    1.seconds,
                    interruptGrace = 2.seconds,
                )
            }
        }
        assertTrue(marker.exists(), "with a grace the remote group gets SIGINT and its trap runs")
    }

    @Test
    fun `a timeout without an interrupt grace SIGKILLs the remote group immediately`(
        @TempDir local: Path,
    ) {
        assumeTrue(setsidAvailable())
        val marker = workdir.resolve("interrupt-marker")
        val script = "trap 'echo int > $marker; exit 0' INT; sleep 30"
        runBlocking {
            assertThrows<CancellationException> {
                reapingExecutor().cmd(listOf("sh", "-c", script), local.resolve("o"), local.resolve("e"), null, emptyMap(), 1.seconds)
            }
        }
        assertFalse(marker.exists(), "without a grace the group is SIGKILLed and the trap never runs")
    }

    @Test
    fun `a refused exec request fails the step naming the refusal and quoting no env value`(
        @TempDir local: Path,
    ) = runTest {
        val refusing = EmbeddedSshServer(refuseExec = true).also { it.start() }
        try {
            // Distinctive, keyword-free: a value the generic secret pattern keys on would fail the pull-request scan.
            val envValue = "UNIQUE-STEP-VALUE-3f9a1c"
            val (exception, events) =
                captureAllEvents {
                    assertThrows<SshExecRefusedException> {
                        executorOn(refusing.newClientSession(client))
                            .cmd(
                                listOf("echo", "hello"),
                                local.resolve("o"),
                                local.resolve("e"),
                                null,
                                mapOf("SECRET_MARK" to envValue),
                                10.seconds,
                            )
                    }
                }
            val message = exception.message.orEmpty()
            assertContains(message, "refused the exec request")
            assertFalse(message.contains(envValue), "the step error must quote no env value")
            // The fixture's sshd logs in this JVM; the remote one whose log reaches app.log does not.
            val leaking =
                events
                    .filterNot { it.loggerName.startsWith("org.apache.sshd.server") }
                    .filter { "${it.formattedMessage} ${it.throwableProxy?.message}".contains(envValue) }
            assertTrue(leaking.isEmpty(), "log events quoting the env value: ${leaking.map { it.loggerName }}")
        } finally {
            refusing.close()
        }
    }

    @Test
    fun `a failed channel open fails the step with the refusal message and no server text`(
        @TempDir local: Path,
    ) = runTest {
        val serverText = "no more channels: UNIQUE-STEP-VALUE-3f9a1c"
        val channel = mockk<ChannelExec>(relaxed = true)
        val openFuture = mockk<OpenFuture>()
        every { openFuture.verify(any<Long>()) } throws SshChannelOpenException(1L, 1, serverText)
        every { channel.open() } returns openFuture
        val session = mockk<ClientSession>(relaxed = true)
        every { session.createExecChannel(any()) } returns channel

        val exception =
            assertThrows<SshExecRefusedException> {
                executorOn(session)
                    .cmd(listOf("echo", "hello"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 10.seconds)
            }
        assertContains(exception.message.orEmpty(), "refused the exec request")
        assertFalse(exception.message.orEmpty().contains(serverText), "the server's own text must not be quoted")
        val chain = generateSequence(exception as Throwable?) { it.cause }.toList()
        assertTrue(
            chain.none { it is SshChannelOpenException || it.message.orEmpty().contains(serverText) },
            "the MINA text quotes the command, so it must reach neither the message nor the cause chain",
        )
    }

    @Test
    fun `a timed out or dropped channel open is a lost connection, not a refusal`(
        @TempDir local: Path,
    ) = runTest {
        val serverText = "connection dropped mid-handshake: UNIQUE-STEP-VALUE-3f9a1c"
        val exception = openFailsWith(IOException(serverText), local)
        assertFalse(exception.message.orEmpty().contains(serverText), "the transport text must not be quoted")
        val chain = generateSequence(exception as Throwable?) { it.cause }.toList()
        assertTrue(
            chain.none { it is SshExecRefusedException || it.message.orEmpty().contains(serverText) },
            "a dropped handshake is not a refusal and names no server text",
        )
    }

    @Test
    fun `a channel open that times out instead of dropping is the same lost connection`(
        @TempDir local: Path,
    ) = runTest {
        openFailsWith(SocketTimeoutException("open timed out"), local)
    }

    /** `cmd` against a mocked session whose exec channel fails to open with [failure]: the step error it produces. */
    private suspend fun openFailsWith(
        failure: Exception,
        local: Path,
    ): SshConnectionLostException {
        val channel = mockk<ChannelExec>(relaxed = true)
        val openFuture = mockk<OpenFuture>()
        every { openFuture.verify(any<Long>()) } throws failure
        every { channel.open() } returns openFuture
        val session = mockk<ClientSession>(relaxed = true)
        every { session.createExecChannel(any()) } returns channel

        val exception =
            assertThrows<SshConnectionLostException> {
                executorOn(session)
                    .cmd(listOf("echo", "hello"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 10.seconds)
            }
        assertContains(exception.message.orEmpty(), "SSH channel open failed")
        return exception
    }

    @Test
    fun `a channel closed without an exit status on a dead session is still a lost connection`(
        @TempDir local: Path,
    ) = runTest {
        val channel = mockk<ChannelExec>(relaxed = true)
        val openFuture = mockk<OpenFuture>()
        every { openFuture.verify(any<Long>()) } returns openFuture
        every { channel.open() } returns openFuture
        every { channel.waitFor(any<Collection<ClientChannelEvent>>(), any<Long>()) } returns setOf(ClientChannelEvent.CLOSED)
        every { channel.exitStatus } returns null
        every { channel.exitSignal } returns null
        val session = mockk<ClientSession>(relaxed = true)
        every { session.createExecChannel(any()) } returns channel
        every { session.isOpen } returns false
        every { session.isClosing } returns true

        val exception =
            assertThrows<SshConnectionLostException> {
                executorOn(session)
                    .cmd(listOf("echo", "hello"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 10.seconds)
            }
        assertContains(exception.message.orEmpty(), "SSH channel closed without an exit status")
    }

    @Test
    fun `a command the remote killed with a signal reports 128 plus its number, not a lost connection`(
        @TempDir local: Path,
    ) = runTest {
        assertEquals(137, exitWithSignal("KILL", local).exitCode)
        assertEquals(143, exitWithSignal("TERM", local).exitCode)
        assertEquals(130, exitWithSignal("INT", local).exitCode)
    }

    @Test
    fun `an unknown exit signal fails the step naming the signal and quoting no command`(
        @TempDir local: Path,
    ) = runTest {
        val exception = assertThrows<SshUnknownExitSignalException> { exitWithSignal("BOGUS", local) }
        assertContains(exception.message.orEmpty(), "BOGUS")
        assertFalse(exception.message.orEmpty().contains("echo"), "the step error must quote no command")
    }

    @Test
    fun `OpenSSH's catch-all signal name fails the step with that name`(
        @TempDir local: Path,
    ) = runTest {
        val exception = assertThrows<SshUnknownExitSignalException> { exitWithSignal("SIG@openssh.com", local) }
        assertContains(exception.message.orEmpty(), "SIG@openssh.com")
    }

    @Test
    fun `an unknown exit signal on a session that is gone is a lost connection quoting nothing from the server`(
        @TempDir local: Path,
    ) = runTest {
        val exception = assertThrows<SshConnectionLostException> { exitWithSignal("BOGUS", local, sessionLive = false) }
        assertFalse(exception.message.orEmpty().contains("BOGUS"))
    }

    @Test
    fun `an unknown exit signal on a session still open but closing is a lost connection quoting nothing`(
        @TempDir local: Path,
    ) = runTest {
        val exception =
            assertThrows<SshConnectionLostException> { exitWithSignal("BOGUS", local, sessionLive = true, closing = true) }
        assertFalse(exception.message.orEmpty().contains("BOGUS"))
    }

    @Test
    fun `a channel with neither exit status nor signal on a session still open but closing is a lost connection`(
        @TempDir local: Path,
    ) = runTest {
        assertThrows<SshConnectionLostException> { exitWithSignal(null, local, sessionLive = true, closing = true) }
    }

    @Test
    fun `a known exit signal still reports 128 plus its number on a session that is closing`(
        @TempDir local: Path,
    ) = runTest {
        assertEquals(137, exitWithSignal("KILL", local, sessionLive = false).exitCode)
    }

    @Test
    fun `an exit signal naming control characters is withheld from the step error`(
        @TempDir local: Path,
    ) = runTest {
        val exception = assertThrows<SshUnknownExitSignalException> { exitWithSignal("X\nINJECTED line", local) }
        assertFalse(exception.message.orEmpty().contains("INJECTED"))
    }

    @Test
    fun `a channel closed with neither exit status nor signal on a live session is still a refusal`(
        @TempDir local: Path,
    ) = runTest {
        assertThrows<SshExecRefusedException> { exitWithSignal(null, local) }
    }

    /** `cmd` against a mocked live session whose exec channel closes with no exit status and [signal], if any. */
    private suspend fun exitWithSignal(
        signal: String?,
        local: Path,
        sessionLive: Boolean = true,
        closing: Boolean = !sessionLive,
    ): CommandResult {
        val channel = mockk<ChannelExec>(relaxed = true)
        val openFuture = mockk<OpenFuture>()
        every { openFuture.verify(any<Long>()) } returns openFuture
        every { channel.open() } returns openFuture
        every { channel.waitFor(any<Collection<ClientChannelEvent>>(), any<Long>()) } returns setOf(ClientChannelEvent.CLOSED)
        every { channel.exitStatus } returns null
        every { channel.exitSignal } returns signal
        val session = mockk<ClientSession>(relaxed = true)
        every { session.createExecChannel(any()) } returns channel
        every { session.isOpen } returns sessionLive
        every { session.isClosing } returns closing
        return executorOn(session)
            .cmd(listOf("echo", "hello"), local.resolve("o"), local.resolve("e"), null, emptyMap(), 10.seconds)
    }

    @Test
    fun `the real session and exec channel are the classes the pinned loggers name`() {
        val session = server.newClientSession(client)
        val channel = session.createExecChannel("true")
        try {
            // logback.xml pins these two logger names to keep the env file's bytes and the command out of app.log;
            // a MINA upgrade that returned a subclass would escape the pin and log them again.
            assertEquals(ClientSessionImpl::class.java.name, session.javaClass.name)
            assertEquals(ChannelExec::class.java.name, channel.javaClass.name)
        } finally {
            channel.close(true)
            session.close(true)
        }
    }

    private companion object {
        /** Secret-shaped, keyword-free: the generic secret pattern of the pull-request scan keys on assignments. */
        const val SECRET_VALUE = "s3cr3t-Ab9-env-value"
        val SECRET_ENV = mapOf("STEP_VALUE" to SECRET_VALUE)

        const val TRICKY_VALUE = "it's \"quoted\"\nline two \$HOME `id` \\ \$(true) '"
    }
}
