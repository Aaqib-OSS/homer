package homer.app

import homer.app.models.Agent
import homer.app.models.AppConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Model ⇔ schema backstop (root CLAUDE.md §10): walks the serial descriptor of [AppConfig] and [Agent]
 * and checks every object it reaches against the schema node that describes it — the same property
 * names, and the same required set (an element with no default is required). A sealed type is
 * matched variant by variant through the schema's `oneOf`, keyed by each variant's `type` const.
 * `@Transient` fields have no descriptor element, so they are never compared.
 */
class ModelSchemaContractTest {
    @Test
    fun `AppConfig and every type it reaches match homer-config schema`() {
        assertNoDrift(ContractSchema("homer-config.schema.json"), AppConfig.serializer().descriptor)
    }

    @Test
    fun `Agent and every type it reaches match homer-agent schema`() {
        assertNoDrift(ContractSchema("homer-agent.schema.json"), Agent.serializer().descriptor)
    }

    @Test
    fun `every exception still matches a difference`() {
        val keys =
            ModelSchemaWalker(ContractSchema("homer-config.schema.json")).walk(AppConfig.serializer().descriptor).keys +
                ModelSchemaWalker(ContractSchema("homer-agent.schema.json")).walk(Agent.serializer().descriptor).keys
        val stale = EXCEPTIONS.keys - keys
        assertTrue(stale.isEmpty(), "Exceptions that no longer match a difference, remove them: $stale")
    }

    private fun assertNoDrift(
        schema: ContractSchema,
        descriptor: SerialDescriptor,
    ) {
        val problems = ModelSchemaWalker(schema).walk(descriptor).filterKeys { it !in EXCEPTIONS }
        if (problems.isNotEmpty()) {
            fail(
                "${schema.fileName} drifted from the ${descriptor.simpleName()} model (the model is the truth):\n" +
                    problems.values.joinToString("\n") { "  - $it" },
            )
        }
    }

    private companion object {
        /** Differences the schema keeps on purpose: it is stricter than the descriptor. Key → reason. */
        val EXCEPTIONS =
            mapOf(
                "AppConfig.server required" to "defaulted, but the default has no apiAuth, which startup rejects (ADR-0036)",
                "Server.apiAuth required" to "nullable so a missing key reaches the explicit startup error (ADR-0036)",
                "Workspace.purgeRecordAfter property" to "removed; kept only to fail with a migration hint (ADR-0053)",
                "HmacKeyConfig.endpoints property" to "removed; kept only to fail startup with a migration hint (ADR-0037)",
                "PasswordUserConfig.endpoints property" to "removed; kept only to fail startup with a migration hint (ADR-0037)",
                "AuthConfig<combined>.schemes<combined> variant" to "AuthConfig.Combined rejects a nested Combined at load",
                "AuthConfig<combined>.schemes<unsecured> variant" to "AuthConfig.Combined rejects Unsecured at load",
            )
    }
}

private fun SerialDescriptor.simpleName(): String = serialName.removeSuffix("?").substringAfterLast('.')

/**
 * Collects each difference under a key independent of the path it was found on — `Owner.name kind`
 * for a property, `Owner.property<tag> variant` for a sealed variant — so an exception names it
 * stably. The message carries the first path the difference was seen on.
 */
@OptIn(ExperimentalSerializationApi::class)
private class ModelSchemaWalker(
    private val schema: ContractSchema,
) {
    private val problems = linkedMapOf<String, String>()
    private val visited = mutableSetOf<Pair<String, JsonObject>>()

    fun walk(descriptor: SerialDescriptor): Map<String, String> {
        compare(descriptor, schema.root, descriptor.simpleName(), descriptor.simpleName())
        return problems
    }

    /** [site] is `Owner.property` of the property that holds [descriptor], kept through lists and maps. */
    private fun compare(
        descriptor: SerialDescriptor,
        node: JsonObject,
        path: String,
        site: String,
    ) {
        val target = schema.unwrap(node)
        if (descriptor.isInline) return compare(descriptor.getElementDescriptor(0), target, path, site)
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> compareClass(descriptor, target, path, descriptor.simpleName())

            PolymorphicKind.SEALED -> compareSealed(descriptor, target, path, site)

            StructureKind.LIST -> compareChild(descriptor.getElementDescriptor(0), target["items"], "$path[]", site)

            StructureKind.MAP -> compareChild(descriptor.getElementDescriptor(1), target["additionalProperties"], "$path{}", site)

            // Primitives and enums have no properties. A CONTEXTUAL descriptor (the scalar-or-object
            // serializers: When, CmdArgs, JsonObject, ...) has no static shape: recursion stops there.
            else -> Unit
        }
    }

    /** A list element or map value: compared only when it is itself an object, list or map. */
    private fun compareChild(
        descriptor: SerialDescriptor,
        node: JsonElement?,
        path: String,
        site: String,
    ) {
        if (!descriptor.isStructured()) return
        if (node !is JsonObject) return report("$site sub-schema", "$path: the schema has no sub-schema for ${descriptor.serialName}")
        compare(descriptor, node, path, site)
    }

    private fun SerialDescriptor.isStructured(): Boolean = if (isInline) getElementDescriptor(0).isStructured() else kind in STRUCTURED

    /** [owner] names the class in keys: its simple name, or `Sealed<tag>` for a sealed variant. */
    private fun compareClass(
        descriptor: SerialDescriptor,
        node: JsonObject,
        path: String,
        owner: String,
        discriminated: Boolean = false,
    ) {
        if (!visited.add(descriptor.serialName to node)) return
        val properties =
            node.properties() ?: return report("$owner properties", "$path: the schema describes $owner with no `properties`")
        val ignored = if (discriminated) setOf(ContractSchema.DISCRIMINATOR) else emptySet()
        val names = descriptor.elementNames.toSet()
        diff(path, owner, "property", names, properties.keys - ignored)
        val required = names.filterIndexed { i, _ -> !descriptor.isElementOptional(i) }.toSet()
        diff(path, owner, "required", required, node.requiredNames() - ignored)
        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val child = properties[name] as? JsonObject ?: continue
            compare(descriptor.getElementDescriptor(i), child, "$path.$name", "$owner.$name")
        }
    }

    // A sealed descriptor has two elements: the discriminator and a "value" whose elements are the subclasses.
    // A content-based polymorphic serializer (TokenConfig: scalar or object) has none: recursion stops there.
    private fun compareSealed(
        descriptor: SerialDescriptor,
        node: JsonObject,
        path: String,
        site: String,
    ) {
        if (descriptor.elementsCount < 2) return
        val variants =
            (node["oneOf"] as? JsonArray)
                ?.map { schema.unwrap(it.jsonObject) }
                ?.associateBy { it.discriminatorValue() }
                ?: return report("$site oneOf", "$path: the schema has no `oneOf` for sealed ${descriptor.serialName}")
        val subclasses = descriptor.getElementDescriptor(1).elementDescriptors.associateBy { it.serialName }
        for (tag in subclasses.keys - variants.keys) {
            report(
                "$site<$tag> variant",
                "$path: variant `$tag` is in the model but not in the schema",
            )
        }
        for (tag in variants.keys - subclasses.keys) {
            report(
                "$site<$tag> variant",
                "$path: variant `$tag` is in the schema but not in the model",
            )
        }
        subclasses.forEach { (tag, sub) ->
            variants[tag]?.let { compareClass(sub, it, "$path<$tag>", "${descriptor.simpleName()}<$tag>", discriminated = true) }
        }
    }

    private fun diff(
        path: String,
        owner: String,
        kind: String,
        model: Set<String>,
        schemaSide: Set<String>,
    ) {
        (model - schemaSide).forEach { report("$owner.$it $kind", "$path: $kind `$it` is in the model but not in the schema") }
        (schemaSide - model).forEach { report("$owner.$it $kind", "$path: $kind `$it` is in the schema but not in the model") }
    }

    private fun report(
        key: String,
        message: String,
    ) {
        problems.putIfAbsent(key, "$message [$key]")
    }

    private companion object {
        val STRUCTURED = setOf(StructureKind.CLASS, StructureKind.OBJECT, StructureKind.LIST, StructureKind.MAP, PolymorphicKind.SEALED)
    }
}
