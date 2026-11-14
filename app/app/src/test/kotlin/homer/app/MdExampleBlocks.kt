package homer.app

import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import homer.app.fixtures.repoRoot
import kotlinx.serialization.json.Json
import java.io.File

/** A fenced ```yaml or ```json block of a Markdown doc; [line] is its opening fence, 1-based. */
internal data class MdBlock(
    val lang: String,
    val content: String,
    val line: Int,
)

/** What `AgentMdExamplesTest` and `ServerMdExamplesTest` share: block extraction, the schema and the re-encoder. */
internal object MdExampleBlocks {
    private val topLevelYamlKey = Regex("^([A-Za-z]\\w*):", RegexOption.MULTILINE)

    // The docs' JSON blocks put top-level keys at a two-space indent.
    private val topLevelJsonKey = Regex("^ {2}\"(\\w+)\"\\s*:", RegexOption.MULTILINE)

    /** Encodes a decoded model back to JSON for schema validation, defaults included. */
    val encoder =
        Json {
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = false
        }

    fun schema(fileName: String): Schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(File(repoRoot, "app/schemas/$fileName").readText())

    /** The ```yaml / ```json blocks of [text] whose top level sets every key in [keys]; the others are fragments. */
    fun blocksSetting(
        text: String,
        keys: Collection<String>,
    ): List<MdBlock> = blocks(text).filter { topLevelKeys(it).containsAll(keys) }

    private fun blocks(text: String): List<MdBlock> {
        val lines = text.lines()
        val blocks = mutableListOf<MdBlock>()
        var start = -1
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd()
            when {
                start < 0 && (line == "```yaml" || line == "```json") -> {
                    start = i
                }

                start >= 0 && line == "```" -> {
                    blocks += MdBlock(lang(lines[start]), lines.subList(start + 1, i).joinToString("\n"), start + 1)
                    start = -1
                }
            }
        }
        // An unclosed fence would otherwise drop the doc's last example from validation without a word.
        check(start < 0) { "unclosed ${lines[start].trim()} fence opened at line ${start + 1}" }
        return blocks
    }

    private fun lang(fence: String): String = fence.trimEnd().removePrefix("```")

    private fun topLevelKeys(block: MdBlock): Set<String> {
        val key = if (block.lang == "yaml") topLevelYamlKey else topLevelJsonKey
        return key.findAll(block.content).map { it.groupValues[1] }.toSet()
    }
}
