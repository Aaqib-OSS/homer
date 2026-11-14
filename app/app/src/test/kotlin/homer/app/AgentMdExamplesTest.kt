package homer.app

import com.charleskorn.kaml.Yaml
import com.networknt.schema.InputFormat
import homer.app.fixtures.repoRoot
import homer.app.models.Agent
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural backstop for [`app/AGENT.md`](../../../../../AGENT.md).
 *
 * Every complete-agent YAML/JSON code block in AGENT.md must:
 *  1. Deserialize via [Agent.serializer] (matches how `AgentsService`
 *     loads agents from disk).
 *  2. Re-validate against `homer-agent.schema.json`.
 *
 * Catches structural drift like:
 *  - `agents: [code-reviewer]` list form when the model expects a map.
 *  - `resultEnvParam: { ... }` object form when the model expects an array.
 *  - Renamed/removed kaml tags after a `Command` sealed-class refactor.
 *
 * A second test (scan in `DeprecatedSyntaxScan.kt`) scans every yaml/json block (partial snippets included, any
 * indent, flow style too) and fails on a `type` of `STRING`/`NUMBER`/`ANY` and on
 * a `queryParams` key that does not belong to a `!<agent>` / `"type": "agent"`
 * command (that one is a different, non-deprecated field; it is recognised by
 * nesting deeper than the nearest preceding command header). A block that
 * deliberately shows the legacy syntax opts out with
 * `<!-- legacy-syntax-example -->` on the line before its fence.
 *
 * Does NOT catch table or prose drift: field-table rows, required flags
 * and schema-stated defaults are [SchemaDocTableContractTest]'s job;
 * misdescribed validation semantics rely on review (root §10).
 */
class AgentMdExamplesTest {
    private val agentMd = File(repoRoot, "app/AGENT.md")
    private val schema = MdExampleBlocks.schema("homer-agent.schema.json")
    private val json = MdExampleBlocks.encoder

    @Test
    fun `every complete-agent code block in AGENT md deserializes and validates against schema`() {
        // A block is a complete agent when its top level sets both `name` and `commands`; others are fragments.
        val blocks = MdExampleBlocks.blocksSetting(agentMd.readText(), listOf("name", "commands"))
        assertTrue(
            blocks.isNotEmpty(),
            "Expected at least one complete-agent block in AGENT.md — extractor regression?",
        )

        val failures = mutableListOf<String>()
        for (block in blocks) {
            try {
                val agent =
                    when (block.lang) {
                        "yaml" -> Yaml.default.decodeFromString(Agent.serializer(), block.content)
                        "json" -> json.decodeFromString(Agent.serializer(), block.content)
                        else -> error("unreachable: ${block.lang}")
                    }
                val asJson = json.encodeToString(Agent.serializer(), agent)
                val errors = schema.validate(asJson, InputFormat.JSON)
                if (errors.isNotEmpty()) {
                    failures += renderFailure(block, "Schema validation failed", errors.joinToString("\n") { "    - ${it.message}" })
                }
            } catch (e: Exception) {
                failures += renderFailure(block, "Deserialization failed", "    ${e.message}")
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                "AGENT.md drift detected (${failures.size} of ${blocks.size} blocks failed):\n\n" +
                    failures.joinToString("\n\n"),
            )
        }
    }

    @Test
    fun `no AGENT md example uses the deprecated param syntax unless marked`() {
        val scan = scanDeprecatedSyntax(agentMd.readText())
        assertTrue(scan.blocks > 0, "No yaml/json blocks scanned in AGENT.md — scanner regression?")
        assertTrue(scan.markedBlocks > 0, "No <!-- legacy-syntax-example --> block found — opt-out path unpinned?")
        if (scan.offenders.isNotEmpty()) {
            fail(
                "Deprecated param syntax in AGENT.md examples (use typed inputs, or mark a deliberate " +
                    "legacy example with <!-- legacy-syntax-example -->):\n" + scan.offenders.joinToString("\n"),
            )
        }
    }

    private fun renderFailure(
        block: MdBlock,
        reason: String,
        detail: String,
    ): String {
        val preview =
            block.content
                .lines()
                .take(4)
                .joinToString("\n") { "    $it" }
        return "$reason at AGENT.md:${block.line} (${block.lang}):\n$detail\n  Block preview:\n$preview\n    ..."
    }
}
