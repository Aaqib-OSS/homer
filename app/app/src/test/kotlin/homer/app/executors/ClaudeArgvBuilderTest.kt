package homer.app.executors

import homer.app.models.ClaudeCmdOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeArgvBuilderTest {
    private val tail = listOf("--output-format", "stream-json", "--print", "--verbose")

    private fun argv(opts: ClaudeCmdOptions) = ClaudeArgvBuilder().build("claude", opts, emptyMap())

    @Test
    fun `variadic blocks are directly followed by the fixed tail whatever raw options precede it`() {
        val optionSets =
            listOf(
                ClaudeCmdOptions(mcpConfig = listOf("a.json", "b.json")),
                ClaudeCmdOptions(addDir = listOf("/x", "/y", "/z")),
                ClaudeCmdOptions(file = listOf("f1")),
                ClaudeCmdOptions(mcpConfig = listOf("a.json"), addDir = listOf("/x", "/y"), file = listOf("f1")),
            )
        optionSets.forEach { base ->
            listOf(null, listOf("--continue"), listOf("bare-value", "-p")).forEach { raw ->
                val argv = argv(base.copy(options = raw))

                assertEquals(tail, argv.takeLast(tail.size), "the fixed tail closes the argv: $argv")
                raw?.let { assertTrue(argv.indexOf(it.first()) < argv.indexOf("--output-format"), "raw options stay before the tail") }
                val lastVariadic = listOf("--mcp-config", "--add-dir", "--file").filter { it in argv }.maxOf { argv.indexOf(it) }
                val block = argv.subList(lastVariadic + 1, argv.size - tail.size)
                assertTrue(block.none { it.startsWith("-") }, "the last variadic block holds only its values: $argv")
                raw?.let { r -> assertTrue(argv.indexOf(r.last()) < lastVariadic, "raw options precede every variadic block: $argv") }
            }
        }
    }
}
