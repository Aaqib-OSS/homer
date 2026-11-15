package homer.architecture

import com.lemonappdev.konsist.api.provider.KoLocationProvider
import com.lemonappdev.konsist.api.provider.KoParametersProvider
import com.lemonappdev.konsist.api.provider.KoPathProvider
import java.io.File
import kotlin.test.Test

/**
 * Size limits on the main code of :app, agentdebug and homer-runner, plus a line cap on their test files
 * — the complement of the CRAP check
 * (ADR-0081), which misses long code that branches little. They restate root CLAUDE.md §2 ("If you write
 * 200 lines and it could be 50, rewrite it") as numbers, plus a cap on parameter lists, which a parameter
 * object keeps short; app/CLAUDE.md "Architecture tests" records them.
 */
class SizeLimitsTest {
    @Test
    fun `source files have at most 600 lines`() {
        val violations =
            mainScopes().flatMap { it.files }.mapNotNull { file ->
                val lines = File(file.path).readLines().size
                if (lines <= MAX_FILE_LINES) return@mapNotNull null
                violation(file.path, 1, "${file.name}.kt has $lines lines (limit $MAX_FILE_LINES)")
            }
        assertNoViolations("Source files ≤ $MAX_FILE_LINES lines (root CLAUDE.md §2)", violations)
    }

    @Test
    fun `test files have at most 3300 lines`() {
        val violations =
            Sources.modules.map { Sources.test(it) }.flatMap { it.files }.mapNotNull { file ->
                val lines = File(file.path).readLines().size
                if (lines <= MAX_TEST_FILE_LINES) return@mapNotNull null
                violation(file.path, 1, "${file.name}.kt has $lines lines (limit $MAX_TEST_FILE_LINES)")
            }
        assertNoViolations("Test files ≤ $MAX_TEST_FILE_LINES lines (root CLAUDE.md §2)", violations)
    }

    @Test
    fun `classes have at most 400 lines`() {
        val violations =
            mainScopes().flatMap { it.classesAndObjects(includeNested = false, includeLocal = false) }.mapNotNull { cls ->
                val lines = declarationLines(cls.text)
                if (lines <= MAX_CLASS_LINES) return@mapNotNull null
                violation(cls, "${cls.name} has $lines lines (limit $MAX_CLASS_LINES)")
            }
        assertNoViolations("Classes ≤ $MAX_CLASS_LINES lines (root CLAUDE.md §2)", violations)
    }

    @Test
    fun `function bodies have at most 60 lines`() {
        val violations =
            mainScopes().flatMap { it.functions(includeNested = true, includeLocal = false) }.mapNotNull { fn ->
                if (!fn.hasBlockBody && !fn.hasExpressionBody) return@mapNotNull null
                val lines = KotlinText.bodyLines(fn.text) ?: return@mapNotNull null
                if (lines <= MAX_BODY_LINES) return@mapNotNull null
                violation(fn, "${fn.name} has a $lines-line body (limit $MAX_BODY_LINES)")
            }
        assertNoViolations("Function bodies ≤ $MAX_BODY_LINES lines (root CLAUDE.md §2)", violations)
    }

    @Test
    fun `functions and constructors of non-data classes have at most 13 parameters`() {
        val violations =
            mainScopes().flatMap { scope ->
                val functions =
                    scope
                        .functions(includeNested = true, includeLocal = false)
                        .filterNot { it.hasOverrideModifier }
                        .mapNotNull { fn -> parameterViolation(fn, fn.name) }
                val constructors =
                    scope.classes(includeNested = true, includeLocal = false).filterNot { it.hasDataModifier }.flatMap { cls ->
                        (listOfNotNull(cls.primaryConstructor) + cls.secondaryConstructors).mapNotNull { ctor ->
                            parameterViolation(ctor, "${cls.name} constructor")
                        }
                    }
                functions + constructors
            }
        assertNoViolations("Parameters ≤ $MAX_PARAMETERS (root CLAUDE.md §2)", violations)
    }

    private fun <T> parameterViolation(
        declaration: T,
        name: String,
    ): Violation? where T : KoParametersProvider, T : KoPathProvider, T : KoLocationProvider {
        val count = declaration.numParameters
        return if (count <= MAX_PARAMETERS) null else violation(declaration, "$name has $count parameters (limit $MAX_PARAMETERS)")
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
        const val MAX_FILE_LINES = 600
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
