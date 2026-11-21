package homer.app.services

import homer.app.executors.CommandArtifactPaths
import homer.app.fixtures.TestFixtures
import homer.app.infra.DescriptorOpen
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogTailServiceTest {
    // No live job registered: every tick reads the status, as for a restarted or finished run.
    private fun processManagerWithoutJob() = mockk<ProcessManager> { every { hasLiveJob(any()) } returns false }

    @Test
    fun `resolve returns null when process not found`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(99) } returns null
        val service = LogTailService(processManager, TestFixtures.mockLogger())
        assertEquals(null, service.resolve(99, 0, LogTailService.Stream.OUT))
    }

    @Test
    fun `resolve falls back to cmd_N naming when nothing is tracked`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(1) } returns processInfo(id = 1, artifactsDir = tmp)
        coEvery { processManager.getCommandArtifacts(1, 0) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertEquals(tmp.resolve("cmd_0.out"), service.resolve(1, 0, LogTailService.Stream.OUT))
        assertEquals(tmp.resolve("cmd_0.err"), service.resolve(1, 0, LogTailService.Stream.ERR))
    }

    @Test
    fun `resolve reads the persisted execution path when nothing is tracked after a restart`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        val out = tmp.resolve("build_0.out")
        val err = tmp.resolve("build_0.err")
        coEvery { processManager.getProcessInfo(1) } returns
            processInfo(id = 1, artifactsDir = tmp, executions = listOf(execution(out, err)))
        coEvery { processManager.getCommandArtifacts(1, 0) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertEquals(out, service.resolve(1, 0, LogTailService.Stream.OUT))
        assertEquals(err, service.resolve(1, 0, LogTailService.Stream.ERR))
    }

    @Test
    fun `resolve serves the row that recorded the requested command index, not the row at that position`(
        @TempDir tmp: Path,
    ) = runTest {
        // A run that executes a subset of its commands records the command's own index, so the
        // row at position 0 belongs to command 2 and must not answer `cmd=0`.
        val out = tmp.resolve("build_2.out")
        val err = tmp.resolve("build_2.err")
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(1) } returns
            processInfo(id = 1, artifactsDir = tmp, executions = listOf(execution(out, err, commandIndex = 2)))
        coEvery { processManager.getCommandArtifacts(1, any()) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertEquals(out, service.resolve(1, 2, LogTailService.Stream.OUT))
        assertEquals(err, service.resolve(1, 2, LogTailService.Stream.ERR))
        // Command 0 recorded no row: the default name, never command 2's file.
        assertEquals(tmp.resolve("cmd_0.out"), service.resolve(1, 0, LogTailService.Stream.OUT))
        assertEquals(tmp.resolve("cmd_0.err"), service.resolve(1, 0, LogTailService.Stream.ERR))
    }

    @Test
    fun `resolve falls back to the list position only for a row that recorded no index`(
        @TempDir tmp: Path,
    ) = runTest {
        val first = tmp.resolve("first.out")
        val second = tmp.resolve("second.out")
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(1) } returns
            processInfo(
                id = 1,
                artifactsDir = tmp,
                executions = listOf(execution(first, null), execution(second, null, commandIndex = 1)),
            )
        coEvery { processManager.getCommandArtifacts(1, any()) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertEquals(first, service.resolve(1, 0, LogTailService.Stream.OUT))
        assertEquals(second, service.resolve(1, 1, LogTailService.Stream.OUT))
    }

    @Test
    fun `resolve is null when the execution row recorded no output file`(
        @TempDir tmp: Path,
    ) = runTest {
        // A cmd_0 file exists, so a null proves the row's missing path is not replaced by the default name.
        tmp.resolve("cmd_0.out").writeText("not this step's output")
        tmp.resolve("cmd_0.err").writeText("not this step's output")
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(1) } returns
            processInfo(id = 1, artifactsDir = tmp, executions = listOf(execution(null, null)))
        coEvery { processManager.getCommandArtifacts(1, 0) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertNull(service.resolve(1, 0, LogTailService.Stream.OUT))
        assertNull(service.resolve(1, 0, LogTailService.Stream.ERR))
    }

    @Test
    fun `resolve refuses a persisted execution path outside the artifacts dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val artifactsDir = tmp.resolve("artifacts").createDirectories()
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(1) } returns
            processInfo(
                id = 1,
                artifactsDir = artifactsDir,
                executions =
                    listOf(
                        execution(artifactsDir.resolve("../escaped.out"), Path.of("/etc/passwd")),
                        execution(tmp.resolve("sibling/cmd_1.out"), artifactsDir.resolve("sub/cmd_1.err")),
                    ),
            )
        coEvery { processManager.getCommandArtifacts(1, any()) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertNull(service.resolve(1, 0, LogTailService.Stream.OUT))
        assertNull(service.resolve(1, 0, LogTailService.Stream.ERR))
        assertNull(service.resolve(1, 1, LogTailService.Stream.OUT))
        assertNull(service.resolve(1, 1, LogTailService.Stream.ERR))
    }

    @Test
    fun `resolve refuses a persisted execution path through a symlinked artifact`(
        @TempDir tmp: Path,
    ) = runTest {
        val artifactsDir = tmp.resolve("artifacts").createDirectories()
        val target = tmp.resolve("secret.txt").also { it.writeText("x") }
        val link = Files.createSymbolicLink(artifactsDir.resolve("cmd_0.out"), target)
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(1) } returns
            processInfo(id = 1, artifactsDir = artifactsDir, executions = listOf(execution(link, null)))
        coEvery { processManager.getCommandArtifacts(1, 0) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertNull(service.resolve(1, 0, LogTailService.Stream.OUT))
        assertNull(service.resolve(1, 0, LogTailService.Stream.ERR), "a row that names no stderr file has none to tail")
    }

    @Test
    fun `resolve refuses an artifacts directory that is a symlink`(
        @TempDir tmp: Path,
    ) = runTest {
        val outside = tmp.resolve("outside").createDirectories()
        outside.resolve("cmd_0.out").writeText("x")
        val artifactsDir = Files.createSymbolicLink(tmp.resolve("artifacts"), outside)
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(1) } returns processInfo(id = 1, artifactsDir = artifactsDir)
        coEvery { processManager.getCommandArtifacts(1, 0) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertNull(service.resolve(1, 0, LogTailService.Stream.OUT))
    }

    @Test
    fun `resolve prefers the tracked path over the default cmd_N naming`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        val trackedOut = tmp.resolve("cmd_0_attempt_1.out")
        val trackedErr = tmp.resolve("cmd_0_attempt_1.err")
        coEvery { processManager.getProcessInfo(1) } returns processInfo(id = 1, artifactsDir = tmp)
        coEvery { processManager.getCommandArtifacts(1, 0) } returns
            CommandArtifactPaths(trackedOut, trackedErr)

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertEquals(trackedOut, service.resolve(1, 0, LogTailService.Stream.OUT))
        assertEquals(trackedErr, service.resolve(1, 0, LogTailService.Stream.ERR))
    }

    @Test
    fun `resolve serves a live run's tracked cmd_2 file for cmd=2 only`(
        @TempDir tmp: Path,
    ) = runTest {
        // A live run tracks each command's file under that command's own index, so command 2's
        // tracked path answers `cmd=2` alone: the run's only row is command 2's, at position 0.
        val trackedOut = tmp.resolve("cmd_2_attempt_1.out")
        val trackedErr = tmp.resolve("cmd_2_attempt_1.err")
        val processManager = processManagerWithoutJob()
        every { processManager.hasLiveJob(1) } returns true
        coEvery { processManager.getProcessInfo(1) } returns
            processInfo(
                id = 1,
                artifactsDir = tmp,
                executions = listOf(execution(tmp.resolve("cmd_2.out"), null, commandIndex = 2)),
            )
        coEvery { processManager.getCommandArtifacts(1, 2) } returns CommandArtifactPaths(trackedOut, trackedErr)
        coEvery { processManager.getCommandArtifacts(1, 0) } returns null

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertEquals(trackedOut, service.resolve(1, 2, LogTailService.Stream.OUT))
        assertEquals(trackedErr, service.resolve(1, 2, LogTailService.Stream.ERR))
        // Command 0 is not this run's: the `cmd_0` default, never command 2's tracked file.
        assertEquals(tmp.resolve("cmd_0.out"), service.resolve(1, 0, LogTailService.Stream.OUT))
        assertEquals(tmp.resolve("cmd_0.err"), service.resolve(1, 0, LogTailService.Stream.ERR))
    }

    @Test
    fun `resolve falls back to cmd_N naming when the tracked path escapes the artifacts dir`(
        @TempDir tmp: Path,
    ) = runTest {
        // ADR-0047 rule 2: commandArtifacts holds agent-influenced paths; one whose parent is not this
        // process's own artifacts dir must never be trusted, whatever produced it.
        val processManager = processManagerWithoutJob()
        val escapedOut = tmp.resolve("../escaped.out")
        val escapedErr = tmp.resolve("../escaped.err")
        coEvery { processManager.getProcessInfo(1) } returns processInfo(id = 1, artifactsDir = tmp)
        coEvery { processManager.getCommandArtifacts(1, 0) } returns
            CommandArtifactPaths(escapedOut, escapedErr)

        val service = LogTailService(processManager, TestFixtures.mockLogger())

        assertEquals(tmp.resolve("cmd_0.out"), service.resolve(1, 0, LogTailService.Stream.OUT))
        assertEquals(tmp.resolve("cmd_0.err"), service.resolve(1, 0, LogTailService.Stream.ERR))
    }

    @Test
    fun `tail streams appended bytes and ends on terminal status`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        val path = tmp.resolve("cmd_0.out")
        Files.createFile(path)
        coEvery { processManager.getProcessStatus(1) } returnsMany
            listOf(
                ProcessStatus.WORKING,
                ProcessStatus.WORKING,
                ProcessStatus.FINISHED,
            )

        val service = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 1L)

        path.writeText("hello")

        val job =
            async {
                service.tail(1, path, resumeFromOffset = 0L).take(2).toList()
            }

        yield()
        path.toFile().appendText(" world")

        val events = job.await()
        assertTrue(events.any { it is LogTailService.LogEvent.Chunk })
        val chunks = events.filterIsInstance<LogTailService.LogEvent.Chunk>()
        val combined = chunks.joinToString("") { it.content }
        assertTrue(combined.contains("hello"))
    }

    @Test
    fun `tail resumes from supplied offset`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.FINISHED
        val path = tmp.resolve("cmd_0.out")
        path.writeText("PREFIX-DATA")

        val service = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 1L)
        val events = service.tail(1, path, resumeFromOffset = 7L).take(2).toList()
        val chunk = events.filterIsInstance<LogTailService.LogEvent.Chunk>().first()
        assertEquals("DATA", chunk.content)
    }

    @Test
    fun `tail caches log file handle across poll cycles and closes it on end (audit P1 #16)`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        val path = tmp.resolve("cmd_0.out")
        path.writeText("seed")
        coEvery { processManager.getProcessStatus(1) } returnsMany
            listOf(
                ProcessStatus.WORKING,
                ProcessStatus.WORKING,
                ProcessStatus.WORKING,
                ProcessStatus.FINISHED,
            )

        val opens = AtomicInteger(0)
        val channels = mutableListOf<SeekableByteChannel>()
        val factory: (Path) -> SeekableByteChannel = { p ->
            opens.incrementAndGet()
            DescriptorOpen.openRegularChannel(p).also { channels.add(it) }
        }

        val service =
            LogTailService(
                processManager = processManager,
                logger = TestFixtures.mockLogger(),
                pollIntervalMs = 1L,
                channelFactory = factory,
            )

        service.tail(1, path, resumeFromOffset = 0L).toList()

        assertEquals(1, opens.get(), "the channel should be opened exactly once across all poll cycles")
        assertEquals(1, channels.size)
        assertTrue(!channels.single().isOpen, "cached channel must be closed when the flow ends")
    }

    @Test
    fun `tail closes cached handle when cancelled before terminal status (audit P1 #16)`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        val path = tmp.resolve("cmd_0.out")
        path.writeText("data")
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.WORKING

        val channels = mutableListOf<SeekableByteChannel>()
        val factory: (Path) -> SeekableByteChannel = { p ->
            DescriptorOpen.openRegularChannel(p).also { channels.add(it) }
        }

        val service =
            LogTailService(
                processManager = processManager,
                logger = TestFixtures.mockLogger(),
                pollIntervalMs = 1L,
                channelFactory = factory,
            )

        service.tail(1, path, resumeFromOffset = 0L).take(1).toList()

        assertEquals(1, channels.size)
        assertTrue(!channels.single().isOpen, "cached channel must be closed on flow cancellation")
    }

    @Test
    fun `tail through the descriptor channel survives the file being truncated under it`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        val path = tmp.resolve("cmd_0.out")
        path.writeText("hello world")
        val polls = AtomicInteger(0)
        coEvery { processManager.getProcessStatus(1) } answers {
            when (polls.incrementAndGet()) {
                1 -> ProcessStatus.WORKING
                2 -> ProcessStatus.WORKING.also { path.writeText("hi") }
                else -> ProcessStatus.FINISHED
            }
        }
        val service = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 1L)

        val events = service.tail(1, path, resumeFromOffset = 0L).toList()

        assertEquals(listOf("hello world"), events.filterIsInstance<LogTailService.LogEvent.Chunk>().map { it.content })
        assertEquals(LogTailService.LogEvent.End, events.last())
    }

    @Test
    fun `tail replays existing content on fresh connection with no resume offset`(
        @TempDir tmp: Path,
    ) = runTest {
        // A short command can finish before the SSE connection opens: the tail must still replay it.
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.FINISHED
        val path = tmp.resolve("cmd_0.out")
        path.writeText("already written before client connected")

        val service = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 1L)
        val events = service.tail(1, path, resumeFromOffset = null).take(2).toList()

        val chunks = events.filterIsInstance<LogTailService.LogEvent.Chunk>()
        assertTrue(chunks.isNotEmpty())
        assertEquals("already written before client connected", chunks.joinToString("") { it.content })
        assertTrue(events.any { it is LogTailService.LogEvent.End })
    }

    @Test
    fun `an UNKNOWN process ends the tail with End`(
        @TempDir tmp: Path,
    ) = runTest {
        // A restart-interrupted run (or a deleted row) reports UNKNOWN; the tail must treat
        // it as terminal instead of polling forever and holding the SSE connection open.
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.UNKNOWN
        val path = tmp.resolve("cmd_0.out")
        path.writeText("data")

        val service = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 1L)
        val events = service.tail(1, path, resumeFromOffset = 0L).toList()

        assertEquals(LogTailService.LogEvent.End, events.last())
    }

    @Test
    fun `a live run reads its status only on the safety cadence, not every tick`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        every { processManager.hasLiveJob(1) } returns true
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.WORKING
        val path = tmp.resolve("cmd_0.out").also { it.writeText("data") }
        val service = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 100L)

        val job = launch { service.tail(1, path, resumeFromOffset = 0L).collect {} }
        // 200 ticks of 100 ms while the job is live: one safety read per 50 ticks, none in between.
        testScheduler.advanceTimeBy(4 * SAFETY_STATUS_READ_MS)
        coVerify(exactly = 4) { processManager.getProcessStatus(1) }
        job.cancelAndJoin()
    }

    @Test
    fun `a live run's tail ends on the next tick once its job is gone and the status is terminal`(
        @TempDir tmp: Path,
    ) = runTest {
        val live = AtomicBoolean(true)
        val status = AtomicReference(ProcessStatus.WORKING)
        val processManager = processManagerWithoutJob()
        every { processManager.hasLiveJob(1) } answers { live.get() }
        coEvery { processManager.getProcessStatus(1) } answers { status.get() }
        val path = tmp.resolve("cmd_0.out").also { it.writeText("data") }
        val service = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 100L)

        val collected = async { service.tail(1, path, resumeFromOffset = 0L).toList() }
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertFalse(collected.isCompleted)
        status.set(ProcessStatus.FINISHED)
        live.set(false)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertTrue(collected.isCompleted)
        assertEquals(LogTailService.LogEvent.End, collected.await().last())
    }

    @Test
    fun `a terminal run whose job lingers still ends on the safety read`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        every { processManager.hasLiveJob(1) } returns true
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.FINISHED
        val path = tmp.resolve("cmd_0.out").also { it.writeText("data") }
        val service = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 100L)

        val collected = async { service.tail(1, path, resumeFromOffset = 0L).toList() }
        testScheduler.advanceTimeBy(SAFETY_STATUS_READ_MS / 2)
        testScheduler.runCurrent()
        assertFalse(collected.isCompleted)
        testScheduler.advanceTimeBy(SAFETY_STATUS_READ_MS / 2)
        testScheduler.runCurrent()
        assertTrue(collected.isCompleted)
        assertEquals(LogTailService.LogEvent.End, collected.await().last())
    }

    @Test
    fun `a run without a registered job reads its status every tick and ends at once when terminal`(
        @TempDir tmp: Path,
    ) = runTest {
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.UNKNOWN
        val path = tmp.resolve("cmd_0.out").also { it.writeText("data") }

        val events = LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 100L).tail(1, path, 0L).toList()

        assertEquals(LogTailService.LogEvent.End, events.last())
        assertEquals(0L, testScheduler.currentTime)
    }

    // Linked / non-regular stdout artifacts (CWE-22, CWE-59): an agent runs as Homer's UID
    // and can plant any of these at its artifact name; none may be served.

    private val secret = "HOST-SECRET-CONTENT"

    private fun artifactsIn(tmp: Path): Path = tmp.resolve("artifacts").createDirectories()

    private fun outsideSecret(tmp: Path): Path = tmp.resolve("host-secret.txt").also { it.writeText(secret) }

    private fun finishedProcess(artifactsDir: Path): ProcessManager {
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessInfo(1) } returns processInfo(id = 1, artifactsDir = artifactsDir)
        coEvery { processManager.getCommandArtifacts(1, 0) } returns null
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.FINISHED
        return processManager
    }

    private fun assertNothingServed(events: List<LogTailService.LogEvent>) {
        assertEquals(listOf<LogTailService.LogEvent>(LogTailService.LogEvent.End), events)
        assertTrue(events.none { it is LogTailService.LogEvent.Chunk && secret in it.content })
    }

    @Test
    fun `resolve refuses a symlink at the artifact name pointing outside the artifacts dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        Files.createSymbolicLink(dir.resolve("cmd_0.out"), outsideSecret(tmp))
        assertNull(LogTailService(finishedProcess(dir), TestFixtures.mockLogger()).resolve(1, 0, LogTailService.Stream.OUT))
    }

    @Test
    fun `resolve refuses a tracked symlink to another artifact in the same dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val other = dir.resolve("cmd_1.out").also { it.writeText(secret) }
        val link = Files.createSymbolicLink(dir.resolve("cmd_0_attempt_1.out"), other)
        val processManager = finishedProcess(dir)
        coEvery { processManager.getCommandArtifacts(1, 0) } returns CommandArtifactPaths(link, dir.resolve("e"))
        assertNull(LogTailService(processManager, TestFixtures.mockLogger()).resolve(1, 0, LogTailService.Stream.OUT))
    }

    @Test
    fun `resolve refuses a dangling symlink at the artifact name`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        Files.createSymbolicLink(dir.resolve("cmd_0.out"), tmp.resolve("not-yet-there"))
        assertNull(LogTailService(finishedProcess(dir), TestFixtures.mockLogger()).resolve(1, 0, LogTailService.Stream.OUT))
    }

    @Test
    fun `tail refuses a symlink planted at the path after resolve and ends normally`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val service = LogTailService(finishedProcess(dir), TestFixtures.mockLogger(), pollIntervalMs = 1L)
        val path = service.resolve(1, 0, LogTailService.Stream.OUT)!!
        Files.createSymbolicLink(path, outsideSecret(tmp))
        assertNothingServed(service.tail(1, path, resumeFromOffset = 0L).toList())
    }

    @Test
    fun `tail refuses a symlink to another artifact in the same dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val other = dir.resolve("cmd_1.out").also { it.writeText(secret) }
        val path = Files.createSymbolicLink(dir.resolve("cmd_0.out"), other)
        val service = LogTailService(finishedProcess(dir), TestFixtures.mockLogger(), pollIntervalMs = 1L)
        assertNothingServed(service.tail(1, path, resumeFromOffset = 0L).toList())
    }

    @Test
    fun `tail refuses a dangling symlink`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val path = Files.createSymbolicLink(dir.resolve("cmd_0.out"), tmp.resolve("not-yet-there"))
        val service = LogTailService(finishedProcess(dir), TestFixtures.mockLogger(), pollIntervalMs = 1L)
        assertNothingServed(service.tail(1, path, resumeFromOffset = 0L).toList())
    }

    @Test
    fun `tail refuses a hard link and warns once without the content`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val path = Files.createLink(dir.resolve("cmd_0.out"), outsideSecret(tmp))
        val status = AtomicInteger(0)
        val processManager = finishedProcess(dir)
        coEvery { processManager.getProcessStatus(1) } answers {
            if (status.incrementAndGet() < 4) ProcessStatus.WORKING else ProcessStatus.FINISHED
        }
        val logger = TestFixtures.mockLogger()
        val service = LogTailService(processManager, logger, pollIntervalMs = 1L)
        assertEquals(path, service.resolve(1, 0, LogTailService.Stream.OUT))

        assertNothingServed(service.tail(1, path, resumeFromOffset = 0L).toList())
        verify(exactly = 1) { logger.warn(match<String> { "refusing" in it }, 1, "cmd_0.out") }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `tail refuses a FIFO without blocking on it`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val path = dir.resolve("cmd_0.out")
        TestFixtures.mkfifoOrSkip(path)
        val service = LogTailService(finishedProcess(dir), TestFixtures.mockLogger(), pollIntervalMs = 1L)
        assertNothingServed(service.tail(1, path, resumeFromOffset = 0L).toList())
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `tail refuses at once a FIFO swapped in after the check`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        TestFixtures.mkfifoOrSkip(tmp.resolve("probe.fifo"))
        val path = dir.resolve("cmd_0.out").also { it.writeText("own output") }
        val logger = TestFixtures.mockLogger()
        val service =
            LogTailService(finishedProcess(dir), logger, pollIntervalMs = 1L) { p ->
                Files.delete(p)
                check(ProcessBuilder("mkfifo", p.toString()).start().waitFor() == 0) { "mkfifo failed" }
                DescriptorOpen.openRegularChannel(p)
            }

        assertNothingServed(service.tail(1, path, resumeFromOffset = 0L).toList())
        verify(exactly = 1) { logger.warn(match<String> { "refusing" in it }, 1, "cmd_0.out") }
    }

    @Test
    fun `tail refuses a hard link made after the check`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val path = dir.resolve("cmd_0.out").also { it.writeText(secret) }
        val service =
            LogTailService(finishedProcess(dir), TestFixtures.mockLogger(), pollIntervalMs = 1L) { p ->
                Files.createLink(tmp.resolve("elsewhere.txt"), p)
                DescriptorOpen.openRegularChannel(p)
            }

        assertNothingServed(service.tail(1, path, resumeFromOffset = 0L).toList())
    }

    @Test
    fun `tail still streams a regular file inside the artifacts dir`(
        @TempDir tmp: Path,
    ) = runTest {
        val dir = artifactsIn(tmp)
        val service = LogTailService(finishedProcess(dir), TestFixtures.mockLogger(), pollIntervalMs = 1L)
        val path = service.resolve(1, 0, LogTailService.Stream.OUT)!!
        path.writeText("own output")
        val events = service.tail(1, path, resumeFromOffset = 0L).toList()
        assertEquals("own output", events.filterIsInstance<LogTailService.LogEvent.Chunk>().joinToString("") { it.content })
        assertEquals(LogTailService.LogEvent.End, events.last())
    }

    @Test
    fun `tail reads a large file in bounded chunks with increasing offsets`(
        @TempDir tmp: Path,
    ) = runTest {
        // ADR-0047 rule 4, CWE-400: one subscriber must not allocate the whole remainder at once.
        val path = tmp.resolve("cmd_0.out")
        val content = "abcdefghij\n".repeat(3 * 1024 * 1024 / 11 + 1)
        path.writeText(content)
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.FINISHED

        val chunks =
            LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 1L)
                .tail(1, path, resumeFromOffset = 0L)
                .toList()
                .filterIsInstance<LogTailService.LogEvent.Chunk>()

        assertTrue(chunks.size >= 3, "expected >= 3 chunks, got ${chunks.size}")
        assertTrue(chunks.all { it.content.toByteArray().size <= LogTailService.MAX_READ_BYTES })
        assertEquals(content, chunks.joinToString("") { it.content })
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.offset < b.offset })
        assertEquals(Files.size(path), chunks.last().offset)
    }

    @Test
    fun `a multi-byte character across a chunk boundary survives intact`(
        @TempDir tmp: Path,
    ) = runTest {
        val path = tmp.resolve("cmd_0.out")
        // "é" is 2 bytes; its first byte is the last byte of the first 1 MiB read.
        val content = "x".repeat(LogTailService.MAX_READ_BYTES.toInt() - 1) + "é" + "tail"
        path.writeText(content)
        val processManager = processManagerWithoutJob()
        coEvery { processManager.getProcessStatus(1) } returns ProcessStatus.FINISHED

        val chunks =
            LogTailService(processManager, TestFixtures.mockLogger(), pollIntervalMs = 1L)
                .tail(1, path, resumeFromOffset = 0L)
                .toList()
                .filterIsInstance<LogTailService.LogEvent.Chunk>()

        assertTrue(chunks.size >= 2)
        assertTrue(chunks.none { '\uFFFD' in it.content })
        assertEquals(content, chunks.joinToString("") { it.content })
        assertEquals(Files.size(path), chunks.last().offset)
    }

    private fun processInfo(
        id: Int,
        artifactsDir: Path?,
        executions: List<ExecutionInfo> = emptyList(),
    ): ProcessInfo =
        ProcessInfo(
            id = id,
            status = ProcessStatus.WORKING,
            agentName = "test-agent",
            artifactsDir = artifactsDir,
            executions = executions,
        )

    private fun execution(
        stdOut: Path?,
        stdErr: Path?,
        commandIndex: Int? = null,
    ) = ExecutionInfo(
        label = "build",
        start = 0,
        end = 1,
        resultCode = 0,
        stdOut = stdOut,
        stdErr = stdErr,
        errMsg = null,
        commandIndex = commandIndex,
    )
}
