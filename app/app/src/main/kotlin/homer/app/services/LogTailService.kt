package homer.app.services

import homer.app.infra.PathSafety
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import org.slf4j.Logger
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class LogTailService(
    private val processManager: ProcessManager,
    private val logger: Logger,
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
    private val channelFactory: (Path) -> FileChannel = {
        FileChannel.open(it, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
    },
) {
    private val terminalStatuses =
        setOf(
            ProcessStatus.FINISHED,
            ProcessStatus.FAILED,
            ProcessStatus.KILLED,
            // Symmetric with LlmEventStreamService.terminal: a restart-interrupted run (or a
            // deleted row) reports UNKNOWN, and without it here the tail never ends.
            ProcessStatus.UNKNOWN,
        )

    /**
     * [cmdIndex]'s file: the path tracked while the server runs, else its execution row's, else `cmd_N`. Null for an
     * unknown process, a row naming no file, or a path that is not a link-free child of the artifacts dir (ADR-0070).
     */
    suspend fun resolve(
        processId: Int,
        cmdIndex: Int,
        stream: Stream,
    ): Path? {
        val info = processManager.getProcessInfo(processId) ?: return null
        val artifactsDir = info.artifactsDir ?: return null
        // A run can swap its artifacts directory for a symlink, which assertContained follows (CWE-59).
        // Checked once here: a swap before the tail opens the file, at any later poll, is followed (AR-12).
        if (Files.isSymbolicLink(artifactsDir)) return null
        val candidate =
            trackedPath(processId, cmdIndex, stream, artifactsDir)
                ?: persistedOrDefaultPath(info.executions.getOrNull(cmdIndex), cmdIndex, stream, artifactsDir)
        return candidate?.takeIf { isLinkFreeChild(artifactsDir, it) }
    }

    // A tracked path comes from an agent-influenced artifactNameTemplate: one outside the dir is ignored (ADR-0047).
    private suspend fun trackedPath(
        processId: Int,
        cmdIndex: Int,
        stream: Stream,
        artifactsDir: Path,
    ): Path? {
        val tracked = processManager.getCommandArtifacts(processId, cmdIndex) ?: return null
        return (if (stream == Stream.OUT) tracked.stdOut else tracked.stdErr).takeIf { it.parent == artifactsDir }
    }

    /** The path [row] persisted, normalized, or `cmd_N` when the command has no row; null when the row names no file. */
    private fun persistedOrDefaultPath(
        row: ExecutionInfo?,
        cmdIndex: Int,
        stream: Stream,
        artifactsDir: Path,
    ): Path? {
        if (row == null) return artifactsDir.resolve("cmd_$cmdIndex.${if (stream == Stream.OUT) "out" else "err"}")
        val persisted = (if (stream == Stream.OUT) row.stdOut else row.stdErr) ?: return null
        return artifactsDir.resolve(persisted).normalize()
    }

    // CWE-22/CWE-59 (ADR-0070): a persisted path is attacker-written data, so no symlink at the name or on the way.
    private fun isLinkFreeChild(
        artifactsDir: Path,
        path: Path,
    ): Boolean =
        path.parent == artifactsDir &&
            try {
                PathSafety.assertContained(artifactsDir, path)
                PathSafety.assertNoSymlinkComponentsBelow(artifactsDir, path)
                true
            } catch (_: SecurityException) {
                false
            }

    fun tail(
        processId: Int,
        path: Path,
        resumeFromOffset: Long?,
    ): Flow<LogEvent> =
        flow {
            var offset: Long = startOffset(resumeFromOffset)

            var file: FileChannel? = null
            // A refused file stays refused: the tail then only waits for the terminal status.
            var refused = false
            val liveness = RunLiveness(processManager, processId, pollIntervalMs, terminalStatuses)
            try {
                while (true) {
                    if (file == null && !refused && Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        file = open(processId, path)
                        refused = file == null
                    }
                    file?.let { offset = emitNewBytes(it, offset, atEnd = false) }

                    if (liveness.isTerminal()) {
                        file?.let { offset = emitNewBytes(it, offset, atEnd = true) }
                        emit(LogEvent.End)
                        return@flow
                    }

                    delay(pollIntervalMs)
                }
            } finally {
                file?.close()
            }
        }

    /** Where a tail starts: a non-negative [resumeFromOffset], else the file's start. */
    private fun startOffset(resumeFromOffset: Long?): Long =
        if (resumeFromOffset != null && resumeFromOffset >= 0) {
            resumeFromOffset
        } else {
            0L
        }

    // CWE-22/CWE-59 (ADR-0070): the file may appear after resolve, so re-check at open; NOFOLLOW_LINKS
    // on the open fails a symlink swapped in after the check instead of following it.
    private fun open(
        processId: Int,
        path: Path,
    ): FileChannel? {
        if (!PathSafety.isLinkedOrSpecial(path)) {
            try {
                return channelFactory(path)
            } catch (_: IOException) {
                // Falls through to the refusal below.
            }
        }
        logger.warn(
            "log tail refusing a linked or non-regular stdout artifact (process {} artifact {})",
            processId,
            path.fileName.toString(),
        )
        return null
    }

    /**
     * Emits the bytes of [file] past [offset] as chunks of at most [MAX_READ_BYTES] (ADR-0047 rule 4,
     * CWE-400) and returns the offset after the last emitted byte. A chunk ends on a whole UTF-8
     * character; the incomplete tail is re-read next time, except at the file's end when [atEnd].
     */
    private suspend fun FlowCollector<LogEvent>.emitNewBytes(
        file: FileChannel,
        offset: Long,
        atEnd: Boolean,
    ): Long {
        var pos = offset
        while (true) {
            val length = file.size()
            if (length <= pos) return pos
            val buf = readAt(file, pos, length)
            val read = buf.position()
            if (read == 0) return pos
            val whole = emittableLength(buf.array(), read, atEnd && pos + read >= length)
            if (whole == 0) return pos
            pos += whole
            emit(LogEvent.Chunk(pos, String(buf.array(), 0, whole, Charsets.UTF_8)))
        }
    }

    /** Reads at most [MAX_READ_BYTES] of [file] from [pos] towards [length]; the buffer's position is the count read. */
    private fun readAt(
        file: FileChannel,
        pos: Long,
        length: Long,
    ): ByteBuffer {
        val buf = ByteBuffer.allocate(minOf(length - pos, MAX_READ_BYTES).toInt())
        while (buf.hasRemaining() && file.read(buf, pos + buf.position()) >= 0) {}
        return buf
    }

    /** How many of the [read] bytes to emit: all of them at the file's end ([atFileEnd]), else the whole UTF-8 prefix. */
    private fun emittableLength(
        bytes: ByteArray,
        read: Int,
        atFileEnd: Boolean,
    ): Int = if (atFileEnd) read else wholeUtf8Prefix(bytes, read)

    /** Length of [bytes]' first [n] bytes without a trailing incomplete UTF-8 sequence. */
    private fun wholeUtf8Prefix(
        bytes: ByteArray,
        n: Int,
    ): Int {
        var i = n - 1
        // Walk back over at most three continuation bytes (10xxxxxx) to the sequence's lead byte.
        while (i >= 0 && n - i <= 3 && (bytes[i].toInt() and 0xC0) == 0x80) i--
        if (i < 0) return n
        return if (n - i < utf8SequenceLength(bytes[i].toInt() and 0xFF)) i else n
    }

    /** The byte length of the UTF-8 sequence [lead] starts; 1 for a byte that leads none. */
    private fun utf8SequenceLength(lead: Int): Int =
        when {
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }

    enum class Stream { OUT, ERR }

    sealed class LogEvent {
        data class Chunk(
            val offset: Long,
            val content: String,
        ) : LogEvent()

        data object End : LogEvent()
    }

    companion object {
        const val DEFAULT_POLL_MS = 500L
        const val MAX_READ_BYTES = 1L * 1024 * 1024
    }
}
