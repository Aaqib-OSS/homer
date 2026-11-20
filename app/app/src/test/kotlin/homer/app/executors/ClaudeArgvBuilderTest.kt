package homer.app.executors

import homer.app.models.ClaudeCmdOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClaudeArgvBuilderTest {
    private val tail = listOf("--output-format", "stream-json", "--print", "--verbose")

    private fun argv(opts: ClaudeCmdOptions) = ClaudeArgvBuilder().build("claude", opts, emptyMap())

    @Test
    fun `a variadic option is closed by a flag, so it cannot swallow later arguments`() {
        val optionSets =
            listOf(
                ClaudeCmdOptions(mcpConfig = listOf("a.json", "b.json")) to ("--mcp-config" to 2),
                ClaudeCmdOptions(addDir = listOf("/x", "/y", "/z")) to ("--add-dir" to 3),
                ClaudeCmdOptions(file = listOf("f1")) to ("--file" to 1),
            )
        optionSets.forEach { (opts, flagAndCount) ->
            val (flag, count) = flagAndCount
            val argv = argv(opts)
            val after = argv.drop(argv.indexOf(flag) + 1)
            assertTrue(after[count].startsWith("--"), "$flag's $count value(s) are followed by a flag in $argv")
            assertEquals(tail, argv.takeLast(tail.size), "the fixed flag block closes the argv")
        }
    }

    @Test
    fun `every variadic option set together keeps each value block closed by a flag`() {
        val argv = argv(ClaudeCmdOptions(mcpConfig = listOf("a.json"), addDir = listOf("/x", "/y"), file = listOf("f1")))

        listOf("--mcp-config" to 1, "--add-dir" to 2, "--file" to 1).forEach { (flag, count) ->
            val after = argv.drop(argv.indexOf(flag) + 1)
            assertTrue(after[count].startsWith("--"), "$flag is followed by a flag in $argv")
        }
    }
}
