package homer.app

import homer.app.fixtures.repoRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/** A hand-written schema under `app/schemas/`, read as JSON for the contract-sync tests. */
internal class ContractSchema(
    val fileName: String,
) {
    val root: JsonObject = Json.parseToJsonElement(File(repoRoot, "app/schemas/$fileName").readText()).jsonObject

    /** The node a local `$ref` (`#`, `#/$defs/Name`, or any `#/...` pointer) points at. */
    fun resolve(ref: String): JsonObject {
        if (ref == "#") return root
        require(ref.startsWith("#/")) { "$fileName: only local refs are supported, got $ref" }
        val target =
            ref.removePrefix("#/").split('/').fold<String, JsonElement?>(root) { node, key ->
                if (node is JsonArray) node.getOrNull(key.toInt()) else (node as? JsonObject)?.get(key)
            }
        return target as? JsonObject ?: error("$fileName: $ref does not resolve")
    }

    /**
     * The object schema [node] stands for: follows `$ref`s and drops the `{"type": "null"}` branch of
     * a nullable `oneOf`/`anyOf`. Any other `oneOf` (a sealed type's variants, scalar-or-object
     * shorthands) is returned as is.
     */
    fun unwrap(node: JsonObject): JsonObject {
        var current = node
        while (true) {
            val ref = (current["\$ref"] as? JsonPrimitive)?.content
            val nonNull = (current["oneOf"] ?: current["anyOf"])?.let { it as JsonArray }?.filterNot(::isNullType)
            current =
                when {
                    ref != null -> resolve(ref)
                    nonNull != null && nonNull.size == 1 -> nonNull.single().jsonObject
                    else -> return current
                }
        }
    }

    private fun isNullType(branch: JsonElement): Boolean = (branch as? JsonObject)?.get("type") == JsonPrimitive("null")

    companion object {
        /** JSON form of a sealed type: kotlinx.serialization's default class discriminator, `type` in both schemas. */
        const val DISCRIMINATOR = "type"
    }
}

internal fun JsonObject.properties(): JsonObject? = this["properties"] as? JsonObject

internal fun JsonObject.requiredNames(): Set<String> =
    (this["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet().orEmpty()

/** The `const` of a sealed variant's `type` property, or null when [this] is no tagged variant. */
internal fun JsonObject.discriminatorValue(): String? =
    ((properties()?.get(ContractSchema.DISCRIMINATOR) as? JsonObject)?.get("const") as? JsonPrimitive)?.content
