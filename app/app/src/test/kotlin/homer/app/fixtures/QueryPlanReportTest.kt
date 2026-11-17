package homer.app.fixtures

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
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
    fun `worker files are merged and one SQL from two call sites counts once`(
        @TempDir dir: File,
    ) {
        dir.resolve("statements-2.tsv").writeText("a\t1\tf\tSELECT 1\nb\t2\tg\tSELECT 2\n")
        dir.resolve("statements-10.tsv").writeText("z\t360\tother\tSELECT 2\n\nc\t3\th\tSELECT 3\n")
        dir.resolve("repeats-2.tsv").writeText("a\t1\tf\tSELECT 1\n")
        dir.resolve("other.tsv").writeText("x\t9\tz\tSELECT 9\n")

        assertEquals(
            listOf("a\t1\tf\tSELECT 1", "b\t2\tg\tSELECT 2", "c\t3\th\tSELECT 3"),
            QueryPlanReport.mergedLines(dir, "statements", QueryPlanReport.STATEMENT_KEY),
        )
        assertEquals(listOf("a\t1\tf\tSELECT 1"), QueryPlanReport.mergedLines(dir, "repeats", QueryPlanReport.REPEAT_KEY))
    }

    @Test
    fun `a repeat at two lines of one function counts once, another function or file does not`(
        @TempDir dir: File,
    ) {
        dir.resolve("repeats-1.tsv").writeText("a\t1\tf\tSELECT 1\n")
        dir.resolve("repeats-2.tsv").writeText("a\t360\tf\tSELECT 1\na\t5\tg\tSELECT 1\nb\t1\tf\tSELECT 1\n")

        assertEquals(
            listOf("a\t1\tf\tSELECT 1", "a\t5\tg\tSELECT 1", "b\t1\tf\tSELECT 1"),
            QueryPlanReport.mergedLines(dir, "repeats", QueryPlanReport.REPEAT_KEY),
        )
    }

    @Test
    fun `the kept line per key does not depend on which worker file holds it`(
        @TempDir dir: File,
    ) {
        val low = "a\t1\tf\tSELECT 1"
        val high = "a\t360\tf\tSELECT 1"
        val first = dir.resolve("first").apply { mkdirs() }
        val second = dir.resolve("second").apply { mkdirs() }
        first.resolve("repeats-1.tsv").writeText("$low\n")
        first.resolve("repeats-2.tsv").writeText("$high\n")
        second.resolve("repeats-1.tsv").writeText("$high\n")
        second.resolve("repeats-2.tsv").writeText("$low\n")

        assertEquals(listOf(low), QueryPlanReport.mergedLines(first, "repeats", QueryPlanReport.REPEAT_KEY))
        assertEquals(listOf(low), QueryPlanReport.mergedLines(second, "repeats", QueryPlanReport.REPEAT_KEY))
    }

    @Test
    fun `main merges the worker files and writes the report`(
        @TempDir dir: File,
    ) {
        val out = dir.resolve("out").resolve("report.json")
        dir.resolve("statements-1.tsv").writeText("app/A.kt\t3\tget\tSELECT processes.status FROM processes WHERE processes.id = ?\n")
        dir.resolve("repeats-1.tsv").writeText("app/R.kt\t360\trun\tSELECT 1\n")
        dir.resolve("repeats-2.tsv").writeText("app/R.kt\t1\trun\tSELECT 1\n")

        QueryPlanReport.main(arrayOf(dir.path, out.path))

        val report = Json.decodeFromString(QueryPlanReport.Report.serializer(), out.readText())
        assertEquals(1, report.statements)
        assertEquals(listOf(QueryPlanReport.Repeat("app/R.kt", 1, "run", "SELECT 1")), report.repeats)
    }

    @Test
    fun `no capture file is an error from main, an empty one too`(
        @TempDir dir: File,
    ) {
        val out = dir.resolve("out.json").path
        assertEquals(emptyList(), QueryPlanReport.mergedLines(dir, "statements", QueryPlanReport.STATEMENT_KEY))
        val missing = assertFailsWith<IllegalStateException> { QueryPlanReport.main(arrayOf(dir.path, out)) }
        assertTrue("no captured statements" in missing.message.orEmpty())
        dir.resolve("statements-1.tsv").writeText("\n")
        val empty = assertFailsWith<IllegalStateException> { QueryPlanReport.main(arrayOf(dir.path, out)) }
        assertTrue("no captured statement" in empty.message.orEmpty())
    }

    @Test
    fun `splitting lines across any number of worker files merges to one line per key, the union of the keys`() =
        runTest {
            val sql = Arb.string(1..6).map { "SELECT '${it.replace(Regex("[\\t\\r\\n]"), " ")}'" }
            val line = Arb.bind(Arb.int(0..3), Arb.int(1..400), Arb.int(0..2), sql) { p, n, f, q -> "p$p\t$n\tf$f\t$q" }
            checkAll(Arb.list(line, 0..20), Arb.list(Arb.int(0..4), 0..40)) { lines, picks ->
                val dir = createTempDirectory("merge").toFile()
                try {
                    // Every line lands in at least one worker file; extra picks duplicate it elsewhere.
                    val files = List(5) { mutableListOf<String>() }
                    lines.forEachIndexed { i, l -> files[i % 5] += l }
                    picks.forEachIndexed { i, w -> if (lines.isNotEmpty()) files[w] += lines[i % lines.size] }
                    files.forEachIndexed { i, ls ->
                        if (ls.isNotEmpty()) dir.resolve("statements-$i.tsv").writeText(ls.joinToString("\n", postfix = "\n"))
                    }
                    for (key in listOf(QueryPlanReport.STATEMENT_KEY, QueryPlanReport.REPEAT_KEY)) {
                        val merged = QueryPlanReport.mergedLines(dir, "statements", key)
                        val keyOf = { l: String -> l.split('\t', limit = 4).let { f -> key.map { f[it] } } }
                        assertEquals(merged.size, merged.map(keyOf).toSet().size, "one line per key")
                        assertEquals(lines.map(keyOf).toSet(), merged.map(keyOf).toSet())
                        assertTrue(lines.containsAll(merged))
                    }
                } finally {
                    dir.deleteRecursively()
                }
            }
        }
}
