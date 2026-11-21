package homer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The layer map against the "Layered architecture" diagram of app/CLAUDE.md: a name one of them adds
 * and the other misses is a type that silently takes its package default, so the two are compared here
 * rather than hand-synced.
 */
class LayersTest {
    @Test
    fun `every diagram row names the types the layer map classifies`() {
        val diagram = diagramRows(File(Sources.repoRoot, "app/CLAUDE.md").readText())
        val mismatches =
            Layer.rows.flatMap { (layer, classified) ->
                val named = diagram[layer.label].orEmpty()
                val unclassified = named - classified
                val undrawn = classified - named - NOT_DRAWN
                listOfNotNull(
                    unclassified.takeIf { it.isNotEmpty() }?.let { "${layer.label}: the diagram names $it, the map does not classify it" },
                    undrawn.takeIf { it.isNotEmpty() }?.let { "${layer.label}: the map classifies $it, the diagram does not name it" },
                )
            }
        assertEquals(emptyList(), mismatches, "app/CLAUDE.md \"Layered architecture\" and Layers.kt disagree")
    }

    @Test
    fun `a multi-word row label keeps its names`() {
        val guide = "## Layered architecture\n\n```\nHTTP API     Application.kt\n```\n"
        assertEquals(mapOf("HTTP API" to setOf("Application.kt")), diagramRows(guide))
    }

    @Test
    fun `a diagram row that names no types fails loudly`() {
        val guide = "## Layered architecture\n\n```\nServices\n```\n"
        val failure = assertFailsWith<IllegalStateException> { diagramRows(guide) }
        assertEquals("diagram row `Services` names no types", failure.message)
    }

    /** The type names each row of the diagram gives, by the row's label; `·` separates them and `(+ …)` annotates one. */
    private fun diagramRows(guide: String): Map<String, Set<String>> =
        DIAGRAM
            .find(guide)
            ?.groupValues
            ?.get(1)
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.associate { line ->
                val parts = line.trim().split(COLUMN_GAP, limit = 2)
                val label = parts[0]
                val names = parts.getOrElse(1) { error("diagram row `$label` names no types") }
                label to names.split(SEPARATOR).filter(String::isNotBlank).toSet()
            }
            ?: error("app/CLAUDE.md has no \"Layered architecture\" diagram")

    private companion object {
        /** `Process` counts as Execution after "Status precedence rules", though the diagram does not name it. */
        val NOT_DRAWN = setOf("Process")

        val DIAGRAM = Regex("""## Layered architecture\n\n```\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)

        /** The diagram's columns align on runs of spaces; a single space inside a label or a name is not a column edge. */
        val COLUMN_GAP = Regex("""\s{2,}""")
        val SEPARATOR = Regex("""[\s·+()]+""")
    }
}
