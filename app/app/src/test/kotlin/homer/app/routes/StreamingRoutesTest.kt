package homer.app.routes

import homer.app.exceptions.BodylessBadRequestException
import homer.app.fixtures.TestFixtures
import homer.app.fixtures.mountApiV1Routes
import homer.app.infra.SecretRedactor
import homer.app.models.ExecutionInfo
import homer.app.models.LlmEvent
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.LlmArtifact
import homer.app.services.LlmEventStreamService
import homer.app.services.LlmStreamEvent
import homer.app.services.LogTailService
import homer.app.services.ProcessEvent
import homer.app.services.ProcessManager
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.io.path.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingRoutesTest {
    @Test
    fun `streamedProcessId names the process of every event the processes stream carries`() {
        assertEquals(1, streamedProcessId(ProcessEvent.StatusChanged(1, ProcessStatus.WORKING, "a")))
        assertEquals(2, streamedProcessId(ProcessEvent.CurrentCommandChanged(2, "build", 10L)))
        assertEquals(3, streamedProcessId(ProcessEvent.QuestionAsked(3, "q", "a")))
        assertEquals(4, streamedProcessId(ProcessEvent.QuestionAnswered(4, "q")))
        assertEquals(5, streamedProcessId(ProcessEvent.QuestionExpired(5, "q")))
        assertEquals(6, streamedProcessId(ProcessEvent.MessageQueued(6, "m")))
        assertEquals(7, streamedProcessId(ProcessEvent.MessageDelivered(7, "m")))
        assertEquals(8, streamedProcessId(ProcessEvent.MessageUndelivered(8, "m")))
        assertNull(streamedProcessId(ProcessEvent.AgentsReloaded(listOf("a"), emptyList(), emptyList())))
    }

    @Test
    fun `the visibility memo never holds more than its cap, evicting the least recently read process`() {
        val memo = visibilityMemo()
        repeat(MAX_VISIBILITY_MEMO_ENTRIES) { memo[it] = "owner" to "agent" }
        memo[0]

        memo[MAX_VISIBILITY_MEMO_ENTRIES] = "owner" to "agent"

        assertEquals(MAX_VISIBILITY_MEMO_ENTRIES, memo.size)
        assertTrue(0 in memo)
        assertFalse(1 in memo)
    }

    @Test
    fun `processEventFrame renders each processes-stream event`() {
        assertEquals(
            SseFrame("process.status", """{"processId":1,"status":"WORKING","agentName":"a"}"""),
            processEventFrame(ProcessEvent.StatusChanged(1, ProcessStatus.WORKING, "a")),
        )
        assertEquals(
            SseFrame("process.cmd", """{"processId":2,"label":"build","startedAt":10}"""),
            processEventFrame(ProcessEvent.CurrentCommandChanged(2, "build", 10L)),
        )
        assertEquals(
            SseFrame("question.asked", """{"processId":3,"questionId":"q","agentName":"a"}"""),
            processEventFrame(ProcessEvent.QuestionAsked(3, "q", "a")),
        )
        assertEquals(
            SseFrame("question.answered", """{"processId":4,"questionId":"q"}"""),
            processEventFrame(ProcessEvent.QuestionAnswered(4, "q")),
        )
        assertEquals(
            SseFrame("question.expired", """{"processId":5,"questionId":"q"}"""),
            processEventFrame(ProcessEvent.QuestionExpired(5, "q")),
        )
        assertEquals(
            SseFrame("message.queued", """{"processId":6,"messageId":"m"}"""),
            processEventFrame(ProcessEvent.MessageQueued(6, "m")),
        )
        assertEquals(
            SseFrame("message.delivered", """{"processId":7,"messageId":"m"}"""),
            processEventFrame(ProcessEvent.MessageDelivered(7, "m")),
        )
        assertEquals(
            SseFrame("message.undelivered", """{"processId":8,"messageId":"m"}"""),
            processEventFrame(ProcessEvent.MessageUndelivered(8, "m")),
        )
        assertNull(processEventFrame(ProcessEvent.AgentsReloaded(emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun `a cleared current command renders null label and start`() {
        assertEquals(
            SseFrame("process.cmd", """{"processId":2,"label":null,"startedAt":null}"""),
            processEventFrame(ProcessEvent.CurrentCommandChanged(2, null, null)),
        )
    }

    @Test
    fun `logStream defaults to out and accepts out or err in any case`() {
        assertEquals(LogTailService.Stream.OUT, logStream(null))
        assertEquals(LogTailService.Stream.OUT, logStream("OUT"))
        assertEquals(LogTailService.Stream.ERR, logStream("Err"))
        assertNull(logStream("bogus"))
        assertNull(logStream(""))
    }

    @Test
    fun `logStream names a stream only for out or err`() =
        runTest {
            checkAll(Arb.string(0..8)) { param ->
                val expected =
                    when (param.lowercase()) {
                        "out" -> LogTailService.Stream.OUT
                        "err" -> LogTailService.Stream.ERR
                        else -> null
                    }
                assertEquals(expected, logStream(param), "param: $param")
            }
        }

    @Test
    fun `logEventFrame carries a chunk's offset as its id and ends with an empty frame`() {
        assertEquals(
            SseFrame("log.chunk", """{"content":"hi\n"}""", id = "42"),
            logEventFrame(LogTailService.LogEvent.Chunk(offset = 42L, content = "hi\n")),
        )
        assertEquals(SseFrame("log.end", "{}"), logEventFrame(LogTailService.LogEvent.End))
    }

    @Test
    fun `optionalCmdIndex is null when absent and rejects a non-index`() {
        assertNull(optionalCmdIndex(null))
        assertEquals(0, optionalCmdIndex("0"))
        assertEquals(7, optionalCmdIndex("7"))
        assertFailsWith<BodylessBadRequestException> { optionalCmdIndex("-1") }
        assertFailsWith<BodylessBadRequestException> { optionalCmdIndex("x") }
        assertFailsWith<BodylessBadRequestException> { optionalCmdIndex("") }
    }

    @Test
    fun `optionalCmdIndex returns exactly the non-negative integers`() =
        runTest {
            checkAll(Arb.int()) { n ->
                if (n >= 0) {
                    assertEquals(n, optionalCmdIndex(n.toString()))
                } else {
                    assertFailsWith<BodylessBadRequestException> { optionalCmdIndex(n.toString()) }
                }
            }
            checkAll(Arb.string(0..8)) { param ->
                val parsed = param.toIntOrNull()
                if (parsed != null && parsed >= 0) {
                    assertEquals(parsed, optionalCmdIndex(param), "param: $param")
                } else {
                    assertFailsWith<BodylessBadRequestException>("param: $param") { optionalCmdIndex(param) }
                }
            }
        }

    @Test
    fun `llmEventFrame carries the stream cursor as its id and ends with an empty frame`() {
        val frame =
            llmEventFrame(
                7,
                LlmStreamEvent.Event(
                    cmdIndex = 1,
                    attemptIndex = 2,
                    lineOffset = 30L,
                    eventIndex = 4,
                    event = LlmEvent.TransientError("slow down"),
                ),
            )
        assertEquals("llm.event", frame.name)
        assertEquals("1:2:30:4", frame.id)
        // The payload is the serialized frame, byte for byte: field order, the `type`
        // discriminator and the nested event all as the client reads them.
        assertEquals(
            """{"processId":7,"cmdIndex":1,"attemptIndex":2,"event":{"type":"transient_error","message":"slow down"}}""",
            frame.data,
        )
        assertEquals(SseFrame("llm.end", "{}"), llmEventFrame(7, LlmStreamEvent.End))
    }

    @Test
    fun `process logs stream defaults cmd to zero and honours an explicit index`() =
        testApplication {
            val resolved = mutableListOf<Pair<Int, LogTailService.Stream>>()
            val tailService =
                mockk<LogTailService>(relaxed = true) {
                    coEvery { resolve(any(), any(), any()) } answers {
                        resolved += secondArg<Int>() to thirdArg<LogTailService.Stream>()
                        // Null answers 404, so the handler never opens an SSE body.
                        null
                    }
                }
            val pm =
                mockk<ProcessManager>(relaxed = true) {
                    coEvery { getProcessInfo(42) } returns
                        ProcessInfo(id = 42, status = ProcessStatus.FINISHED, agentName = "deploy", owner = "alice")
                }
            mountApiV1Routes {
                every { processManager } returns pm
                every { logTailService } returns tailService
            }

            for (query in listOf("", "?cmd=3")) {
                val response = client.get("/api/v1/stream/processes/42/logs$query")
                assertEquals(HttpStatusCode.NotFound, response.status, "query=$query")
            }

            assertEquals(listOf(0 to LogTailService.Stream.OUT, 3 to LogTailService.Stream.OUT), resolved)
        }

    @Test
    fun `the logs stream serves the file of the command the cmd index names`(
        @TempDir dir: java.nio.file.Path,
    ) = testApplication {
        // A run that executes a subset of its commands records each row's own index, so the row
        // at position 0 is command 2's: `cmd=0` must not serve command 2's output.
        dir.resolve("cmd_2.out").writeText("command two\n")
        val pm =
            mockk<ProcessManager>(relaxed = true) {
                every { hasLiveJob(any()) } returns false
                coEvery { getCommandArtifacts(any(), any()) } returns null
                coEvery { getProcessStatus(42) } returns ProcessStatus.FINISHED
                coEvery { getProcessInfo(42) } returns
                    ProcessInfo(
                        id = 42,
                        status = ProcessStatus.FINISHED,
                        agentName = "deploy",
                        owner = "alice",
                        artifactsDir = dir,
                        executions =
                            listOf(
                                ExecutionInfo(
                                    label = "two",
                                    start = 0,
                                    end = 1,
                                    resultCode = 0,
                                    stdOut = Path("cmd_2.out"),
                                    stdErr = null,
                                    errMsg = null,
                                    commandIndex = 2,
                                ),
                            ),
                    )
            }
        mountApiV1Routes {
            every { processManager } returns pm
            every { logTailService } returns LogTailService(pm, TestFixtures.mockLogger(), pollIntervalMs = 1L)
        }

        val served = client.get("/api/v1/stream/processes/42/logs?cmd=2").bodyAsText()
        assertTrue(served.contains("command two"), "cmd=2 names command 2: $served")

        // Command 0 and the indexes the run never executed recorded no row, so their `cmd_N`
        // defaults are tried and serve nothing: no cmd value reaches the row at position 0.
        for (query in listOf("", "?cmd=0", "?cmd=-1", "?cmd=x")) {
            val other = client.get("/api/v1/stream/processes/42/logs$query").bodyAsText()
            assertFalse(other.contains("command two"), "query=$query must not serve command 2's output: $other")
        }
    }

    @Test
    fun `the llm-events stream serves the command the cmd index names and refuses one the run did not execute`() =
        testApplication {
            // The same debug-run record: the row at position 0 is command 2's, so `cmd=0` names a
            // command the run did not execute and must not fall back to that position.
            val llmService =
                mockk<LlmEventStreamService>(relaxed = true) {
                    coEvery { resolve(42, 2) } returns LlmArtifact(Path("cmd_2.out"), "codex", 0, SecretRedactor.NONE)
                    coEvery { resolve(42, 0) } returns null
                    every { streamCommand(42, 2, any()) } returns
                        flowOf(
                            LlmStreamEvent.Event(2, 0, 0L, 0, LlmEvent.SessionStart("s", null)),
                            LlmStreamEvent.End,
                        )
                }
            val pm =
                mockk<ProcessManager>(relaxed = true) {
                    coEvery { getProcessInfo(42) } returns
                        ProcessInfo(
                            id = 42,
                            status = ProcessStatus.FINISHED,
                            agentName = "deploy",
                            owner = "alice",
                            executions =
                                listOf(
                                    ExecutionInfo(
                                        label = "two",
                                        start = 0,
                                        end = 1,
                                        resultCode = 0,
                                        stdOut = Path("cmd_2.out"),
                                        stdErr = null,
                                        errMsg = null,
                                        commandIndex = 2,
                                    ),
                                ),
                        )
                }
            mountApiV1Routes {
                every { processManager } returns pm
                every { llmEventStreamService } returns llmService
            }

            val served = client.get("/api/v1/stream/processes/42/llm-events?cmd=2").bodyAsText()
            assertTrue(served.contains("llm.event"), "cmd=2 names command 2: $served")
            assertTrue(served.contains("llm.end"), "the stream of a finished command ends: $served")

            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/v1/stream/processes/42/llm-events?cmd=0").status,
                "cmd=0 names a command the run did not execute",
            )
            // Unlike `/logs`, whose `cmd` defaults a non-integer to 0, this stream rejects one.
            for (bad in listOf("-1", "x")) {
                assertEquals(
                    HttpStatusCode.BadRequest,
                    client.get("/api/v1/stream/processes/42/llm-events?cmd=$bad").status,
                    "cmd=$bad",
                )
            }
        }

    @Test
    fun `process logs stream resumes from a Last-Event-ID past the Int range and drops a malformed one`() =
        testApplication {
            val offsets = mutableListOf<Long?>()
            val tailService =
                mockk<LogTailService>(relaxed = true) {
                    coEvery { resolve(any(), any(), any()) } returns Path("/tmp/cmd_0.out")
                    every { tail(any(), any(), any()) } answers {
                        offsets += thirdArg<Long?>()
                        emptyFlow()
                    }
                }
            val pm =
                mockk<ProcessManager>(relaxed = true) {
                    coEvery { getProcessInfo(42) } returns
                        ProcessInfo(id = 42, status = ProcessStatus.FINISHED, agentName = "deploy", owner = "alice")
                }
            mountApiV1Routes {
                every { processManager } returns pm
                every { logTailService } returns tailService
            }

            // The resume offset is a Long, so an offset past 2 GB reaches the tail uncut; a
            // malformed header is no resume at all, never a truncated offset.
            for (lastId in listOf("${Int.MAX_VALUE + 1L}", "not-a-number")) {
                val response =
                    client.get("/api/v1/stream/processes/42/logs?cmd=0") {
                        header("Last-Event-ID", lastId)
                    }
                assertEquals(HttpStatusCode.OK, response.status, "Last-Event-ID=$lastId")
            }

            assertEquals(listOf(Int.MAX_VALUE + 1L, null), offsets)
        }
}
