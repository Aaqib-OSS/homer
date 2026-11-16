package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No tracked file may cite the gitignored `docs/`-subtree security review document by the
 * citation forms that cleanup removed — the parenthesised citation, the gitignored path
 * itself, the numbered `Audit #` narration, or a `See M<number>` finding label — because
 * an outside contributor cannot open that document, so every such pointer is dead. A
 * surviving explanation names the ADR that records it instead (`ADR-0047`, or the
 * specific one).
 *
 * The scope is deliberately the citation forms the cleanup removed, not every way a file
 * could allude to a review: an unparenthesised prose mention ("the 2026-06-08 security
 * review found…"), a comma form, and a bare label not introduced by "See" (e.g.
 * parenthesised) pass, because the ADRs record those findings under their own provenance
 * and widening the patterns would flag that historical narration. Adjacent lines are
 * joined before matching — each line stripped of the comment prefix a continuation line
 * carries — so a citation wrapped across a line break inside a comment block is still
 * caught.
 *
 * `.gitignore` is exempt — one of its lines *is* the ignore rule for that subtree — and so
 * is this file, which has to spell the patterns out. They are assembled by concatenation
 * anyway, so the guard's own source reads clean under it.
 */
class DeadReviewReferenceScanTest {
    @Test
    fun `no tracked file cites the gitignored security review`() {
        val files = trackedFiles()
        assertTrue(files.isNotEmpty(), "git ls-files listed nothing scannable; the guard would pass vacuously")
        val offenders =
            files.flatMap { (path, file) ->
                deadReviewOffenders(file.readText(Charsets.ISO_8859_1)).map { "$path:$it" }
            }
        assertEquals(emptyList(), offenders, "tracked files citing the gitignored review document or its audit narration")
    }

    @Test
    fun `flags every guard pattern`() =
        GUARD_PATTERNS.forEach { (form, _) ->
            assertEquals(listOf("1: $form"), deadReviewOffenders("a line citing $form in passing"))
        }

    @Test
    fun `flags a citation wrapped across two lines`() =
        WRAPPABLE.forEach { (form, _) ->
            val cut = form.lastIndexOf(' ') + 1
            assertEquals(
                listOf("1: $form (wrapped)"),
                deadReviewOffenders("${form.take(cut).trimEnd()}\n${form.drop(cut)}"),
                "pattern $form must be caught across a line break",
            )
        }

    @Test
    fun `flags a citation wrapped across two comment lines`() =
        listOf(" * ", "// ", "# ").forEach { prefix ->
            WRAPPABLE.forEach { (form, _) ->
                val cut = form.lastIndexOf(' ') + 1
                assertEquals(
                    listOf("1: $form (wrapped)"),
                    deadReviewOffenders("$prefix${form.take(cut).trimEnd()}\n$prefix${form.drop(cut)}"),
                    "pattern $form must be caught across two `$prefix` comment lines",
                )
            }
        }

    @Test
    fun `spares a reworded mention, an unrelated audit label and a bare date`() {
        // Unparenthesised prose mentions: the ADRs record those findings under their own
        // provenance (e.g. the 2026-06-08 review's H3/H4 in the cookie-auth ADR), and the
        // guard's scope is deliberately the removed citation forms, not every allusion.
        assertEquals(emptyList(), deadReviewOffenders("the security review found (finding H5) that"))
        assertEquals(emptyList(), deadReviewOffenders("H3 (security review 2026-06-08, ADR-0016)"))
        assertEquals(emptyList(), deadReviewOffenders("the 2026-07-12 review, H3"))
        assertEquals(emptyList(), deadReviewOffenders("Audit P0 #9 narration stays"))
        assertEquals(emptyList(), deadReviewOffenders("released 2026-07-12"))
        // A `See M` not followed by a finding number is prose, not a citation.
        assertEquals(emptyList(), deadReviewOffenders("See MCP tools, See Markdown and See Mermaid rendering"))
    }

    /** The tracked files under [repoRoot] the guard scans, minus the two exemptions. */
    private fun trackedFiles(): List<Pair<String, File>> {
        val process = ProcessBuilder("git", "-C", repoRoot.absolutePath, "ls-files", "-z").start()
        try {
            val stdout = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val stderr = process.errorStream.readBytes().toString(Charsets.UTF_8)
            val exited = process.waitFor(60, TimeUnit.SECONDS)
            if (!exited) process.destroyForcibly()
            assertTrue(exited, "git ls-files did not exit")
            assertEquals(0, process.exitValue(), "git ls-files failed: $stderr")
            return stdout
                .split('\u0000')
                .filter { it.isNotEmpty() && it !in EXEMPT }
                .map { it to File(repoRoot, it) }
                .filter { (_, file) -> file.isFile }
        } finally {
            process.destroy()
        }
    }
}

/**
 * The guard patterns, each one's canonical citation form concatenated so this file does not
 * match itself, paired with the regex that finds it. The `See M` label requires a finding
 * number — `See M12` — so prose such as `See MCP tools` is not a citation; the other three
 * are anchored to text unique to the dead citations.
 */
private val GUARD_PATTERNS =
    listOf(
        "security review" + " (" to Regex("security" + " review \\("),
        "docs/" + "superpowers" to Regex("docs/" + "superpowers"),
        "Audit" + " #" to Regex("Audit" + " #"),
        "See " + "M12" to Regex("See " + "M\\d+"),
    )

/** The patterns that can be split across a line break: those holding a space. */
private val WRAPPABLE = GUARD_PATTERNS.filter { (form, _) -> ' ' in form }

/** The blanks and comment markers opening a comment line: `*`, `//` or `#`. */
private val COMMENT_PREFIX = Regex("^[ \\t]*(?:[*/]+|#)[ \\t]*")

/** The paths the guard does not scan: the ignore rule itself, and this test's own source. */
private val EXEMPT = setOf(".gitignore", "app/app/src/test/kotlin/homer/app/DeadReviewReferenceScanTest.kt")

/**
 * Each guard pattern [content] trips, as `<line number>: <citation>`; empty when the content
 * is clean. A pattern found only when two adjacent lines are joined reports the first of
 * them, marked `(wrapped)`. Line-based so a finding names the line to look at.
 */
internal fun deadReviewOffenders(content: String): List<String> {
    val lines = content.lineSequence().toList()
    val perLine =
        lines.withIndex().flatMap { (n, line) ->
            GUARD_PATTERNS.mapNotNull { (_, regex) -> regex.find(line)?.value?.let { "${n + 1}: $it" } }
        }
    val wrapped =
        lines.zipWithNext().withIndex().flatMap { (n, pair) ->
            val joined = withoutCommentPrefix(pair.first) + " " + withoutCommentPrefix(pair.second)
            GUARD_PATTERNS
                .filter { (_, regex) -> !regex.containsMatchIn(pair.first) && !regex.containsMatchIn(pair.second) }
                .mapNotNull { (_, regex) -> regex.find(joined)?.value?.let { "${n + 1}: $it (wrapped)" } }
        }
    return perLine + wrapped
}

/**
 * [line] without the comment prefix a continuation line carries, so that a citation wrapped
 * inside a comment block joins the way the citation is written. A line that is nothing but a
 * prefix is kept as it is: the `#` of a wrapped `Audit #` can be the line's whole content.
 */
private fun withoutCommentPrefix(line: String): String = COMMENT_PREFIX.replaceFirst(line, "").ifEmpty { line }.trimEnd()
