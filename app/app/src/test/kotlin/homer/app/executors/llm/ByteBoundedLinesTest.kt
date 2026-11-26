package homer.app.executors.llm

import homer.app.infra.parseNdjsonLenient
import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteBoundedLinesTest {
    private val lineArb = Arb.choice(Arb.pattern("[a-z{}\":, ]{0,12}"), Arb.element("", "é", "€€ ", "😀x"))
    private val linesArb = Arb.list(lineArb, 0..8)
    private val chunks = Arb.int(1..9)

    @TempDir
    lateinit var tempDir: Path

    private fun read(
        path: Path,
        maxLineBytes: Int,
        maxBytes: Long,
        chunkBytes: Int = 8,
    ): Triple<List<String>, Int, Boolean> =
        ByteBoundedLines(path, maxBytes, maxLineBytes, dropCutLine = true, chunkBytes).use {
            Triple(it.lines.toList(), it.skippedLines, it.cut)
        }

    private fun readKeepingCutLine(
        path: Path,
        maxBytes: Long,
    ): Pair<List<String>, Boolean> = ByteBoundedLines(path, maxBytes).use { it.lines.toList() to it.cut }

    private fun bounded(
        lines: List<String>,
        maxLineBytes: Int,
    ) = lines.map { if (it.toByteArray().size <= maxLineBytes) it else "" }

    @Test
    fun `within the byte bound every line comes back whole, or blank and counted when over the line bound`() =
        runTest {
            val file = Files.createTempFile("bounded-lines", ".txt")
            try {
                checkAll(linesArb, Arb.int(0..16), chunks) { lines, maxLineBytes, chunkBytes ->
                    file.writeText(lines.joinToString("") { "$it\n" })

                    val (read, skipped, cut) = read(file, maxLineBytes, Long.MAX_VALUE, chunkBytes)

                    assertEquals(bounded(lines, maxLineBytes), read)
                    assertEquals(lines.count { it.toByteArray().size > maxLineBytes }, skipped)
                    assertFalse(cut)
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun `a read past the byte bound is cut and yields only a prefix of lines within both bounds (CWE-400)`() =
        runTest {
            val file = Files.createTempFile("bounded-lines", ".txt")
            try {
                checkAll(linesArb, Arb.int(0..16), Arb.long(0L..80L), chunks) { lines, maxLineBytes, maxBytes, chunkBytes ->
                    val content = lines.joinToString("") { "$it\n" }.toByteArray()
                    file.writeBytes(content)

                    val (read, _, cut) = read(file, maxLineBytes, maxBytes, chunkBytes)

                    assertEquals(content.size > maxBytes, cut)
                    assertEquals(bounded(lines, maxLineBytes).let { if (cut) it.take(read.size) else it }, read)
                    assertTrue(read.all { it.toByteArray().size <= maxLineBytes })
                    assertTrue(read.sumOf { it.toByteArray().size + 1L } <= maxBytes)
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun `a last line without a newline is still read, and an empty file has no lines`() {
        val unterminated = tempDir.resolve("a").also { it.writeText("one\ntwo") }
        val empty = tempDir.resolve("b").also { it.writeText("") }

        assertEquals(Triple(listOf("one", "two"), 0, false), read(unterminated, 8, 100))
        assertEquals(Triple(emptyList(), 0, false), read(empty, 8, 100))
    }

    @Test
    fun `without a line bound it stops reading at the byte bound, keeps the partial line and marks the cut`() {
        val file = tempDir.resolve("flood.ndjson")
        Files.write(file, "line\n".repeat(10).toByteArray())
        val (lines, cut) = readKeepingCutLine(file, maxBytes = 12)
        // 12 bytes: two whole 5-byte lines and the partial third.
        assertEquals(listOf("line", "line", "li"), lines)
        assertTrue(cut)
        assertFalse(readKeepingCutLine(file, maxBytes = 100).second)
    }

    @Test
    fun `without a line bound it never materializes a line past the byte bound (CWE-400)`() {
        val file = tempDir.resolve("one-line.ndjson")
        Files.write(file, ByteArray(1024 * 1024) { 'a'.code.toByte() })
        assertEquals(listOf("a".repeat(1024)) to true, readKeepingCutLine(file, maxBytes = 1024))
    }

    @Test
    fun `without a line bound it marks a cut only when the file holds more than the bound`() {
        val file = tempDir.resolve("exact.ndjson")
        Files.write(file, "{}\n{}\n".toByteArray())
        assertEquals(listOf("{}", "{}") to false, readKeepingCutLine(file, maxBytes = 6))
        assertEquals(listOf("{}", "{") to true, readKeepingCutLine(file, maxBytes = 4))
    }

    @Test
    fun `lines split on a newline only, so a carriage return stays on its line`() {
        val crlf = Files.writeString(tempDir.resolve("crlf.ndjson"), "{\"a\":1}\r\n{\"b\":2}\r\n")
        val bareCr = Files.writeString(tempDir.resolve("cr.ndjson"), "{\"a\":1}\r{\"b\":2}\n")
        assertEquals(listOf("{\"a\":1}\r", "{\"b\":2}\r") to false, readKeepingCutLine(crlf, maxBytes = 100))
        assertEquals(listOf("{\"a\":1}\r{\"b\":2}") to false, readKeepingCutLine(bareCr, maxBytes = 100))
    }

    @Test
    fun `the hand-off parses a CRLF stream whole and a bare carriage return as one malformed line`() {
        val crlf = Files.writeString(tempDir.resolve("crlf.ndjson"), "{\"a\":1}\r\n{\"b\":2}\r\n")
        val bareCr = Files.writeString(tempDir.resolve("cr.ndjson"), "{\"a\":1}\r{\"b\":2}\n")
        val parsedCrlf = ByteBoundedLines(crlf, 100).use { parseNdjsonLenient(it.lines) }
        val parsedBareCr = ByteBoundedLines(bareCr, 100).use { parseNdjsonLenient(it.lines) }
        assertEquals(2 to 0L, parsedCrlf.events.size to parsedCrlf.skippedLines)
        assertEquals(0 to 1L, parsedBareCr.events.size to parsedBareCr.skippedLines)
    }

    @Test
    fun `without a line bound it bounds bytes, not chars, and decodes a split character as a replacement`() {
        val file = Files.writeString(tempDir.resolve("multibyte.ndjson"), "日日日\n")
        assertEquals(listOf("日�") to true, readKeepingCutLine(file, maxBytes = 4))
    }

    @Test
    fun `without a line bound it reads the file as it is up to the bound, and marks a cut exactly when it holds more`() =
        runTest {
            checkAll(Arb.pattern("[a-z{}\":,é日\\n]{0,300}"), Arb.int(0..400)) { content, maxBytes ->
                val bytes = content.toByteArray()
                val file = Files.write(tempDir.resolve("prop.ndjson"), bytes)
                val (lines, cut) = readKeepingCutLine(file, maxBytes.toLong())
                assertEquals(String(bytes.copyOf(minOf(bytes.size, maxBytes)), Charsets.UTF_8).reader().readLines(), lines)
                assertEquals(bytes.size > maxBytes, cut)
            }
        }
}
