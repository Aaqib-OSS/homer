package homer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Budgets for the suppressions in backend main code: `@Suppress`, `jscpd:ignore-start` and the
 * pending-rule tag (the console's `eslint-disable` and `jscpd:ignore-start` are budgeted in
 * `console/scripts/size.mjs`). Each count may not exceed its budget (root CLAUDE.md §5, "add no new finding in code you
 * touch") and the budget ratchets like the size caps (ADR-0082): lower it when the count falls. Every
 * `@Suppress` also states its reason, on one line. A directive inside a string literal or
 * a comment counts too: the occurrence is the thing a reviewer must look at.
 */
class SuppressionBudgetTest {
    @Test
    fun `backend @Suppress stays within SUPPRESS_BUDGET`() {
        assertWithinBudget("@Suppress in backend main code", "SUPPRESS_BUDGET", SUPPRESS_BUDGET, occurrences(backendMain(), SUPPRESS))
    }

    @Test
    fun `jscpd ignore blocks stay within JSCPD_IGNORE_BUDGET`() {
        assertWithinBudget(
            "jscpd:ignore-start blocks in backend main code",
            "JSCPD_IGNORE_BUDGET",
            JSCPD_IGNORE_BUDGET,
            occurrences(backendMain(), JSCPD_IGNORE),
        )
    }

    @Test
    fun `pending-rule tags stay within REPORT_ONLY_BUDGET`() {
        assertWithinBudget(
            "@Tag(REPORT_ONLY) in architecture tests",
            "REPORT_ONLY_BUDGET",
            REPORT_ONLY_BUDGET,
            occurrences(architectureTests(), REPORT_ONLY_TAG),
        )
    }

    @Test
    fun `every backend @Suppress states its reason`() {
        val violations =
            backendMain().flatMap { file ->
                val text = file.readText()
                SUPPRESS.findAll(text).filterNot { hasReason(text, it.range.first) }.map {
                    violation(
                        file.path,
                        lineOf(text, it.range.first),
                        "@Suppress without a one-line reason (// comment on the line or the one above)",
                    )
                }
            }
        assertNoViolations("Every @Suppress states why (root CLAUDE.md §5)", violations)
    }

    @Test
    fun `an annotation with several ids counts once`() {
        assertEquals(1, countOf("@Suppress(\"A\", \"B\", \"C\") fun f() {}", SUPPRESS))
        assertEquals(2, countOf("@Suppress(\"A\")\nfun f() {}\n@Suppress(\"B\")\nfun g() {}", SUPPRESS))
    }

    @Test
    fun `a directive inside a string literal is counted`() {
        assertEquals(1, countOf("val s = \"@Suppress(\"", SUPPRESS))
    }

    @Test
    fun `jscpd and tag patterns count their marker only`() {
        assertEquals(1, countOf("// jscpd:ignore-start\nx\n// jscpd:ignore-end", JSCPD_IGNORE))
        assertEquals(1, countOf("    @Tag(REPORT_ONLY)\n    @Test", REPORT_ONLY_TAG))
        assertEquals(0, countOf(" * the @Tag(REPORT_ONLY) lane", REPORT_ONLY_TAG), "a KDoc mention is no tag")
    }

    @Test
    fun `a Kotlin suppression has a reason on its line, inside it or on the line above`() {
        fun reasoned(text: String) = hasReason(text, text.indexOf("@Suppress"))
        assertTrue(reasoned("@Suppress(\"A\") // why\nfun f() {}"), "same line")
        assertTrue(reasoned("// why\n@Suppress(\"A\")\nfun f() {}"), "line above")
        assertTrue(reasoned("@Suppress(\n    \"A\", // why\n)\nfun f() {}"), "inside the annotation")
        assertFalse(reasoned("@Suppress(\"A\")\nfun f() {}"), "no reason")
        assertFalse(reasoned("// why\n\n@Suppress(\"A\")\nfun f() {}"), "a blank line breaks the link")
        assertFalse(
            reasoned("val x = 1 // a note on the code\n@Suppress(\"A\")\nfun f() {}"),
            "a trailing comment on a code line above is no reason",
        )
        assertFalse(reasoned("val x = 1\n@Suppress(\"A\")\nfun f() {}"), "code above is no reason")
        assertFalse(reasoned("@Suppress(\"A\") //\nfun f() {}"), "a bare marker")
        assertFalse(reasoned("@Suppress(\"A\") // TODO\nfun f() {}"), "TODO is no reason")
        assertFalse(reasoned("// FIXME:\n@Suppress(\"A\")\nfun f() {}"), "FIXME above is no reason")
        assertFalse(reasoned("@Suppress(\"http://x\")\nfun f() {}"), "a // inside a string literal is no comment")
        assertFalse(reasoned("@Suppress(\"\"\"http://x\"\"\")\nfun f() {}"), "a // inside a raw string is no comment")
        assertFalse(reasoned("val c = '/'\n@Suppress(\"A\", \"//\")\nfun f() {}"), "a // in a second literal is no comment")
        assertFalse(reasoned("@Suppress(\"A(\")\nfun f() {}"), "a parenthesis inside a string shifts nothing")
        assertFalse(
            reasoned("@Suppress(\"A(\")\nfun f() {} // next line's comment"),
            "an open parenthesis in a string does not swallow the next line",
        )
        assertFalse(reasoned("@Suppress(\"\${listOf(\"(\")}\")\nfun f() {} // next line's comment"), "nor one inside a template expression")
        assertFalse(reasoned("@Suppress(\"\${listOf(\"//\")}\")\nfun f() {}"), "a // inside a template expression is no comment")
        assertFalse(
            reasoned("val s = \"\"\"a\n// why it is here\"\"\".trim()\n@Suppress(\"A\")\nfun f() {}"),
            "a raw-string line above is no comment",
        )
        assertTrue(reasoned("@Suppress(\"A(\") // why it stays\nfun f() {}"), "a real reason after a literal with a parenthesis")
        assertTrue(reasoned("@Suppress(\"http://x\") // the url is fixed\nfun f() {}"), "a real reason after a literal")
    }

    @Test
    fun `a budget is stale by the size-cap ratchet rule`() {
        assertNull(staleCap(cap = 51, currentMax = 51))
        assertNull(staleCap(cap = 0, currentMax = 0))
        assertNull(staleCap(cap = 14, currentMax = 13), "the slack is at least 1")
        assertEquals(12, staleCap(cap = 14, currentMax = 12))
    }

    private fun assertWithinBudget(
        rule: String,
        constant: String,
        budget: Int,
        found: List<Violation>,
    ) {
        if (found.size > budget) {
            fail("$rule: ${found.size} occurrence(s), budget $budget ($constant, $SOURCE_FILE)\n" + found.joinToString("\n"))
        }
        staleCap(budget, found.size)?.let { lowerTo ->
            fail(
                "$rule: $constant ($SOURCE_FILE) is $budget but the count is ${found.size}; " +
                    "lower it to $lowerTo (a budget may sit at most ${slack(budget)} above the count, ADR-0082)",
            )
        }
    }

    private fun occurrences(
        files: List<File>,
        pattern: Regex,
    ): List<Violation> =
        files.flatMap { file ->
            val text = file.readText()
            pattern.findAll(text).map { violation(file.path, lineOf(text, it.range.first), it.value.trim()) }.toList()
        }

    private fun backendMain(): List<File> = Sources.modules.flatMap { Sources.main(it).files }.map { File(it.path) }

    private fun architectureTests(): List<File> = Sources.test("architecture").files.map { File(it.path) }

    private companion object {
        const val SOURCE_FILE = "app/architecture/src/test/kotlin/homer/architecture/SuppressionBudgetTest.kt"

        // Counts on 2026-11-17 (GH #310); lower a budget when its count falls, never raise one to fit a new suppression.
        const val SUPPRESS_BUDGET = 51
        const val JSCPD_IGNORE_BUDGET = 5
        const val REPORT_ONLY_BUDGET = 0
    }
}

internal val SUPPRESS = Regex("""@Suppress\(""")
internal val JSCPD_IGNORE = Regex("""jscpd:ignore-start""")
internal val REPORT_ONLY_TAG = Regex("""(?m)^\s*@Tag\(REPORT_ONLY\)""")

internal fun countOf(
    text: String,
    pattern: Regex,
): Int = pattern.findAll(text).count()

/**
 * Whether the `@Suppress(` at [offset] states why: a comment with real text on its line (up to the line of its
 * closing parenthesis, so one inside the annotation counts) or a `//` comment line directly above (comment-only: a trailing comment on a code line does not count). Comments
 * are read with [KotlinText], so a marker inside a string or character literal is no comment.
 */
internal fun hasReason(
    text: String,
    offset: Int,
): Boolean {
    val segment = text.substring(offset, lineEnd(text, offset))
    if (commentTexts(segment).any(::isReasonText)) return true
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    if (lineStart == 0) return false
    // The line above must be comment-only: its raw text starts with the marker, and lexed up to it the marker
    // is a comment, so neither a trailing comment on a code line nor a raw-string line counts.
    val before = text.substring(0, lineStart - 1)
    val raw = before.substringAfterLast('\n').trimStart()
    val lexed = KotlinText.commentText(before).substringAfterLast('\n').trimStart()
    return raw.startsWith("//") && lexed.startsWith("//") && isReasonText(raw.removePrefix("//"))
}

/** A reason has real words: at least 3 non-space characters, once a closing marker is dropped, and not a bare TODO / FIXME. */
internal fun isReasonText(raw: String): Boolean {
    val text = raw.trim().removeSuffix("*/").trim()
    return text.count { !it.isWhitespace() } >= 3 && !Regex("""(?i)(todo|fixme)\W*""").matches(text)
}

private val COMMENT = Regex("""//[^\n]*|/\*[\s\S]*?(?:\*/|$)""")

internal fun commentTexts(segment: String): List<String> =
    COMMENT.findAll(KotlinText.commentText(segment)).map { it.value.removePrefix("//").removePrefix("/*") }.toList()

private fun lineEnd(
    text: String,
    offset: Int,
): Int {
    var depth = 0
    var i = text.indexOf('(', offset)
    while (i in text.indices) {
        val next = KotlinText.skipNonCode(text, i)
        if (next != i) {
            i = next
            continue
        }
        if (text[i] == '(') depth++
        if (text[i] == ')' && --depth == 0) break
        i++
    }
    return text.indexOf('\n', i.coerceAtMost(text.length)).let { if (it < 0) text.length else it }
}
