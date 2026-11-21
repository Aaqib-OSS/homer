package homer.architecture

import com.lemonappdev.konsist.api.provider.KoLocationProvider
import com.lemonappdev.konsist.api.provider.KoParametersProvider
import com.lemonappdev.konsist.api.provider.KoPathProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Size limits on the main code of :app, agentdebug and homer-runner, plus a line cap on their test files.
 * They complement the CRAP check (ADR-0081); app/CLAUDE.md "Architecture tests" records them.
 */
class SizeLimitsTest {
    @Test
    fun `source files stay within MAX_FILE_LINES`() {
        val sizes =
            mainScopes().flatMap { it.files }.map { file ->
                val lines = File(file.path).readLines().size
                Size(lines, violation(file.path, 1, "${file.name}.kt has $lines lines (limit $MAX_FILE_LINES)"))
            }
        assertWithinCap("Source files ≤ $MAX_FILE_LINES lines (root CLAUDE.md section 2)", "MAX_FILE_LINES", MAX_FILE_LINES, sizes)
    }

    @Test
    fun `test files stay within MAX_TEST_FILE_LINES`() {
        val sizes =
            Sources.modules.map { Sources.test(it) }.flatMap { it.files }.map { file ->
                val lines = File(file.path).readLines().size
                Size(lines, violation(file.path, 1, "${file.name}.kt has $lines lines (limit $MAX_TEST_FILE_LINES)"))
            }
        assertWithinCap(
            "Test files ≤ $MAX_TEST_FILE_LINES lines (root CLAUDE.md section 2)",
            "MAX_TEST_FILE_LINES",
            MAX_TEST_FILE_LINES,
            sizes,
        )
    }

    @Test
    fun `classes stay within MAX_CLASS_LINES`() {
        val sizes =
            mainScopes().flatMap { it.classesAndObjects(includeNested = false, includeLocal = false) }.map { cls ->
                val lines = declarationLines(cls.text)
                Size(lines, violation(cls, "${cls.name} has $lines lines (limit $MAX_CLASS_LINES)"))
            }
        assertWithinCap("Classes ≤ $MAX_CLASS_LINES lines (root CLAUDE.md section 2)", "MAX_CLASS_LINES", MAX_CLASS_LINES, sizes)
    }

    @Test
    fun `function bodies stay within MAX_BODY_LINES`() {
        val sizes =
            mainScopes().flatMap { it.functions(includeNested = true, includeLocal = false) }.mapNotNull { fn ->
                if (!fn.hasBlockBody && !fn.hasExpressionBody) return@mapNotNull null
                val lines = KotlinText.bodyLines(fn.text) ?: return@mapNotNull null
                Size(lines, violation(fn, "${fn.name} has a $lines-line body (limit $MAX_BODY_LINES)"))
            }
        assertWithinCap("Function bodies ≤ $MAX_BODY_LINES lines (root CLAUDE.md section 2)", "MAX_BODY_LINES", MAX_BODY_LINES, sizes)
    }

    @Test
    fun `functions and constructors of non-data classes stay within MAX_PARAMETERS`() {
        val sizes =
            mainScopes().flatMap { scope ->
                val functions =
                    scope
                        .functions(includeNested = true, includeLocal = false)
                        .filterNot { it.hasOverrideModifier }
                        .map { fn -> parameterSize(fn, fn.name) }
                val constructors =
                    scope.classes(includeNested = true, includeLocal = false).filterNot { it.hasDataModifier }.flatMap { cls ->
                        (listOfNotNull(cls.primaryConstructor) + cls.secondaryConstructors).map { ctor ->
                            parameterSize(ctor, "${cls.name} constructor")
                        }
                    }
                functions + constructors
            }
        assertWithinCap("Parameters ≤ $MAX_PARAMETERS (root CLAUDE.md section 2)", "MAX_PARAMETERS", MAX_PARAMETERS, sizes)
    }

    @Test
    fun `a cap is stale only when it sits more than 5 percent above the current maximum`() {
        assertNull(staleCap(cap = 600, currentMax = 600), "at the maximum")
        assertNull(staleCap(cap = 600, currentMax = 570), "exactly the slack (30) above")
        assertEquals(569, staleCap(cap = 600, currentMax = 569), "one past the slack")
        assertNull(staleCap(cap = 13, currentMax = 12), "the slack is at least 1")
        assertEquals(11, staleCap(cap = 13, currentMax = 11))
        assertNull(staleCap(cap = 3_300, currentMax = 3_135), "slack rounds up: 165")
        assertEquals(3_134, staleCap(cap = 3_300, currentMax = 3_134))
    }

    @Test
    fun `a maximum is measured only when some declaration has a size`() {
        assertNull(measuredMax(emptyList()), "no declaration")
        assertNull(measuredMax(listOf(0, 0)), "only zeros")
        assertEquals(7, measuredMax(listOf(3, 7, 0)))
    }

    /** One measured declaration: its size and the violation to report when it is over the cap. */
    private class Size(
        val value: Int,
        val violation: Violation,
    )

    private fun assertWithinCap(
        rule: String,
        constant: String,
        cap: Int,
        sizes: List<Size>,
    ) {
        assertNoViolations(rule, sizes.filter { it.value > cap }.map { it.violation })
        val current = measuredMax(sizes.map { it.value })
        if (current == null) fail("$rule: $constant ($SOURCE_FILE) measured nothing, so its cap cannot be ratcheted")
        staleCap(cap, current)?.let { lowerTo ->
            fail(
                "$rule: $constant ($SOURCE_FILE) is $cap but the current maximum is $current; " +
                    "lower it to $lowerTo (a cap may sit at most ${slack(cap)} above the maximum, ADR-0082)",
            )
        }
    }

    private fun <T> parameterSize(
        declaration: T,
        name: String,
    ): Size where T : KoParametersProvider, T : KoPathProvider, T : KoLocationProvider {
        val count = declaration.numParameters
        return Size(count, violation(declaration, "$name has $count parameters (limit $MAX_PARAMETERS)"))
    }

    private fun mainScopes() = Sources.modules.map { Sources.main(it) }

    /** Lines of a declaration without its leading KDoc and annotations. */
    private fun declarationLines(text: String): Int {
        val code = KotlinText.codeOnly(text)
        val start = DECLARATION_KEYWORD.find(code)?.range?.first ?: 0
        return code
            .substring(start)
            .trimEnd()
            .lines()
            .size
    }

    private companion object {
        const val SOURCE_FILE = "app/architecture/src/test/kotlin/homer/architecture/SizeLimitsTest.kt"
        const val MAX_FILE_LINES = 514
        const val MAX_TEST_FILE_LINES = 3_300
        const val MAX_CLASS_LINES = 400
        const val MAX_BODY_LINES = 60

        /**
         * The widest non-override function or non-`data` constructor when the rule came in (ADR-0082); it may
         * only be lowered. An override is skipped (the declaration it overrides is checked, when it is ours); a
         * `data` class is a parameter object.
         */
        const val MAX_PARAMETERS = 13
        val DECLARATION_KEYWORD = Regex("""\b(fun|class|object|interface)\b""")
    }
}

/** Slack a cap may sit above the current maximum: 5% of the cap, rounded up, at least 1. */
internal fun slack(cap: Int): Int = maxOf(1, (cap + 19) / 20)

/** The value to lower [cap] to (the current maximum) when it sits more than the slack above it, else null. */
internal fun staleCap(
    cap: Int,
    currentMax: Int,
): Int? = if (cap - currentMax > slack(cap)) currentMax else null

/** The largest measured value, or null when nothing was measured (no declaration, or only zeros). */
internal fun measuredMax(values: List<Int>): Int? = values.maxOrNull()?.takeIf { it > 0 }
