package homer.app.services

import homer.app.executors.CommandArtifactPaths
import homer.app.executors.llm.LlmHarness
import homer.app.infra.DescriptorOpen
import homer.app.infra.LlmOutputJson
import homer.app.infra.PathSafety
import homer.app.infra.SecretRedactor
import homer.app.infra.readAt
import homer.app.models.LlmEvent
import homer.app.models.LlmEventJson
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.Translation
import homer.app.models.forCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import org.slf4j.Logger
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * SSE resume position: the [eventIndex]-th event translated from the stdout line that
 * starts at byte [lineOffset] of one attempt. One line can yield several events.
 */
data class StreamCursor(
    val cmdIndex: Int,
    val attemptIndex: Int,
    val lineOffset: Long,
    val eventIndex: Int,
) {
    val id: String get() = "$cmdIndex:$attemptIndex:$lineOffset:$eventIndex"

    companion object {
        fun parse(id: String?): StreamCursor? {
            val parts = id?.split(':') ?: return null
            if (parts.size != 4) return null
            val c = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val a = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val o = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val e = parts[3].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return StreamCursor(c, a, o, e)
        }
    }
}

sealed class LlmStreamEvent {
    data class Event(
        val cmdIndex: Int,
        val attemptIndex: Int,
        // Byte position of the start of the line this event was translated from.
        val lineOffset: Long,
        // 0-based index among that line's translated events.
        val eventIndex: Int,
        val event: LlmEvent,
    ) : LlmStreamEvent()

    data object End : LlmStreamEvent()
}

data class LlmArtifact(
    val path: Path,
    val kind: String,
    val attemptIndex: Int,
    val redactor: SecretRedactor,
)

/** The restart path's rows for one command: its own row, and whether a later command has one. */
private data class PersistedRows(
    val own: Boolean,
    val later: Boolean,
)

/**
 * [cmdIndex]'s persisted execution row and whether a later command has one, both from one read —
 * the restart path's only evidence, the run's job being gone. Null once the process is gone.
 */
private suspend fun persistedRows(
    processManager: ProcessManager,
    processId: Int,
    cmdIndex: Int,
    readDb: Boolean,
): PersistedRows? =
    if (!readDb) {
        PersistedRows(own = false, later = false)
    } else {
        processManager.getProcessInfo(processId)?.let { info ->
            PersistedRows(
                own = info.executions.forCommand(cmdIndex) != null,
                later = info.executions.any { (it.commandIndex ?: -1) > cmdIndex },
            )
        }
    }

/** Whether this tick reads the persisted rows: never for a tracked command, else on the safety cadence. */
private fun readPersisted(
    tracked: Boolean,
    liveness: RunLiveness,
): Boolean = !tracked && liveness.readDue()

/**
 * Per-subscriber tail of an LLM harness command's stdout, translated through its
 * adapter and redacted. A late subscriber gets the whole stream from byte 0, and
 * `Last-Event-ID` resumes. See ADR-0070.
 */
class LlmEventStreamService(
    private val processManager: ProcessManager,
    private val harnesses: Map<String, LlmHarness<*>>,
    private val logger: Logger,
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
    private val channelFactory: (Path) -> SeekableByteChannel = DescriptorOpen::openRegularChannel,
) {
    private val terminal =
        setOf(
            ProcessStatus.FINISHED,
            ProcessStatus.FAILED,
            ProcessStatus.KILLED,
            // A restart-interrupted run (or a deleted row) reports UNKNOWN; without it here
            // the stream never emits llm.end and holds an SSE slot open forever.
            ProcessStatus.UNKNOWN,
        )

    /** The LLM artifact for (process, cmd), or null when the command is not an LLM harness command or its path cannot be trusted. */
    suspend fun resolve(
        processId: Int,
        cmdIndex: Int,
    ): LlmArtifact? {
        val info = processManager.getProcessInfo(processId) ?: return null
        val dir = info.artifactsDir ?: return null
        // A run can swap its artifacts directory for a symlink, which assertContained follows (CWE-59).
        // Checked once here: a swap before the stream opens the file, at any later poll, is followed (AR-12).
        if (Files.isSymbolicLink(dir)) return null
        val tracked = processManager.getCommandArtifacts(processId, cmdIndex)
        if (tracked != null) return trackedArtifact(dir, tracked)
        // Not tracked (restart since the run): the persisted execution row.
        return persistedArtifact(info, dir, cmdIndex)
    }

    private fun trackedArtifact(
        dir: Path,
        tracked: CommandArtifactPaths,
    ): LlmArtifact? {
        val kind = tracked.kind?.takeIf { it in harnesses } ?: return null
        // Containment guard at the point of use (ADR-0047 rule 2), as LogTailService.resolve.
        if (tracked.stdOut.parent != dir || !isContained(dir, tracked.stdOut)) return null
        return LlmArtifact(tracked.stdOut, kind, tracked.attemptIndex, tracked.redactor)
    }

    private fun persistedArtifact(
        info: ProcessInfo,
        dir: Path,
        cmdIndex: Int,
    ): LlmArtifact? {
        val row = info.executions.forCommand(cmdIndex) ?: return null
        if (row.kind !in harnesses) return null
        val path = dir.resolve(row.stdOut ?: return null).normalize()
        if (path.parent != dir || !isContained(dir, path)) return null
        return LlmArtifact(path, row.kind, 0, fallbackRedactor(info))
    }

    // CWE-22/CWE-59 (ADR-0070): no symlink at the artifact name or on the way to it.
    private fun isContained(
        dir: Path,
        path: Path,
    ): Boolean =
        try {
            PathSafety.assertContained(dir, path)
            PathSafety.assertNoSymlinkComponentsBelow(dir, path)
            true
        } catch (_: SecurityException) {
            false
        }

    // The run's redactor is in-memory only; after a restart mask the stored secret input values.
    private fun fallbackRedactor(info: ProcessInfo): SecretRedactor =
        SecretRedactor.from(info.inputParams, info.secretInputKeys.orEmpty(), hostEnv = { null })

    /**
     * Follows one command's stdout from byte 0 (or [resume]), re-targeting each newer
     * attempt, until a later command registers or the process is terminal; then emits
     * [LlmStreamEvent.End].
     */
    fun streamCommand(
        processId: Int,
        cmdIndex: Int,
        resume: StreamCursor?,
    ): Flow<LlmStreamEvent> =
        flow {
            followCommands(processId, cmdIndex, cmdIndex, resume)
            emit(LlmStreamEvent.End)
        }

    /** Follows every LLM harness command of the process in order, from [resume]'s command or command 0. */
    fun streamProcess(
        processId: Int,
        resume: StreamCursor?,
    ): Flow<LlmStreamEvent> =
        flow {
            followCommands(processId, resume?.cmdIndex ?: 0, Int.MAX_VALUE, resume)
            emit(LlmStreamEvent.End)
        }

    /**
     * Follows the LLM commands [firstCmd]..[lastCmd] in order, skipping non-LLM ones. A
     * command's file ends (after a final drain) when a newer attempt of it registers,
     * which is then followed from byte 0, or when a later command registers.
     */
    private suspend fun FlowCollector<LlmStreamEvent>.followCommands(
        processId: Int,
        firstCmd: Int,
        lastCmd: Int,
        resume: StreamCursor?,
    ) {
        var cmdIndex = firstCmd
        var cursor = resume?.takeIf { it.cmdIndex == firstCmd }
        val liveness = RunLiveness(processManager, processId, pollIntervalMs, terminal)
        while (cmdIndex <= lastCmd) {
            val tracked = processManager.getCommandArtifacts(processId, cmdIndex) != null
            // A live run registers each command's artifacts before running it, so the persisted
            // executions (the restart path) are read only when no job is live or on the safety read.
            val readDb = readPersisted(tracked, liveness)
            val persisted = persistedRows(processManager, processId, cmdIndex, readDb) ?: return
            if (tracked || persisted.own) {
                cmdIndex = followRecorded(processId, cmdIndex, cursor, liveness)
            } else if (laterCommandStarted(processId, cmdIndex, persisted.later)) {
                // A run restricted to some commands records nothing for the ones it skips.
                cmdIndex++
            } else if (!liveness.isTerminal(readDb)) {
                delay(pollIntervalMs)
                continue
            } else {
                return
            }
            cursor = null
        }
    }

    /**
     * Follows a recorded command's artifact from [cursor] when it is that attempt's; returns
     * [cmdIndex] again when a newer attempt took over, else the next command's index.
     */
    private suspend fun FlowCollector<LlmStreamEvent>.followRecorded(
        processId: Int,
        cmdIndex: Int,
        cursor: StreamCursor?,
        liveness: RunLiveness,
    ): Int {
        val artifact = resolve(processId, cmdIndex) ?: return cmdIndex + 1
        val from = cursor?.takeIf { it.attemptIndex == artifact.attemptIndex }
        followFile(processId, cmdIndex, artifact, liveness, from?.lineOffset ?: 0L, from?.eventIndex ?: -1) {
            newerAttemptStarted(processId, cmdIndex, artifact.attemptIndex) || laterCommandStarted(processId, cmdIndex)
        }
        return if (newerAttemptStarted(processId, cmdIndex, artifact.attemptIndex)) cmdIndex else cmdIndex + 1
    }

    private suspend fun newerAttemptStarted(
        processId: Int,
        cmdIndex: Int,
        attemptIndex: Int,
    ): Boolean = (processManager.getCommandArtifacts(processId, cmdIndex)?.attemptIndex ?: attemptIndex) > attemptIndex

    private fun laterCommandStarted(
        processId: Int,
        cmdIndex: Int,
        // A run whose job is gone (a restart) has no in-memory registration: its persisted rows
        // are the only evidence that it moved past [cmdIndex].
        persistedLater: Boolean = false,
    ): Boolean = persistedLater || (processManager.latestCommandArtifacts(processId)?.first ?: -1) > cmdIndex

    /**
     * Emits the translated events of every complete line of [artifact] from [startOffset], skipping events
     * <= [skipThroughEventIndex] of the line starting there. Polls until the run ends or [stopWhen] holds,
     * then drains the file and returns the offset after the last consumed newline.
     */
    internal suspend fun FlowCollector<LlmStreamEvent>.followFile(
        processId: Int,
        cmdIndex: Int,
        artifact: LlmArtifact,
        liveness: RunLiveness,
        startOffset: Long,
        skipThroughEventIndex: Int = -1,
        stopWhen: suspend () -> Boolean = { false },
    ): Long {
        val tail = LineTail(processId, cmdIndex, artifact, startOffset, skipThroughEventIndex)
        var file: SeekableByteChannel? = null
        // A refused file stays refused: the follow then only waits for its usual end.
        var refused = false
        val openIfPresent = {
            if (file == null && !refused && Files.exists(artifact.path, LinkOption.NOFOLLOW_LINKS)) {
                file = open(processId, cmdIndex, artifact)
                refused = file == null
            }
        }
        try {
            while (true) {
                openIfPresent()
                file?.let { tail.drain(this, it) }
                if (liveness.isTerminal() || stopWhen()) {
                    // The writer may have flushed between the drain and the status check.
                    openIfPresent()
                    file?.let { tail.drain(this, it) }
                    tail.dropTrailingPartial()
                    return tail.lineStart
                }
                delay(pollIntervalMs)
            }
        } finally {
            file?.close()
        }
    }

    // CWE-22/CWE-59 (ADR-0070): the file may be swapped after resolve, so the open checks the descriptor itself and
    // refuses a symlink, hard link, FIFO or other non-regular file without blocking.
    private fun open(
        processId: Int,
        cmdIndex: Int,
        artifact: LlmArtifact,
    ): SeekableByteChannel? {
        try {
            return channelFactory(artifact.path)
        } catch (_: IOException) {
            // Falls through to the refusal below.
        }
        logger.warn(
            "llm stream refusing a linked or non-regular stdout artifact (process {} cmd {} attempt {})",
            processId,
            cmdIndex,
            artifact.attemptIndex,
        )
        return null
    }

    /**
     * Splits bytes into lines without re-reading: [readPos] is how far the file has been
     * read, [lineStart] the position after the last consumed newline. A line longer than
     * [MAX_LINE_BYTES] is dropped whole (ADR-0047 rule 4: the held buffer is per subscriber).
     */
    private inner class LineTail(
        private val processId: Int,
        private val cmdIndex: Int,
        private val artifact: LlmArtifact,
        // A translator that keeps state also reads the lines before it, never emitting or warning about them (ADR-0074).
        private val resumeFrom: Long,
        // Applies only to the line starting exactly at [resumeFrom]: the resumed line's already-delivered events.
        private val skipThroughEventIndex: Int,
    ) {
        private val harness = harnesses.getValue(artifact.kind)
        private val translate = harness.translator()
        private val held = ByteArrayOutputStream()
        private var readPos = if (harness.translatorKeepsState) 0L else resumeFrom
        var lineStart = readPos
            private set

        // Inside a line already over MAX_LINE_BYTES: discard until its newline.
        private var skipping = false

        // Once per tail: a mask that breaks one event usually breaks every one.
        private var maskDropLogged = false

        suspend fun drain(
            collector: FlowCollector<LlmStreamEvent>,
            file: SeekableByteChannel,
        ) {
            while (true) {
                val length = file.size()
                if (length <= readPos) return
                val buf = ByteBuffer.allocate(minOf(length - readPos, MAX_READ_BYTES).toInt())
                while (buf.hasRemaining() && file.readAt(buf, readPos + buf.position()) >= 0) {}
                // Truncated under us: nothing more to read until it grows again.
                if (buf.position() == 0) return
                val chunkStart = readPos
                readPos += buf.position()
                consume(collector, if (buf.hasRemaining()) buf.array().copyOf(buf.position()) else buf.array(), chunkStart)
            }
        }

        private suspend fun consume(
            collector: FlowCollector<LlmStreamEvent>,
            buf: ByteArray,
            chunkStart: Long,
        ) {
            var i = 0
            while (i < buf.size) {
                val nl = buf.indexOfByte(NEWLINE, i)
                val segmentEnd = if (nl < 0) buf.size else nl
                if (!skipping && held.size().toLong() + (segmentEnd - i) > MAX_LINE_BYTES) startDroppingLine()
                if (!skipping) held.write(buf, i, segmentEnd - i)
                if (nl < 0) return
                endLine(collector, chunkStart + nl + 1)
                i = nl + 1
            }
        }

        private fun startDroppingLine() {
            held.reset()
            skipping = true
            if (lineStart < resumeFrom) return
            logger.warn(
                "llm stream dropping a line over {} bytes (process {} cmd {} attempt {})",
                MAX_LINE_BYTES,
                processId,
                cmdIndex,
                artifact.attemptIndex,
            )
        }

        /** Ends the held line at [nextLineStart]; its events are emitted only when it starts at or past [resumeFrom]. */
        private suspend fun endLine(
            collector: FlowCollector<LlmStreamEvent>,
            nextLineStart: Long,
        ) {
            val thisLineStart = lineStart
            lineStart = nextLineStart
            val resumed = thisLineStart >= resumeFrom
            val skipThrough = if (thisLineStart == resumeFrom) skipThroughEventIndex else -1
            val events = if (skipping) emptyList() else translateLine(held.toString(Charsets.UTF_8))
            skipping = false
            held.reset()
            if (!resumed) return
            redact(events).forEachIndexed { index, event ->
                if (index > skipThrough) {
                    collector.emit(LlmStreamEvent.Event(cmdIndex, artifact.attemptIndex, thisLineStart, index, event))
                }
            }
        }

        fun dropTrailingPartial() {
            if (held.size() > 0) {
                logger.warn(
                    "llm stream dropping a trailing partial line (process {} cmd {} attempt {})",
                    processId,
                    cmdIndex,
                    artifact.attemptIndex,
                )
            }
            held.reset()
        }

        private fun translateLine(rawLine: String): List<LlmEvent> {
            if (rawLine.isBlank()) return emptyList()
            val obj =
                try {
                    LlmOutputJson.parseToJsonElement(rawLine) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } ?: return emptyList()
            return (translate(obj) as? Translation.Events)?.events.orEmpty()
        }

        // Redact the decoded event, not the raw line: a \uXXXX-escaped secret is cleartext
        // only after parsing (CWE-200). An event the mask breaks is dropped. See ADR-0070.
        private fun redact(events: List<LlmEvent>): List<LlmEvent> =
            events.mapNotNull { event ->
                val redacted = redactEvent(event, artifact.redactor)
                if (redacted == null) logMaskDrop()
                redacted
            }

        private fun logMaskDrop() {
            if (maskDropLogged) return
            maskDropLogged = true
            logger.warn(
                "llm stream dropping an event the secret mask made undecodable (process {} cmd {} attempt {}); " +
                    "further drops on this stream are not logged",
                processId,
                cmdIndex,
                artifact.attemptIndex,
            )
        }
    }

    private fun redactEvent(
        event: LlmEvent,
        redactor: SecretRedactor,
    ): LlmEvent? =
        try {
            val masked = redactor.redact(LlmEventJson.encodeToString(LlmEvent.serializer(), event))
            LlmEventJson.decodeFromString(LlmEvent.serializer(), masked)
        } catch (_: IllegalArgumentException) {
            // SerializationException included: the mask hit the event's own structure.
            null
        }

    private fun ByteArray.indexOfByte(
        b: Byte,
        from: Int,
    ): Int {
        for (j in from until size) if (this[j] == b) return j
        return -1
    }

    companion object {
        const val DEFAULT_POLL_MS = 500L
        const val MAX_READ_BYTES = 1L * 1024 * 1024
        const val MAX_LINE_BYTES = 4L * 1024 * 1024
        private const val NEWLINE = '\n'.code.toByte()
    }
}
