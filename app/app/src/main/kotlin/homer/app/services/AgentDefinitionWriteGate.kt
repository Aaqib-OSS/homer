package homer.app.services

import com.charleskorn.kaml.Yaml
import homer.app.infra.AgentDirs
import homer.app.infra.AgentSysEnvAllowlist
import homer.app.models.Agent
import kotlinx.serialization.json.Json
import org.slf4j.Logger

/**
 * The grant and schema gate of an [AgentFilesService.write] landing on a definition
 * file: a write that would create an agent needs the create-agent grant on the name
 * it declares, and every definition written must pass the load-time schema and `@SYSENV:` checks.
 */
internal class AgentDefinitionWriteGate(
    private val logger: Logger,
    private val sysEnvAllowlist: AgentSysEnvAllowlist,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The denial of a write landing at [landingRel] (relative to the agent's real
     * directory), or null when the write may proceed. [relPath] is the name the
     * request used, the only one a denial may echo.
     */
    fun denial(
        landingRel: String,
        relPath: String,
        content: String,
        authorizeCreateAgent: (String) -> Boolean,
    ): AgentFilesService.FileOpResult<Unit>? {
        val isYaml = landingRel.endsWith(".yaml")
        // Any definition write needs the create-agent grant on its declared name; one that does not
        // parse is denied alike, the root definition excepted (ADR-0068, ADR-0047 rule 3, CWE-285).
        if (isDefFileName(landingRel)) {
            val declared = declaredAgentName(content, isYaml)
            if (declared == null) {
                if (!isAgentDef(landingRel)) return AgentFilesService.FileOpResult.NotFound("File not found: $relPath")
            } else if (!authorizeCreateAgent(declared)) {
                return AgentFilesService.FileOpResult.NotFound("File not found: $relPath")
            }
        }

        // At any depth: a written definition that fails validation would still mark
        // its directory as a nested-agent boundary no parent request can then reach.
        if (isDefFileName(landingRel)) {
            val errors = validateAgentDefinition(content, isYaml)
            if (errors.isNotEmpty()) return AgentFilesService.FileOpResult.SchemaInvalid(errors)
        }
        return null
    }

    private fun validateAgentDefinition(
        content: String,
        isYaml: Boolean,
    ): List<String> =
        try {
            val agent = decodeAgent(content, isYaml)
            buildList {
                if (!AgentDirs.AGENT_NAME_PATTERN.matches(agent.name)) {
                    add("Invalid agent name '${agent.name}': only letters, digits, '-', '_' allowed")
                }
                if (agent.commands.isEmpty()) add("Agent must have at least one command")
                sysEnvAllowlist.refusal(agent.env)?.let { add("Agent '${agent.name}' $it") }
            }
        } catch (e: Exception) {
            logger.debug("Agent definition rejected: {}", e.message)
            listOf(e.message ?: "Failed to parse agent definition")
        }

    /** The `name:` a definition declares, when it parses; null when it does not (the schema validation reports that). */
    private fun declaredAgentName(
        content: String,
        isYaml: Boolean,
    ): String? =
        try {
            decodeAgent(content, isYaml).name
        } catch (_: Exception) {
            null
        }

    private fun decodeAgent(
        content: String,
        isYaml: Boolean,
    ): Agent =
        if (isYaml) {
            Yaml.default.decodeFromString(Agent.serializer(), content)
        } else {
            json.decodeFromString<Agent>(content)
        }
}
