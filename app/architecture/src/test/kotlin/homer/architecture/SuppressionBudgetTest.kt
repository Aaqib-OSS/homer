package homer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Budgets for the suppressions in main code: `@Suppress`, `eslint-disable`, `jscpd:ignore-start` and the
 * pending-rule tag. Each count may not exceed its budget (root CLAUDE.md §5, "add no new finding in code you
 * touch") and the budget ratchets like the size caps (ADR-0082): lower it when the count falls. Every
 * `@Suppress` and `eslint-disable` also states its reason, on one line. A directive inside a string literal or
 * a comment counts too: the occurrence is the thing a reviewer must look at.
 */
class SuppressionBudgetTest {
    @Test
    fun `backend @Suppress stays within SUPPRESS_BUDGET`() {
        assertWithinBudget("@Suppress in backend main code", "SUPPRESS_BUDGET", SUPPRESS_BUDGET, occurrences(backendMain(), SUPPRESS))
    }

    @Test
    fun `console eslint-disable stays within ESLINT_DISABLE_BUDGET`() {
        assertWithinBudget(
            "eslint-disable in console production code",
            "ESLINT_DISABLE_BUDGET",
            ESLINT_DISABLE_BUDGET,
            occurrences(consoleProduction(), ESLINT_DISABLE),
        )
    }

    @Test
    fun `jscpd ignore blocks stay within JSCPD_IGNORE_BUDGET`() {
        // One combined count: backend main code and console production code.
        assertWithinBudget(
            "jscpd:ignore-start blocks in main code",
            "JSCPD_IGNORE_BUDGET",
            JSCPD_IGNORE_BUDGET,
            occurrences(backendMain() + consoleProduction(), JSCPD_IGNORE),
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
                SUPPRESS.findAll(text).filterNot { hasReason(text, it.range.first, ReasonStyle.SLASHES) }.map {
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
    fun `every console eslint-disable states its reason`() {
        val violations =
            consoleProduction().flatMap { file ->
                val text = file.readText()
                ESLINT_DISABLE.findAll(text).filterNot { hasReason(text, it.range.first, ReasonStyle.ESLINT) }.map {
                    violation(
                        file.path,
                        lineOf(text, it.range.first),
                        "eslint-disable without a reason (` -- reason` or a // comment on the line above)",
                    )
                }
            }
        assertNoViolations("Every eslint-disable states why (root CLAUDE.md §5)", violations)
    }

    @Test
    fun `an annotation with several ids counts once`() {
        assertEquals(1, countOf("@Suppress(\"A\", \"B\", \"C\") fun f() {}", SUPPRESS))
        assertEquals(2, countOf("@Suppress(\"A\")\nfun f() {}\n@Suppress(\"B\")\nfun g() {}", SUPPRESS))
    }

    @Test
    fun `the three eslint-disable forms each count once`() {
        assertEquals(1, countOf("/* eslint-disable no-x */", ESLINT_DISABLE))
        assertEquals(1, countOf("// eslint-disable-next-line no-x", ESLINT_DISABLE))
        assertEquals(1, countOf("foo(); // eslint-disable-line no-x", ESLINT_DISABLE))
        assertEquals(0, countOf("/* eslint-enable no-x */", ESLINT_DISABLE))
    }

    @Test
    fun `a directive inside a string literal is counted`() {
        assertEquals(1, countOf("val s = \"eslint-disable-next-line\"", ESLINT_DISABLE))
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
        fun reasoned(text: String) = hasReason(text, text.indexOf("@Suppress"), ReasonStyle.SLASHES)
        assertTrue(reasoned("@Suppress(\"A\") // why\nfun f() {}"), "same line")
        assertTrue(reasoned("// why\n@Suppress(\"A\")\nfun f() {}"), "line above")
        assertTrue(reasoned("@Suppress(\n    \"A\", // why\n)\nfun f() {}"), "inside the annotation")
        assertFalse(reasoned("@Suppress(\"A\")\nfun f() {}"), "no reason")
        assertFalse(reasoned("// why\n\n@Suppress(\"A\")\nfun f() {}"), "a blank line breaks the link")
        assertFalse(reasoned("val x = 1\n@Suppress(\"A\")\nfun f() {}"), "code above is no reason")
    }

    @Test
    fun `an eslint-disable has a reason after two dashes or a comment above`() {
        fun reasoned(text: String) = hasReason(text, text.indexOf("eslint-disable"), ReasonStyle.ESLINT)
        assertTrue(reasoned("// eslint-disable-next-line no-x -- why\nfoo()"))
        assertTrue(reasoned("{/* eslint-disable-next-line no-x -- why */}"))
        assertTrue(reasoned("// why\n// eslint-disable-next-line no-x\nfoo()"), "comment above")
        assertFalse(reasoned("foo(); // eslint-disable-line no-x"), "no reason")
        assertFalse(reasoned("foo()\n// eslint-disable-next-line no-x\nfoo()"), "code above is no reason")
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

    /** The files `console/scripts/size.mjs` calls production code: no tests, no shadcn primitives, no `.d.ts`. */
    private fun consoleProduction(): List<File> {
        val console = File(Sources.repoRoot, "console")
        return listOf("app", "components", "lib", "types")
            .map { File(console, it) }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile } }
            .plus(File(console, "middleware.ts"))
            .filter { it.isFile }
            .filter { it.extension == "ts" || (it.extension == "tsx" && !it.relativeTo(console).startsWith("types")) }
            .filterNot { it.name.contains(".test.") || it.name.endsWith(".d.ts") }
            .filterNot { it.relativeTo(console).invariantSeparatorsPath.startsWith("components/ui/") }
            .sortedBy { it.path }
    }

    private companion object {
        const val SOURCE_FILE = "app/architecture/src/test/kotlin/homer/architecture/SuppressionBudgetTest.kt"

        // Counts on 2026-11-17 (GH #310); lower a budget when its count falls, never raise one to fit a new suppression.
        const val SUPPRESS_BUDGET = 51
        const val ESLINT_DISABLE_BUDGET = 15
        const val JSCPD_IGNORE_BUDGET = 5
        const val REPORT_ONLY_BUDGET = 0
    }
}

internal val SUPPRESS = Regex("""@Suppress\(""")
internal val ESLINT_DISABLE = Regex("""\beslint-disable(?:-next-line|-line)?\b""")
internal val JSCPD_IGNORE = Regex("""jscpd:ignore-start""")
internal val REPORT_ONLY_TAG = Regex("""(?m)^\s*@Tag\(REPORT_ONLY\)""")

internal fun countOf(
    text: String,
    pattern: Regex,
): Int = pattern.findAll(text).count()

internal enum class ReasonStyle { SLASHES, ESLINT }

/**
 * Whether the directive at [offset] states why: a comment on the rest of its line (for `@Suppress(`, up to the
 * line of its closing parenthesis, so one inside the annotation counts) or a `//` comment on the line directly
 * above; [ReasonStyle.ESLINT] asks for ESLint's own ` -- reason` on the line instead of any comment, since the
 * directive itself sits in one.
 */
internal fun hasReason(
    text: String,
    offset: Int,
    style: ReasonStyle,
): Boolean {
    val segment = text.substring(offset, lineEndOf(text, offset, style))
    val onLine = if (style == ReasonStyle.ESLINT) " -- " in segment else "//" in segment || "/*" in segment
    if (onLine) return true
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    if (lineStart == 0) return false
    val above = text.substring(text.lastIndexOf('\n', lineStart - 2) + 1, lineStart - 1)
    return above.trimStart().startsWith("//")
}

private fun lineEndOf(
    text: String,
    offset: Int,
    style: ReasonStyle,
): Int {
    var end = offset
    if (style == ReasonStyle.SLASHES) {
        var depth = 0
        var i = text.indexOf('(', offset)
        while (i in text.indices) {
            if (text[i] == '(') depth++
            if (text[i] == ')' && --depth == 0) break
            i++
        }
        end = i.coerceAtMost(text.length)
    }
    return text.indexOf('\n', end).let { if (it < 0) text.length else it }
}
