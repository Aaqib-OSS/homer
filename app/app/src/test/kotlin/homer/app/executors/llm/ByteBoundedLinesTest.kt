package homer.app.executors.llm

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

    private fun read(
        path: Path,
        maxLineBytes: Int,
        maxBytes: Long,
        chunkBytes: Int = 8,
    ): Triple<List<String>, Int, Boolean> =
        ByteBoundedLines(path, maxLineBytes, maxBytes, chunkBytes).use { Triple(it.lines.toList(), it.skippedLines, it.cut) }

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
    fun `a last line without a newline is still read, and an empty file has no lines`(
        @TempDir dir: Path,
    ) {
        val unterminated = dir.resolve("a").also { it.writeText("one\ntwo") }
        val empty = dir.resolve("b").also { it.writeText("") }

        assertEquals(Triple(listOf("one", "two"), 0, false), read(unterminated, 8, 100))
        assertEquals(Triple(emptyList(), 0, false), read(empty, 8, 100))
    }
}
