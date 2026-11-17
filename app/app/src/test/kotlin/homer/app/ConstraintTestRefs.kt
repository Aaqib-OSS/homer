package homer.app

/** One `- **Constraint:** ...` bullet of a backend CLAUDE.md guide, its wrapped lines joined by single spaces. */
internal data class ConstraintItem(
    val file: String,
    val line: Int,
    val text: String,
)

/** A test a constraint names: [className] alone, or [className] and one of its `@Test` functions, [method]. */
internal data class TestRef(
    val className: String,
    val method: String?,
)

/**
 * Reads the `Constraint:` bullets of the backend CLAUDE.md guides and the tests they name. A bullet
 * ends with `Enforced by `Class#test name`.`: one or more backticked refs, a bare `Class` meaning
 * the whole class, joined by `,` or `and`. See app/CLAUDE.md § Key service responsibilities.
 */
internal object ConstraintTestRefs {
    const val MARKER = "**Constraint:**"

    private val ENFORCED = Regex("""Enforced by ((?:`[^`]+`(?:,? and |, )?)+)\.$""")
    private val BACKTICKED = Regex("`([^`]+)`")
    private val CLASS_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

    /** Every constraint bullet in [lines]: a bullet runs until a blank line or the next bullet. */
    fun items(
        file: String,
        lines: List<String>,
    ): List<ConstraintItem> {
        val items = mutableListOf<ConstraintItem>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].trimStart().startsWith("- $MARKER")) {
                i++
                continue
            }
            val start = i
            val parts = mutableListOf(lines[i].trim())
            i++
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].trimStart().startsWith("- ")) {
                parts += lines[i].trim()
                i++
            }
            items += ConstraintItem(file, start + 1, parts.joinToString(" "))
        }
        return items
    }

    /** The tests [text] ends by naming; empty when it has no `Enforced by` tail or a ref is malformed. */
    fun refs(text: String): List<TestRef> {
        val tail = ENFORCED.find(text.trim())?.groupValues?.get(1) ?: return emptyList()
        val parsed = BACKTICKED.findAll(tail).map { parseRef(it.groupValues[1]) }.toList()
        return if (parsed.any { it == null }) emptyList() else parsed.filterNotNull()
    }

    /** `Class` or `Class#method name`; null when the class part is not an identifier or the method is blank. */
    fun parseRef(token: String): TestRef? {
        val hash = token.indexOf('#')
        val className = if (hash < 0) token else token.substring(0, hash)
        val method = if (hash < 0) null else token.substring(hash + 1)
        if (!CLASS_NAME.matches(className)) return null
        if (method != null && method.isBlank()) return null
        return TestRef(className, method)
    }

    /**
     * Why [ref] names no test in [sources] (file path to text), or null when it resolves: a file other
     * than this check's own tests declares the class at the start of a line and, for a method ref, the
     * class body holds a `@Test`-annotated function of that name that is not `@Disabled`.
     */
    fun unresolved(
        ref: TestRef,
        sources: Map<String, String>,
    ): String? {
        val declaring =
            Regex("""(?m)^\s*(?:(?:public|internal|private|open|abstract|data)\s+)*(?:class|object)\s+${Regex.escape(ref.className)}\b""")
        val bodies =
            sources
                .filterKeys { !it.endsWith("ConstraintTestRefsTest.kt") }
                .values
                .mapNotNull { source -> declaring.find(source)?.let { classBody(source, it.range.first) } }
        if (bodies.isEmpty()) return "no test class ${ref.className}"
        val method = ref.method ?: return null
        val states = bodies.map { testState(it, method) }
        return when {
            true in states -> null
            false in states -> "${ref.className} has only a @Disabled test `$method`"
            else -> "${ref.className} has no @Test function `$method`"
        }
    }

    /** The lines from the class declaration to the first column-0 closing brace (ktlint's top-level end). */
    private fun classBody(
        source: String,
        declarationAt: Int,
    ): List<String> {
        val lines = source.substring(declarationAt).lines()
        val end = lines.drop(1).indexOfFirst { it == "}" }
        return if (end < 0) lines else lines.take(end + 2)
    }

    /** True: an enabled test of that name; false: only disabled ones; null: none. */
    private fun testState(
        body: List<String>,
        method: String,
    ): Boolean? {
        val declaration = "fun `$method`("
        val states =
            body.indices.filter { declaration in body[it] }.mapNotNull { at ->
                val annotations =
                    (at - 1 downTo maxOf(0, at - 8))
                        .map { body[it].trim() }
                        .takeWhile { !it.endsWith("}") }
                        .filter { it.startsWith("@") }
                when {
                    annotations.none { Regex("""@\w*Test\b""").containsMatchIn(it) } -> null
                    annotations.any { it.startsWith("@Disabled") } -> false
                    else -> true
                }
            }
        return if (true in states) {
            true
        } else if (false in states) {
            false
        } else {
            null
        }
    }
}
