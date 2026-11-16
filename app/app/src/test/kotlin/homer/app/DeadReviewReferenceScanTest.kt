package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No tracked file may cite the security review document that lives only under a gitignored
 * `docs/` subtree, nor the numbered audit narration that went with it: an outside
 * contributor cannot open that document, so every pointer to it is dead. A surviving
 * explanation names the ADR that records it instead (`ADR-0047`, or the specific one).
 *
 * `.gitignore` is exempt — one of its lines *is* the ignore rule for that subtree — and so
 * is this file, which has to spell the patterns out. They are assembled by concatenation
 * anyway, so the guard's own source reads clean under it.
 */
class DeadReviewReferenceScanTest {
    @Test
    fun `no tracked file cites the gitignored security review`() {
        val offenders =
            trackedFiles().flatMap { (path, file) ->
                deadReviewOffenders(file.readText(Charsets.ISO_8859_1)).map { "$path:$it" }
            }
        assertEquals(emptyList(), offenders, "tracked files citing the gitignored review document or its audit narration")
    }

    @Test
    fun `flags every guard pattern`() =
        GUARD_PATTERNS.forEach { pattern ->
            assertEquals(listOf("1: $pattern"), deadReviewOffenders("a line citing $pattern in passing"))
        }

    @Test
    fun `spares a reworded mention, an unrelated audit label and a bare date`() {
        assertEquals(emptyList(), deadReviewOffenders("the security review found (finding H5) that"))
        assertEquals(emptyList(), deadReviewOffenders("Audit P0 #9 narration stays"))
        assertEquals(emptyList(), deadReviewOffenders("released 2026-07-12"))
    }

    /** The tracked files under [repoRoot] the guard scans, minus the two exemptions. */
    private fun trackedFiles(): List<Pair<String, File>> {
        val process = ProcessBuilder("git", "-C", repoRoot.absolutePath, "ls-files", "-z").start()
        val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
        val exited = process.waitFor(60, TimeUnit.SECONDS)
        assertTrue(exited, "git ls-files did not exit")
        assertEquals(0, process.exitValue(), "git ls-files failed: $stderr")
        return stdout
            .split('\u0000')
            .filter { it.isNotEmpty() && it !in EXEMPT }
            .map { it to File(repoRoot, it) }
            .filter { (_, file) -> file.isFile }
    }
}

/** The guard patterns, concatenated so this file does not match itself. */
private val GUARD_PATTERNS = listOf("security review" + " (", "docs/" + "superpowers", "Audit" + " #")

/** The paths the guard does not scan: the ignore rule itself, and this test's own source. */
private val EXEMPT = setOf(".gitignore", "app/app/src/test/kotlin/homer/app/DeadReviewReferenceScanTest.kt")

/**
 * Each guard pattern [content] trips, as `<line number>: <pattern>`; empty when the content
 * is clean. Line-based so a finding names the line to look at.
 */
internal fun deadReviewOffenders(content: String): List<String> =
    content
        .lineSequence()
        .withIndex()
        .flatMap { (n, line) -> GUARD_PATTERNS.filter { it in line }.map { "${n + 1}: $it" } }
        .toList()
