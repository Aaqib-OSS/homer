package homer.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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

    /** The type names each row of the diagram gives, by the row's label; `·` separates them and `(+ …)` annotates one. */
    private fun diagramRows(guide: String): Map<String, Set<String>> =
        DIAGRAM
            .find(guide)
            ?.groupValues
            ?.get(1)
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?.associate { line ->
                val (label, names) = line.trim().split(WHITESPACE, limit = 2)
                label to names.split(SEPARATOR).filter(String::isNotBlank).toSet()
            }
            ?: error("app/CLAUDE.md has no \"Layered architecture\" diagram")

    private companion object {
        /** `Process` counts as Execution after "Status precedence rules", though the diagram does not name it. */
        val NOT_DRAWN = setOf("Process")

        val DIAGRAM = Regex("""## Layered architecture\n\n```\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
        val WHITESPACE = Regex("""\s+""")
        val SEPARATOR = Regex("""[\s·+()]+""")
    }
}
