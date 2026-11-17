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
     * Why [ref] names no test in [sources] (file path to text), or null when it resolves: some file
     * declares the class and, for a method ref, holds a `@Test`-annotated function of that name.
     */
    fun unresolved(
        ref: TestRef,
        sources: Map<String, String>,
    ): String? {
        val declaring = Regex("""\b(?:class|object)\s+${Regex.escape(ref.className)}\b""")
        val files = sources.filterValues { declaring.containsMatchIn(it) }
        if (files.isEmpty()) return "no test class ${ref.className}"
        val method = ref.method ?: return null
        val found = files.values.any { hasTestFunction(it, method) }
        return if (found) null else "${ref.className} has no @Test function `$method`"
    }

    private fun hasTestFunction(
        source: String,
        method: String,
    ): Boolean {
        val lines = source.lines()
        val declaration = "fun `$method`("
        return lines.indices.any { at ->
            declaration in lines[at] &&
                (at - 1 downTo maxOf(0, at - 8))
                    .map { lines[it].trim() }
                    .takeWhile { !it.endsWith("}") }
                    .any { Regex("""@\w*Test\b""").containsMatchIn(it) && it.startsWith("@") }
        }
    }
}
