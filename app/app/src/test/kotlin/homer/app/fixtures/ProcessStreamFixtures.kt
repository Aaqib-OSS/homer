package homer.app.fixtures

import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.TokenConfig
import homer.app.routes.ApiDeps
import homer.app.services.ProcessEvent
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.assertTrue

/** The processes one `/stream/processes` visibility case broadcasts: pid → (agent name, owner). */
private val streamedProcesses: Map<Int, Pair<String, String?>> =
    mapOf(
        1 to ("deploy" to null),
        2 to ("other" to "mallory"),
    )

private const val SENTINEL_QUESTION = "stream-sentinel"

/**
 * One `GET /stream/processes` connection as [bearer]: delivers exactly the frames of [delivered], never one of
 * [skipped], no line carrying an [absent] fragment (ADR-0038). The events — a `process.status` per known process
 * plus [extraEvents] — ride a repeating timer until the sentinel is observed: a fixed delay races the subscription.
 */
internal fun assertStreamedProcessFrames(
    tokens: Map<String, TokenConfig>,
    bearer: String,
    delivered: Set<Int>,
    skipped: Set<Int> = emptySet(),
    extraEvents: List<ProcessEvent> = emptyList(),
    absent: List<String> = emptyList(),
) = testApplication {
    val broadcaster = ProcessEventBroadcaster()
    val pm =
        mockk<ProcessManager>(relaxed = true) {
            streamedProcesses.forEach { (pid, process) ->
                coEvery { getProcessInfo(pid) } returns
                    ProcessInfo(id = pid, status = ProcessStatus.WORKING, agentName = process.first, owner = process.second)
            }
        }
    val deps =
        mockk<ApiDeps>(relaxed = true) {
            every { authorizer } returns authorizerFor(tokens)
            every { processManager } returns pm
            every { processEventBroadcaster } returns broadcaster
        }
    application { installGrantsApp(tokens, deps) }

    // The sentinel question rides a delivered process and goes last, so a skipped event before it
    // must not end the stream.
    val sentinelPid = delivered.filter { it in streamedProcesses }.min()
    val events =
        streamedProcesses.map { (pid, process) -> ProcessEvent.StatusChanged(pid, ProcessStatus.WORKING, process.first) } +
            extraEvents +
            ProcessEvent.QuestionAsked(sentinelPid, SENTINEL_QUESTION, streamedProcesses.getValue(sentinelPid).first)

    client
        .prepareGet("/api/v1/stream/processes") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
        }.execute { response ->
            coroutineScope {
                val trigger =
                    launch {
                        while (isActive) {
                            events.forEach { broadcaster.emit(it) }
                            delay(50)
                        }
                    }
                val channel = response.bodyAsChannel()
                val lines = mutableListOf<String>()
                withTimeout(10_000) {
                    while (true) {
                        val line = channel.readLine() ?: break
                        lines += line
                        // Break on a sentinel that follows every delivered frame already read: a
                        // subscription landing mid-pass must not stop the read a frame short.
                        if (SENTINEL_QUESTION in line && delivered.all { pid -> lines.any { "\"processId\":$pid" in it } }) break
                    }
                }
                trigger.cancelAndJoin()
                for (pid in delivered) {
                    assertTrue(lines.any { "\"processId\":$pid" in it }, "process $pid is delivered: $lines")
                }
                for (pid in skipped) {
                    assertTrue(lines.none { "\"processId\":$pid" in it }, "process $pid is skipped: $lines")
                }
                for (fragment in absent) {
                    assertTrue(lines.none { fragment in it }, "no line carries $fragment: $lines")
                }
            }
        }
}
