package homer.app.fixtures

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueryPlanReportTest {
    @Test
    fun `a scan of a growing table is a finding, through an index or of another table it is not`() {
        assertEquals("questions", QueryPlanReport.scannedTable("SELECT id FROM questions", listOf("SCAN questions")))
        assertNull(
            QueryPlanReport.scannedTable("SELECT COUNT(*) FROM processes", listOf("SCAN processes USING COVERING INDEX processes_status")),
        )
        assertNull(QueryPlanReport.scannedTable("SELECT key FROM server_meta", listOf("SCAN server_meta")))
    }

    @Test
    fun `an unfiltered primary-key page is bounded, a filtered one is not`() {
        val plan = listOf("SCAN processes")
        assertNull(QueryPlanReport.scannedTable("SELECT … FROM processes WHERE 1 ORDER BY processes.id DESC LIMIT 100 OFFSET 200", plan))
        assertNull(QueryPlanReport.scannedTable("SELECT … FROM processes ORDER BY processes.id ASC LIMIT 5", plan))
        assertEquals(
            "processes",
            QueryPlanReport.scannedTable(
                "SELECT … FROM processes WHERE 1 AND (processes.owner = ?) ORDER BY processes.id ASC LIMIT 100",
                plan,
            ),
        )
    }

    @Test
    fun `report plans captured lines on the migrated schema`() {
        val path = PersistenceTestSupport.tempDatabasePath()
        val db = PersistenceTestSupport.tempDatabase(path)
        val lines =
            listOf(
                "app/A.kt\t3\tget\tSELECT processes.status FROM processes WHERE processes.id = ?",
                "app/B.kt\t7\tlist\tSELECT questions.text FROM questions WHERE 1",
                "app/C.kt\t9\tbroken\tSELECT nope FROM missing_table",
            )
        val report = DriverManager.getConnection("jdbc:sqlite:$path").use { QueryPlanReport.report(it, lines) }
        db.close()

        assertEquals(3, report.statements)
        assertEquals(listOf("app/B.kt:7 questions"), report.findings.map { "${it.path}:${it.line} ${it.table}" })
        assertEquals(listOf("app/C.kt"), report.unplanned.map { it.path })
    }

    @Test
    fun `worker files are merged and an overlapping line counts once`(
        @TempDir dir: File,
    ) {
        dir.resolve("statements-2.tsv").writeText("a\t1\tf\tSELECT 1\nb\t2\tg\tSELECT 2\n")
        dir.resolve("statements-10.tsv").writeText("b\t2\tg\tSELECT 2\n\nc\t3\th\tSELECT 3\n")
        dir.resolve("repeats-2.tsv").writeText("a\t1\tf\tSELECT 1\n")
        dir.resolve("other.tsv").writeText("x\t9\tz\tSELECT 9\n")

        assertEquals(
            listOf("b\t2\tg\tSELECT 2", "c\t3\th\tSELECT 3", "a\t1\tf\tSELECT 1"),
            QueryPlanReport.mergedLines(dir, "statements"),
        )
        assertEquals(listOf("a\t1\tf\tSELECT 1"), QueryPlanReport.mergedLines(dir, "repeats"))
    }

    @Test
    fun `no capture file is an error from main, an empty one too`(
        @TempDir dir: File,
    ) {
        val out = dir.resolve("out.json").path
        assertEquals(emptyList(), QueryPlanReport.mergedLines(dir, "statements"))
        val missing = assertFailsWith<IllegalStateException> { QueryPlanReport.main(arrayOf(dir.path, out)) }
        assertTrue("no captured statements" in missing.message.orEmpty())
        dir.resolve("statements-1.tsv").writeText("\n")
        val empty = assertFailsWith<IllegalStateException> { QueryPlanReport.main(arrayOf(dir.path, out)) }
        assertTrue("no captured statement" in empty.message.orEmpty())
    }

    @Test
    fun `splitting lines across any number of worker files with duplicates merges to the distinct set`() =
        runTest {
            val line = Arb.string(1..12).map { "p\t1\tf\tSELECT '$it'".replace("\n", " ").replace("\r", " ") }
            checkAll(Arb.list(line, 0..20), Arb.list(Arb.int(0..4), 0..40)) { lines, picks ->
                val dir =
                    kotlin.io.path
                        .createTempDirectory("merge")
                        .toFile()
                try {
                    // Every line lands in at least one worker file; extra picks duplicate it elsewhere.
                    val files = List(5) { mutableListOf<String>() }
                    lines.forEachIndexed { i, l -> files[i % 5] += l }
                    picks.forEachIndexed { i, w -> if (lines.isNotEmpty()) files[w] += lines[i % lines.size] }
                    files.forEachIndexed {
                        i,
                        ls,
                        ->
                        if (ls.isNotEmpty()) dir.resolve("statements-$i.tsv").writeText(ls.joinToString("\n", postfix = "\n"))
                    }
                    assertEquals(lines.filter { it.isNotBlank() }.toSet(), QueryPlanReport.mergedLines(dir, "statements").toSet())
                    assertEquals(
                        QueryPlanReport.mergedLines(dir, "statements").size,
                        QueryPlanReport.mergedLines(dir, "statements").toSet().size,
                    )
                } finally {
                    dir.deleteRecursively()
                }
            }
        }
}
