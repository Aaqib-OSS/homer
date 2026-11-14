package homer.app

import com.charleskorn.kaml.Yaml
import com.networknt.schema.InputFormat
import homer.app.fixtures.repoRoot
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.TokenConfigObject
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
        val errors = schema.validate(toJson(config).toString(), InputFormat.JSON)
        if (errors.isEmpty()) return null
        return "Schema validation failed at SERVER.md:${block.line} (${block.lang}):\n" +
            errors.joinToString("\n") { "    - ${it.instanceLocation}: ${it.message}" }
    }

    /** [config] as JSON, with its `apiAuth` written by [authJson]. */
    private fun toJson(config: AppConfig): JsonObject {
        val auth = config.server.apiAuth ?: return encoder.encodeToJsonElement(AppConfig.serializer(), config).jsonObject
        val json = encoder.encodeToJsonElement(AppConfig.serializer(), config.copy(server = config.server.copy(apiAuth = null))).jsonObject
        val server = JsonObject(json.getValue("server").jsonObject + ("apiAuth" to authJson(auth)))
        return JsonObject(json + ("server" to server))
    }

    /** Bearer tokens go through their object form by hand: `TokenConfig`'s own serializer cannot encode (#238). */
    private fun authJson(auth: AuthConfig): JsonElement =
        when (auth) {
            is AuthConfig.Bearer -> {
                val tokens =
                    auth.tokens.mapValues { (_, t) ->
                        encoder.encodeToJsonElement(
                            TokenConfigObject.serializer(),
                            TokenConfigObject(t.token, t.role, t.grants, t.endpoints),
                        )
                    }
                JsonObject(mapOf("type" to JsonPrimitive("bearer"), "tokens" to JsonObject(tokens)))
            }

            is AuthConfig.Combined -> {
                JsonObject(mapOf("type" to JsonPrimitive("combined"), "schemes" to JsonArray(auth.schemes.map(::authJson))))
            }

            else -> {
                encoder.encodeToJsonElement(AuthConfig.serializer(), auth)
            }
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
