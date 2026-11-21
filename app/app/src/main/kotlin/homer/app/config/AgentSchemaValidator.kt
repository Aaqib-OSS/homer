package homer.app.config

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import homer.app.models.Agent
import kotlinx.serialization.json.Json

/**
 * Validates [Agent] definitions against the bundled `homer-agent.schema.json`, the schema the console editor and
 * `SchemaValidationTest` use. Read from the classpath (`processResources` copies `app/schemas/`), so it works
 * from any jar; `agentdebug validate` shares it.
 */
class AgentSchemaValidator(
    schemaResourcePath: String = "schemas/homer-agent.schema.json",
) {
    private val json =
        Json {
            prettyPrint = false
            encodeDefaults = true
            explicitNulls = false
        }

    private val schema: Schema =
        run {
            val schemaText =
                this::class.java.classLoader
                    .getResource(schemaResourcePath)
                    ?.readText()
                    ?: error("Agent schema resource '$schemaResourcePath' not bundled with this build")
            SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(schemaText)
        }

    /** Returns human-readable validation error messages; an empty list means valid. */
    fun validate(agentJson: String): List<String> = schema.validate(agentJson, InputFormat.JSON).map { it.message }

    /** Serializes [agent] to JSON and validates it against the schema. */
    fun validate(agent: Agent): List<String> = validate(json.encodeToString(Agent.serializer(), agent))
}
