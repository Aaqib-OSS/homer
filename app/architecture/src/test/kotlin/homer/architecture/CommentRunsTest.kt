package homer.architecture

import kotlin.test.Test
import kotlin.test.assertEquals

/** The comment-hygiene rule's finding functions on fixture text, so its limits hold without the real sources. */
class CommentRunsTest {
    private val path = "app/src/main/kotlin/homer/app/Fixture.kt"

    @Test
    fun `nine consecutive comment lines are flagged at the first one and eight are not`() {
        assertEquals(listOf(1 to 9), commentRuns(comments(9)))
        assertEquals(emptyList(), commentRuns(comments(8)))
        assertEquals(
            listOf(Violation(path, 1, "9 consecutive comment lines (limit 8)")),
            commentRunViolations(path, comments(9)),
        )
    }

    @Test
    fun `a run is reported at its own first line, not the file's`() {
        assertEquals(listOf(3 to 9), commentRuns("val a = 1\n\n${comments(9)}"))
    }

    @Test
    fun `a blank line or a code line splits a run`() {
        assertEquals(emptyList(), commentRuns("${comments(8)}\n\n${comments(8)}"))
        assertEquals(emptyList(), commentRuns("${comments(8)}\nval a = 1\n${comments(8)}"))
        assertEquals(listOf(7 to 9), commentRuns("${comments(5)}\n\n${comments(9)}"))
    }

    @Test
    fun `a KDoc block counts from its opening line to its closing one`() {
        val kdoc = "/**\n" + (1..7).joinToString("\n") { " * constraint $it" } + "\n */"

        assertEquals(listOf(1 to 9), commentRuns(kdoc))
    }

    @Test
    fun `a phrase in a comment is flagged, in a string literal it is not`() {
        // Assembled from parts so this file does not itself read as a citation of the gitignored review
        // document that `DeadReviewReferenceScanTest` guards.
        val audit = "Audit" + " #8"
        val lineComment = narrationViolations(path, "// $audit: the race\n").single()

        assertEquals(1, lineComment.line)
        assertEquals("comment narrates what is gone (past-audit narration)", lineComment.text)

        val kdoc = narrationViolations(path, "/**\n * the previous implementation serialized everything\n */\n").single()

        assertEquals(2, kdoc.line)
        assertEquals("comment narrates what is gone (previous implementation)", kdoc.text)

        assertEquals(emptyList(), narrationViolations(path, "val s = \"$audit\"\nval t = \"previous implementation\"\n"))
        assertEquals(emptyList(), narrationViolations(path, "// the audit of run #8\n"))
    }

    @Test
    fun `past-implementation narration is flagged, a purpose phrase is not`() {
        val past = narrationViolations(path, "// the four flags previously carried this state\n").single()

        assertEquals(1, past.line)
        assertEquals("comment narrates what is gone (past-implementation narration)", past.text)

        // One row per listed verb: a typo in one would let the narration it exists for pass with the rule green.
        listOf(
            "used to be the exit code",
            "used to hold a slot",
            "used to read the config",
            "used to carry this state",
            "used to have a name",
        ).forEach { phrase ->
            assertEquals(
                "comment narrates what is gone (past-implementation narration)",
                narrationViolations(path, "// each lock waiter $phrase\n").single().text,
                phrase,
            )
        }
        assertEquals(
            "comment narrates what is gone (past-implementation narration)",
            narrationViolations(path, "// Previously this held the state\n").single().text,
        )
        assertEquals(emptyList(), narrationViolations(path, "// used to scope the cooldown reads\n"))
        assertEquals(emptyList(), narrationViolations(path, "// a finished run can no longer grow its file\n"))
    }

    private fun comments(count: Int) = (1..count).joinToString("\n") { "// constraint $it" }
}
