package homer.architecture

import java.io.File
import kotlin.test.Test

/**
 * Comment hygiene on the main code of :app, agentdebug and homer-runner: a comment block is a one- or
 * two-line constraint, never a run of more than [MAX_COMMENT_RUN] lines, and it never narrates a past
 * implementation. It restates root CLAUDE.md §9 ("Comments vs docs — keep code comments sparse"), which
 * app/CLAUDE.md "Architecture tests" records.
 */
class CommentHygieneTest {
    @Test
    fun `main comments run at most 8 consecutive lines`() {
        val violations =
            mainFiles().flatMap { file ->
                commentRunViolations(Sources.repoPath(file.path), File(file.path).readText())
            }
        assertNoViolations("Comment runs ≤ $MAX_COMMENT_RUN lines (root CLAUDE.md §9)", violations)
    }

    @Test
    fun `main comments do not narrate past implementations`() {
        val violations =
            mainFiles().flatMap { file ->
                narrationViolations(Sources.repoPath(file.path), File(file.path).readText())
            }
        assertNoViolations("Comments without ${NARRATION_PATTERNS.joinToString(" / ") { it.first }} (root CLAUDE.md §9)", violations)
    }

    private fun mainFiles() = Sources.modules.flatMap { Sources.main(it).files }
}

/** Longest run of consecutive comment lines a main file may hold; a longer block is comment sediment. */
internal const val MAX_COMMENT_RUN = 8

/**
 * Comment text that narrates an implementation that is gone instead of the constraint that holds now: a label for
 * the finding, and the regex that finds it in the lowercased comment text.
 *
 * The list stays on phrases that can only look backwards — a stale `previous implementation`, a bare `previously`,
 * `used to` with a stative verb, and the past-audit form — rather than banning past tense generally: `no longer`
 * and a purpose `used to` ("used to scope the cooldown reads") state the code as it stands, and a broader rule
 * would fail on them. The past-audit form is a regex rather than a literal so this file does not itself read as a
 * citation of the gitignored review document that `DeadReviewReferenceScanTest` guards; it still matches that form
 * in any case.
 */
internal val NARRATION_PATTERNS: List<Pair<String, Regex>> =
    listOf(
        "previous implementation" to Regex("previous implementation"),
        "past-audit narration" to Regex("audit\\s*#"),
        "past-implementation narration" to Regex("\\bpreviously\\b|\\bused to (be|hold|read|carry|have)\\b"),
    )

/** Every run of more than [MAX_COMMENT_RUN] consecutive comment lines in [text], reported at its first line. */
internal fun commentRunViolations(
    path: String,
    text: String,
): List<Violation> =
    commentRuns(text).map { (line, length) ->
        Violation(path, line, "$length consecutive comment lines (limit $MAX_COMMENT_RUN)")
    }

/** Every narration pattern in the comment text of [text], reported at the line it starts on. */
internal fun narrationViolations(
    path: String,
    text: String,
): List<Violation> {
    val comments = KotlinText.commentText(text).lowercase()
    return NARRATION_PATTERNS.mapNotNull { (label, regex) ->
        val at = regex.find(comments)?.range?.first
        if (at == null) null else Violation(path, lineOf(comments, at), "comment narrates what is gone ($label)")
    }
}

/** First line (1-based) and length of every run of more than [max] consecutive comment lines in [text]. */
internal fun commentRuns(
    text: String,
    max: Int = MAX_COMMENT_RUN,
): List<Pair<Int, Int>> {
    val lines = KotlinText.commentLines(text)
    val runs = mutableListOf<Pair<Int, Int>>()
    var start = -1
    lines.forEachIndexed { index, isComment ->
        if (isComment) {
            if (start < 0) start = index
        } else {
            if (start >= 0 && index - start > max) runs += start + 1 to index - start
            start = -1
        }
    }
    if (start >= 0 && lines.size - start > max) runs += start + 1 to lines.size - start
    return runs
}
