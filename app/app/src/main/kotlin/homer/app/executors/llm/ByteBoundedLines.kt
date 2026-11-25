package homer.app.executors.llm

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/**
 * The file's UTF-8 lines as a lazy [Sequence], bounded while it reads: a line over [maxLineBytes] is never
 * buffered past that bound and comes out blank, and the read stops once more than [maxBytes] bytes were read.
 */
internal class ByteBoundedLines(
    path: Path,
    private val maxLineBytes: Int,
    private val maxBytes: Long,
    chunkBytes: Int = CHUNK_BYTES,
) : Closeable {
    private val input = Files.newInputStream(path)
    private val chunk = ByteArray(chunkBytes)
    private var pos = 0
    private var end = 0
    private var readBytes = 0L
    private val buffer = ByteArrayOutputStream()
    private var lineBytes = 0L

    /** Lines that went over [maxLineBytes] and came out blank. */
    var skippedLines = 0
        private set

    /** True once the read stopped at [maxBytes]; the line it was reading is dropped. */
    var cut = false
        private set

    val lines: Sequence<String> = generateSequence { nextLine() }

    private fun nextLine(): String? {
        buffer.reset()
        lineBytes = 0L
        while (pos < end || fill()) {
            if (takeToNewline()) return finishLine()
        }
        return if (cut || lineBytes == 0L) null else finishLine()
    }

    /** Takes the chunk's bytes up to the next newline, keeping at most [maxLineBytes] of the line; true once it took one. */
    private fun takeToNewline(): Boolean {
        var stop = pos
        while (stop < end && chunk[stop] != NEWLINE) stop++
        val keep = minOf(stop - pos.toLong(), maxLineBytes - lineBytes).toInt()
        if (keep > 0) buffer.write(chunk, pos, keep)
        lineBytes += stop - pos
        pos = minOf(stop + 1, end)
        return stop < end
    }

    /** Reads the next chunk, never past [maxBytes]; at the bound, one byte more decides [cut]. */
    private fun fill(): Boolean {
        val room = minOf(chunk.size.toLong(), maxBytes - readBytes).toInt()
        if (room == 0) {
            cut = input.read() != -1
            return false
        }
        val n = input.read(chunk, 0, room)
        if (n <= 0) return false
        pos = 0
        end = n
        readBytes += n
        return true
    }

    private fun finishLine(): String {
        if (lineBytes <= maxLineBytes) return buffer.toString(Charsets.UTF_8)
        skippedLines++
        return ""
    }

    override fun close() {
        input.close()
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
        const val CHUNK_BYTES = 8 * 1024
    }
}
