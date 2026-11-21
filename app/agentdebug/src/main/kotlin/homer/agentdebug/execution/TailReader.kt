package homer.agentdebug.execution

/**
 * Tracks the read position of one tailed output file and emits newly-appended lines exactly once.
 * Single-owner: advanced only by its tail coroutine via [consume], read by [flushRemaining] after that coroutine
 * is joined (ordered by `cancelAndJoin`), so no synchronization is needed.
 */
class TailReader(
    private val prefix: String,
) {
    private var position = 0

    /**
     * Print every complete line that appeared since the last call (never the
     * trailing partial line), then advance the position past those lines.
     */
    fun consume(content: String) {
        if (content.length <= position) return
        val newChunk = content.substring(position)
        // Split on '\n' only: lines() also splits "\r\n" and would desync the position accounting.
        // Drop the last element: an incomplete trailing line, or the empty string after a trailing newline.
        val completeLines = newChunk.split('\n').dropLast(1)
        completeLines.forEach { line -> println("$prefix ${line.removeSuffix("\r")}") }
        // Track how far we've consumed (complete lines only)
        val consumed = completeLines.sumOf { it.length + 1 }
        position += consumed
    }

    /**
     * Print whatever remains past the consumed position (e.g. a final line not
     * terminated by a newline). Read-only with respect to [position].
     */
    fun flushRemaining(content: String) {
        if (content.length <= position) return
        val remaining = content.substring(position).trimEnd('\n').trimEnd('\r')
        if (remaining.isNotEmpty()) {
            remaining.split('\n').forEach { line -> println("$prefix ${line.removeSuffix("\r")}") }
        }
    }
}
