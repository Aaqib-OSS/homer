package homer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Budgets for `jscpd:ignore-start` in backend main code and the pending-rule tag in the architecture tests. Each count
 * may not exceed its budget (root CLAUDE.md section 5) and the budget ratchets like the size caps (ADR-0082).
 * `@Suppress` and its reason are counted by `tools/quality/comment-policy.mjs`.
 */
class SuppressionBudgetTest {
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
    fun `jscpd and tag patterns count their marker only`() {
        assertEquals(1, countOf("// jscpd:ignore-start\nx\n// jscpd:ignore-end", JSCPD_IGNORE))
        assertEquals(1, countOf("    @Tag(REPORT_ONLY)\n    @Test", REPORT_ONLY_TAG))
        assertEquals(0, countOf(" * the @Tag(REPORT_ONLY) lane", REPORT_ONLY_TAG), "a KDoc mention is no tag")
    }

    @Test
    fun `a budget is stale by the size-cap ratchet rule`() {
        assertNull(staleCap(cap = 5, currentMax = 5))
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

        // Counts on 2026-11-17; lower a budget when its count falls, never raise one to fit a new suppression.
        const val JSCPD_IGNORE_BUDGET = 5
        const val REPORT_ONLY_BUDGET = 0
    }
}

internal val JSCPD_IGNORE = Regex("""jscpd:ignore-start""")
internal val REPORT_ONLY_TAG = Regex("""(?m)^\s*@Tag\(REPORT_ONLY\)""")

internal fun countOf(
    text: String,
    pattern: Regex,
): Int = pattern.findAll(text).count()
