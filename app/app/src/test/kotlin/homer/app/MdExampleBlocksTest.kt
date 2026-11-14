package homer.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MdExampleBlocksTest {
    @Test
    fun `keeps only the blocks whose top level sets every key`() {
        val text =
            """
            ```yaml
            name: a
            commands: []
            ```

            ```yaml
            name: b
            ```

            ```json
            {
              "name": "c",
              "commands": []
            }
            ```
            """.trimIndent()
        val blocks = MdExampleBlocks.blocksSetting(text, listOf("name", "commands"))
        assertEquals(listOf("yaml" to 1, "json" to 10), blocks.map { it.lang to it.line })
    }

    @Test
    fun `an unclosed final fence fails instead of dropping the block`() {
        val text =
            """
            ```yaml
            name: a
            ```
            ```json
            {
              "name": "b"
            """.trimIndent()
        val error = assertFailsWith<IllegalStateException> { MdExampleBlocks.blocksSetting(text, listOf("name")) }
        assertTrue("line 4" in error.message.orEmpty(), error.message)
    }
}
