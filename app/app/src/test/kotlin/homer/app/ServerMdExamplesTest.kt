package homer.app

import com.charleskorn.kaml.Yaml
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import homer.app.fixtures.repoRoot
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Grant
import homer.app.models.TokenConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
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
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(File(repoRoot, "app/schemas/homer-config.schema.json").readText())
    private val encoder =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    @Test
    fun `every complete config block in SERVER md deserializes and validates against schema`() {
        val blocks = completeConfigBlocks(serverMd.readText())
        assertTrue(blocks.size >= 2, "Expected complete config blocks in SERVER.md, found ${blocks.size} — extractor regression?")
        val failures = blocks.mapNotNull(::check)
        if (failures.isNotEmpty()) {
            fail("SERVER.md drift detected (${failures.size} of ${blocks.size} blocks failed):\n\n" + failures.joinToString("\n\n"))
        }
    }

    /** The failure for [block], or null when it parses and validates. */
    private fun check(block: Block): String? {
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

    /**
     * [TokenConfig]'s serializer cannot encode (kaml's content-polymorphic base looks the class's own
     * serializer up again and recurses), so bearer tokens are written by hand as their object form.
     */
    private fun authJson(auth: AuthConfig): JsonElement =
        when (auth) {
            is AuthConfig.Bearer -> {
                val tokens =
                    auth.tokens.mapValues { (_, t) ->
                        encoder.encodeToJsonElement(TokenObject.serializer(), TokenObject(t.token, t.role, t.grants))
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

    @Serializable
    private data class TokenObject(
        val token: String,
        val role: String? = null,
        val grants: List<Grant> = emptyList(),
    )

    private data class Block(
        val lang: String,
        val content: String,
        val line: Int,
    )

    private fun completeConfigBlocks(text: String): List<Block> {
        val lines = text.lines()
        val blocks = mutableListOf<Block>()
        var start = -1
        lines.forEachIndexed { i, line ->
            when {
                start < 0 && (line == "```yaml" || line == "```json") -> {
                    start = i
                }

                start >= 0 && line == "```" -> {
                    blocks += Block(lines[start].removePrefix("```"), lines.subList(start + 1, i).joinToString("\n"), start + 1)
                    start = -1
                }
            }
        }
        return blocks.filter { requiredKeys.all(topLevelKeys(it)::contains) }
    }

    /** Top-level keys: unindented `key:` in YAML; `"key":` at the two-space indent SERVER.md's JSON blocks use. */
    private fun topLevelKeys(block: Block): Set<String> {
        val key =
            if (block.lang ==
                "yaml"
            ) {
                Regex("^([A-Za-z]\\w*):", RegexOption.MULTILINE)
            } else {
                Regex("^ {2}\"(\\w+)\"\\s*:", RegexOption.MULTILINE)
            }
        return key.findAll(block.content).map { it.groupValues[1] }.toSet()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val requiredKeys: List<String> =
        AppConfig.serializer().descriptor.let { d ->
            (0 until d.elementsCount).filterNot(d::isElementOptional).map(d::getElementName)
        }
}
