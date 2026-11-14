package homer.app

import homer.app.fixtures.repoRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.fail

/**
 * Schema ⇔ doc-table backstop (root CLAUDE.md §10): every property of every schema object has a row
 * in the field table `CONFIG_TABLES` / `AGENT_TABLES` maps the object to, with the same required flag
 * and, where the schema states a `default`, the same default; every row names a schema property. A
 * sealed variant's `type` discriminator is left out on both sides. An object documented in prose
 * instead is listed in `CONFIG_UNTABLED` / `AGENT_UNTABLED` with the reason, and each of its properties
 * must at least appear in the doc's code. An object neither mapped nor listed fails, so a new schema
 * object needs a decision.
 *
 * Objects are the schema nodes with `properties` reachable through `properties`, `$defs`, `items`,
 * `additionalProperties`, `oneOf` and `anyOf`; `allOf`/`not`/`if`/`then` only constrain, never declare.
 */
class SchemaDocTableContractTest {
    @Test
    fun `every homer-config schema property has a matching row in SERVER md`() {
        assertNoDrift(ContractSchema("homer-config.schema.json"), "app/SERVER.md", CONFIG_TABLES, CONFIG_UNTABLED)
    }

    @Test
    fun `every homer-agent schema property has a matching row in AGENT md`() {
        assertNoDrift(ContractSchema("homer-agent.schema.json"), "app/AGENT.md", AGENT_TABLES, AGENT_UNTABLED)
    }

    private fun assertNoDrift(
        schema: ContractSchema,
        docPath: String,
        tables: Map<String, TableRef>,
        untabled: Map<String, String>,
    ) {
        val markdown = File(repoRoot, docPath).readText()
        val check = DocCheck(docPath, DocFieldTables.parse(markdown), markdown)
        val objects = schemaObjects(schema.root, "#")
        (tables.keys + untabled.keys - objects.keys).forEach { check.report("$it: listed here, but no such object in ${schema.fileName}") }
        objects.forEach { (pointer, node) ->
            val properties = node.properties().orEmpty().keys - discriminator(node)
            // A variant made of its discriminator alone has nothing to document.
            when {
                properties.isEmpty() -> Unit
                pointer in tables -> check.table(pointer, node, properties, tables.getValue(pointer))
                pointer in untabled -> check.mentioned(pointer, properties)
                else -> check.report("$pointer: map it to a $docPath field table or list it as untabled with a reason")
            }
        }
        if (check.problems.isNotEmpty()) {
            fail("${schema.fileName} and $docPath drifted apart:\n" + check.problems.joinToString("\n") { "  - $it" })
        }
    }

    private fun schemaObjects(
        node: JsonObject,
        pointer: String,
    ): Map<String, JsonObject> {
        val own = if (node.properties() != null) mapOf(pointer to node) else emptyMap()
        val children =
            listOf("properties", "\$defs").flatMap { key ->
                (node[key] as? JsonObject).orEmpty().map { (name, child) -> "$pointer/$key/$name" to child }
            } +
                listOf("items", "additionalProperties").mapNotNull { key -> node[key]?.let { "$pointer/$key" to it } } +
                listOf("oneOf", "anyOf").flatMap { key -> node.arrayAt(key).mapIndexed { i, child -> "$pointer/$key/$i" to child } }
        return own + children.filter { it.second is JsonObject }.flatMap { (p, child) -> schemaObjects(child as JsonObject, p).toList() }
    }

    private fun JsonObject.arrayAt(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    /** A field table: the [index]-th `Field` table under the heading [heading]; [prefix] selects its dotted rows. */
    private data class TableRef(
        val heading: String,
        val index: Int = 0,
        val prefix: String = "",
    )

    private class DocCheck(
        private val docPath: String,
        private val tables: List<FieldTable>,
        markdown: String,
    ) {
        val problems = mutableListOf<String>()
        private val codeText = codeText(markdown)

        fun report(problem: String) {
            problems += problem
        }

        fun table(
            pointer: String,
            node: JsonObject,
            properties: Set<String>,
            ref: TableRef,
        ) {
            val table =
                tables.filter { it.heading == ref.heading }.getOrNull(ref.index)
                    ?: return report("$pointer: no field table #${ref.index} under heading \"${ref.heading}\" in $docPath")
            val where = "$docPath:${table.line}"
            val rows = rowsFor(table, ref.prefix) - discriminator(node)
            (properties - rows.keys).sorted().forEach { report("$pointer: property `$it` has no row in the table at $where") }
            (rows.keys - properties).sorted().forEach {
                report(
                    "$pointer: the table at $where has a row `$it` the schema does not declare",
                )
            }
            val schemaProps = node.properties().orEmpty()
            (properties intersect rows.keys).forEach { name ->
                compareRow("$pointer.$name ($where)", rows.getValue(name), name in node.requiredNames(), schemaProps[name] as? JsonObject)
            }
        }

        /** Rows keyed by name: undotted rows for [prefix] "", else the rows under [prefix] with it stripped. */
        private fun rowsFor(
            table: FieldTable,
            prefix: String,
        ): Map<String, FieldRow> =
            table.rows
                .flatMap { row -> row.names.map { it to row } }
                .filter { (name, _) -> if (prefix.isEmpty()) '.' !in name else name.startsWith(prefix) }
                .associate { (name, row) -> name.removePrefix(prefix) to row }

        private fun compareRow(
            subject: String,
            row: FieldRow,
            required: Boolean,
            property: JsonObject?,
        ) {
            val docRequired = parseRequired(row.required)
            when {
                docRequired == null -> report("$subject: unreadable Required cell \"${row.required}\"")
                docRequired != required -> report("$subject: the schema says required=$required, the doc says \"${row.required}\"")
            }
            val default = property?.get("default") ?: return
            if (row.default != null && !defaultMatches(row.default, default)) {
                report("$subject: the schema default is $default, the doc says \"${row.default}\"")
            }
        }

        fun mentioned(
            pointer: String,
            properties: Set<String>,
        ) {
            properties.filterNot { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(codeText) }.sorted().forEach {
                report("$pointer: property `$it` is not mentioned in any code span or block of $docPath")
            }
        }
    }

    private companion object {
        private val NO_DEFAULT = setOf("", "-", "—", "–")

        /** "Yes", "**Yes**", "**Yes** (inside `retain`)", "no", … — null when the cell says neither; no column = not required. */
        fun parseRequired(cell: String?): Boolean? {
            val text = cell?.replace("*", "")?.trim()?.lowercase() ?: return false
            return when {
                text.startsWith("yes") -> true
                text.startsWith("no") -> false
                else -> null
            }
        }

        /** The doc's default is its leading code span (`` `5000` (5 s) `` → `5000`), compared as JSON, or as a bare string. */
        fun defaultMatches(
            cell: String,
            schemaDefault: JsonElement,
        ): Boolean {
            val text = if (cell.startsWith("`")) cell.substringAfter('`').substringBefore('`') else cell.trim()
            if (text in NO_DEFAULT) return false
            if (runCatching { Json.parseToJsonElement(text) }.getOrNull() == schemaDefault) return true
            return schemaDefault is JsonPrimitive && schemaDefault.isString && text.removeSurrounding("\"") == schemaDefault.content
        }

        /** Every inline code span and fenced block of [markdown], where a prose-documented property must appear. */
        fun codeText(markdown: String): String =
            Regex("```.*?```", RegexOption.DOT_MATCHES_ALL).findAll(markdown).joinToString("\n") { it.value } + "\n" +
                Regex("`([^`\n]+)`").findAll(markdown).joinToString("\n") { it.groupValues[1] }

        fun discriminator(node: JsonObject): Set<String> =
            setOfNotNull(
                ContractSchema.DISCRIMINATOR.takeIf {
                    node.discriminatorValue() !=
                        null
                },
            )

        val CONFIG_TABLES =
            mapOf(
                "#" to TableRef("Field Reference"),
                "#/\$defs/CostLimits" to TableRef("Cost Limits"),
                "#/\$defs/SessionSpendConfig" to TableRef("`sessionSpend` fields"),
                "#/\$defs/Server" to TableRef("Server Fields"),
                "#/\$defs/MaintenanceConfig" to TableRef("Server Fields", prefix = "maintenance."),
                "#/\$defs/McpServerConfig" to TableRef("MCP endpoint"),
                "#/\$defs/QuestionsConfig" to TableRef("Operator questions"),
                "#/\$defs/RunMessagesConfig" to TableRef("Run messages"),
                "#/\$defs/DispatchConfig" to TableRef("Ask-and-dispatch"),
                "#/\$defs/HookConfig" to TableRef("Status hooks"),
                "#/\$defs/LangGraphConfig" to TableRef("LangGraph"),
                "#/\$defs/HmacKeyConfig" to TableRef("HMAC Authentication"),
                "#/\$defs/AuthConfigPassword" to TableRef("Password Authentication"),
                "#/\$defs/PasswordUserConfig" to TableRef("Password Authentication", index = 1),
                "#/\$defs/RateLimitConfig" to TableRef("Rate Limit Fields"),
                "#/\$defs/LangfuseConfig" to TableRef("Langfuse Fields"),
                "#/\$defs/KubernetesRunner" to TableRef("KubernetesRunner fields"),
                "#/\$defs/VolumeSpec" to TableRef("KubernetesRunner fields", index = 1),
                "#/\$defs/ContainerSpec" to TableRef("KubernetesRunner fields", index = 2),
                "#/\$defs/SshRunner" to TableRef("SshRunner fields"),
                "#/\$defs/ExternalRunner" to TableRef("ExternalRunner fields"),
            )

        val CONFIG_UNTABLED =
            mapOf(
                "#/\$defs/AuthConfigBearer" to "one key, `tokens`, shown by example under Bearer authentication",
                "#/\$defs/TokenConfig/oneOf/1" to "the object form of a bearer token, shown by example",
                "#/\$defs/AuthConfigHmac" to "one key, `keys`; the HMAC table describes each entry",
                "#/\$defs/AuthConfigCombined" to "one key, `schemes`, shown by example under Combined authentication",
                "#/\$defs/Grant" to "documented in prose under Grants and roles",
                "#/\$defs/LlmPriceConfig" to "one key, `sessionSpend`, which has its own table",
                "#/\$defs/LocalRunnerConfig" to "its keys are listed in the Server table's `localRunner` row",
                "#/\$defs/ExtraUploadSpec" to "its keys are listed in each runner table's `extraUploads` row",
                "#/\$defs/SshAuth/oneOf/0" to "key auth, documented by example with the SshRunner `auth` row",
                "#/\$defs/SecurityContextSpec" to "documented in prose and by example under KubernetesRunner fields",
                "#/\$defs/CapabilitiesSpec" to "documented in a prose paragraph under KubernetesRunner fields",
                "#/\$defs/SeccompProfileSpec" to "documented in a prose paragraph under KubernetesRunner fields",
                "#/\$defs/ResourceSpec" to "requests/limits maps, shown by example",
                "#/\$defs/VolumeSource/oneOf/0" to "the `source` table lists each variant's fields in one cell",
                "#/\$defs/VolumeSource/oneOf/1" to "the `source` table lists each variant's fields in one cell",
                "#/\$defs/VolumeSource/oneOf/2" to "the `source` table lists each variant's fields in one cell",
                "#/\$defs/VolumeSource/oneOf/3" to "the `source` table lists each variant's fields in one cell",
                "#/\$defs/VolumeMountSpec" to "its keys are listed in the ContainerSpec table's `volumeMounts` row",
            )

        val AGENT_TABLES =
            mapOf(
                "#" to TableRef("Agent Fields"),
                "#/\$defs/AgentTelemetry" to TableRef("Telemetry Fields"),
                "#/\$defs/Tags" to TableRef("Tags Fields"),
                "#/\$defs/Cooldown" to TableRef("Cooldown Fields"),
                "#/\$defs/Workspace" to TableRef("Workspace Fields"),
                "#/\$defs/Workspace/properties/retain" to TableRef("Workspace Fields", prefix = "retain."),
                "#/\$defs/LockStrategy" to TableRef("Lock Fields"),
                "#/\$defs/Cmd" to TableRef("Cmd Fields"),
                "#/\$defs/Script" to TableRef("Script Fields"),
                "#/\$defs/Claude" to TableRef("Claude Fields"),
                "#/\$defs/LlmRetry" to TableRef("Claude Retry"),
                "#/\$defs/LlmEscalation" to TableRef("Claude Escalation"),
                "#/\$defs/ClaudeCmdOptions" to TableRef("ClaudeCmdOptions Fields"),
                "#/\$defs/Codex" to TableRef("Codex Fields"),
                "#/\$defs/CodexCmdOptions" to TableRef("Codex Options (CodexCmdOptions)"),
                "#/\$defs/Opencode" to TableRef("Opencode Fields"),
                "#/\$defs/OpencodeCmdOptions" to TableRef("Opencode Options (OpencodeCmdOptions)"),
                "#/\$defs/RunAgent" to TableRef("RunAgent Fields"),
                "#/\$defs/AgentCommandParam" to TableRef("AgentCommandParam Fields"),
                "#/\$defs/LangGraph" to TableRef("LangGraph Fields"),
                "#/\$defs/RunnerCmd" to TableRef("RunnerCmd Fields"),
                "#/\$defs/ResultEnvParam" to TableRef("Fields"),
                "#/\$defs/Param" to TableRef("Param Fields"),
            )

        val AGENT_UNTABLED =
            mapOf(
                "#/properties/runnerAffinity" to "its keys are described in the Agent table's `runnerAffinity` row",
                "#/\$defs/AgentMcpConfig" to "one key, `enabled`, documented under MCP Exposure",
                "#/\$defs/AgentDispatchConfig" to "one key, `enabled`, documented under \"`dispatch.enabled` is required\"",
                "#/\$defs/AgentContinuationConfig" to "one key, `inheritInputs`, documented with continuations",
                "#/\$defs/CronConfig" to "documented under Cron Configuration; the Cron Fields table describes the expression",
                "#/\$defs/CommandCommonProps" to "a shared fragment: every command type's table lists these rows",
                "#/\$defs/When/oneOf/1" to "the `all` form, documented under Conditional execution",
                "#/\$defs/When/oneOf/2" to "the `any` form, documented under Conditional execution",
                "#/\$defs/LlmPrompt/oneOf/0" to "documented under Prompt - Inline Prompt",
                "#/\$defs/LlmPrompt/oneOf/1" to "documented under Langfuse - Langfuse-Managed Prompt",
                "#/\$defs/ClaudeSubagent" to "documented with the ClaudeCmdOptions `agents` row",
                "#/\$defs/ResultTransformer/oneOf/1" to "documented under Transformer.JsonPath",
                "#/\$defs/ResultTransformer/oneOf/2" to "documented under Transformer.Regex",
                "#/\$defs/ParamTransformer/oneOf/1" to "documented under Parameter Transformers",
            )
    }
}
