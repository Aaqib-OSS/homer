package homer.app

import com.charleskorn.kaml.Yaml
import com.networknt.schema.InputFormat
import homer.app.fixtures.repoRoot
import homer.app.models.AppConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Structural backstop for [`app/SERVER.md`](../../../../../SERVER.md), the config twin of
 * [AgentMdExamplesTest]: every complete-config YAML/JSON block deserializes with [AppConfig.serializer]
 * the way `readStartupConfig` reads a config file (`Yaml.default`, plain `Json`), and the result
 * re-validates against `homer-config.schema.json`.
 *
 * A block is complete when its top level sets every key [AppConfig] requires (no default); every other
 * block is a fragment and is skipped.
 */
class ServerMdExamplesTest {
    private val serverMd = File(repoRoot, "app/SERVER.md")
    private val schema = MdExampleBlocks.schema("homer-config.schema.json")
    private val encoder = MdExampleBlocks.encoder

    @Test
    fun `every complete config block in SERVER md deserializes and validates against schema`() {
        val blocks = MdExampleBlocks.blocksSetting(serverMd.readText(), requiredKeys)
        assertEquals(
            COMPLETE_BLOCKS,
            blocks.size,
            "Complete config blocks in SERVER.md: update COMPLETE_BLOCKS when SERVER.md gains or loses a complete config example",
        )
        val failures = blocks.mapNotNull(::check)
        if (failures.isNotEmpty()) {
            fail("SERVER.md drift detected (${failures.size} of ${blocks.size} blocks failed):\n\n" + failures.joinToString("\n\n"))
        }
    }

    /** The failure for [block], or null when it parses and validates. */
    private fun check(block: MdBlock): String? {
        val config =
            try {
                if (block.lang == "yaml") {
                    Yaml.default.decodeFromString(AppConfig.serializer(), block.content)
                } else {
                    Json.decodeFromString(AppConfig.serializer(), block.content)
                }
            } catch (e: Exception) {
                return "Deserialization failed at SERVER.md:${block.line} (${block.lang}):\n    ${e.message}"
            }
        val errors = schema.validate(encoder.encodeToString(AppConfig.serializer(), config), InputFormat.JSON)
        if (errors.isEmpty()) return null
        return "Schema validation failed at SERVER.md:${block.line} (${block.lang}):\n" +
            errors.joinToString("\n") { "    - ${it.instanceLocation}: ${it.message}" }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val requiredKeys: List<String> =
        AppConfig.serializer().descriptor.let { d ->
            (0 until d.elementsCount).filterNot(d::isElementOptional).map(d::getElementName)
        }

    private companion object {
        const val COMPLETE_BLOCKS = 8
    }
}
