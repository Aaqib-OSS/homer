package homer.app.executors.llm

import java.io.BufferedInputStream
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
) : Closeable {
    private val input = BufferedInputStream(Files.newInputStream(path))
    private val buffer = ByteArrayOutputStream()
    private var consumed = 0L

    /** Lines that went over [maxLineBytes] and came out blank. */
    var skippedLines = 0
        private set

    /** True once the read stopped at [maxBytes]; the line it was reading is dropped. */
    var cut = false
        private set

    val lines: Sequence<String> = generateSequence { nextLine() }

    private fun nextLine(): String? {
        buffer.reset()
        var lineBytes = 0L
        while (true) {
            val b = input.read()
            if (b == -1) return if (lineBytes == 0L) null else finishLine(lineBytes)
            if (++consumed > maxBytes) return null.also { cut = true }
            if (b == NEWLINE) return finishLine(lineBytes)
            if (++lineBytes <= maxLineBytes) buffer.write(b)
        }
    }

    private fun finishLine(lineBytes: Long): String {
        if (lineBytes <= maxLineBytes) return buffer.toString(Charsets.UTF_8)
        skippedLines++
        return ""
    }

    override fun close() {
        input.close()
    }

    private companion object {
        const val NEWLINE = '\n'.code
    }
}
