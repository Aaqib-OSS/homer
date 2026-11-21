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
 * Structural backstop for [`app/AGENT.md`](../../../../../AGENT.md): every complete-agent YAML/JSON block must
 * deserialize via [Agent.serializer] and validate against `homer-agent.schema.json`. Field-table drift is
 * [SchemaDocTableContractTest]'s job; misdescribed validation semantics rely on review.
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
