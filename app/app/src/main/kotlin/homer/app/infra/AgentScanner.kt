package homer.app.infra

import com.charleskorn.kaml.Yaml
import homer.app.EnvConstants
import homer.app.models.Agent
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

/**
 * One pass over `agentsSearchPath`: walks it with [AgentDirs] (ADR-0068), reads each definition under
 * the size cap, validates it and claims its name. Each directory isolates its own failure.
 */
internal class AgentScanner(
    private val agentsSearchPath: String,
    private val logger: Logger,
    private val sysEnvResolver: SysEnvResolver,
    private val sysEnvAllowlist: AgentSysEnvAllowlist,
    private val maxConfigFileBytes: Long,
    private val validator: AgentDefinitionValidator,
    private val inputChecks: AgentInputChecks,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun scan(): AgentScan {
        val basePath = Path.of(agentsSearchPath)
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            logger.warn("Agents search path does not exist or is not a directory: {}", agentsSearchPath)
            return AgentScan(emptyMap(), emptyMap(), emptySet(), emptyList())
        }

        val parsed = mutableMapOf<String, Agent>()
        val sourceDirs = mutableMapOf<String, String>()
        val presentDirNames = mutableSetOf<String>()
        val errors = mutableListOf<String>()
        val collidedNames = mutableSetOf<String>()

        try {
            AgentDirs.walkAgentDirs(basePath).forEach { rel ->
                val relName = rel.invariantSeparatorsPathString
                parseAgentDir(basePath.resolve(rel), relName, parsed, sourceDirs, presentDirNames, errors, collidedNames)
            }
        } catch (e: Exception) {
            logger.error("Error scanning agents directory: {}", e.message, e)
            errors.add("Directory scan failed: ${e.message}")
        }

        // Hook checks need the whole catalog; a rejected agent stays in presentDirNames (ADR-0107).
        for ((name, hookErrors) in AgentHookValidation.apply(parsed)) {
            sourceDirs.remove(name)
            for (error in hookErrors) {
                val msg = "Agent '$name': $error; the agent is skipped."
                logger.error(msg)
                errors.add(msg)
            }
        }

        return AgentScan(parsed, sourceDirs, presentDirNames, errors)
    }

    /** Parses one agent directory. [rel], its path below `agentsSearchPath`, is its identity (ADR-0068). */
    private fun parseAgentDir(
        dir: Path,
        rel: String,
        parsed: MutableMap<String, Agent>,
        sourceDirs: MutableMap<String, String>,
        presentDirNames: MutableSet<String>,
        errors: MutableList<String>,
        collidedNames: MutableSet<String>,
    ) {
        val agentPath = AgentDirs.definitionFile(dir) ?: return

        presentDirNames.add(rel)

        try {
            val agent = readAgent(agentPath, rel, errors) ?: return
            // Before any `@SYSENV:` resolution: a refused name is never read, and the refusal
            // does not reveal whether it is set (ADR-0037).
            val (validatedName, cronDisabled) = checkAgent(agent, rel, errors) ?: return

            // HMR_AGENT_DIR is injected last so no author-set value can override it.
            val sysEnvBackedEnvNames = sysEnvResolver.secretNamesFor(agent.env)
            // Only the keys half feeds the cooldown.key load check; see `SysEnvResolver.secretEnvKeysFor`.
            val sysEnvBackedEnvKeys = sysEnvResolver.secretEnvKeysFor(agent.env)
            val resolvedEnv =
                sysEnvResolver.resolveMapValues(agent.env) +
                    (EnvConstants.AGENT_DIR to dir.toAbsolutePath().normalize().toString())
            if (!claimName(validatedName, rel, parsed, sourceDirs, collidedNames, errors)) return

            inputChecks.warnOnInputSteering(agent)
            parsed[validatedName] =
                agent.copy(
                    name = validatedName,
                    env = resolvedEnv,
                    cron = agent.cron.takeUnless { cronDisabled },
                    writable = Files.isWritable(dir),
                    sysEnvBackedEnvNames = sysEnvBackedEnvNames,
                    sysEnvBackedEnvKeys = sysEnvBackedEnvKeys,
                )
            sourceDirs[validatedName] = rel
            logger.info("Loaded agent: {} from {}", validatedName, rel)
        } catch (e: Exception) {
            logger.error("Error loading agent from {}: {}", rel, e.message, e)
            errors.add("Error loading agent from $rel: ${e.message}")
        }
    }

    /**
     * The name, definition and `@SYSENV:` checks, in order: [agent]'s validated name and whether its cron
     * schedule is disabled, or `null` at the first failure, which reported itself into [errors].
     */
    private fun checkAgent(
        agent: Agent,
        rel: String,
        errors: MutableList<String>,
    ): Pair<String, Boolean>? {
        val validatedName = validator.checkAgentName(agent, rel, errors) ?: return null
        val cronDisabled = validator.checkDefinition(agent, rel, errors) ?: return null
        val refusal = sysEnvAllowlist.refusal(agent.env) ?: return validatedName to cronDisabled
        val msg = "Agent '${agent.name}' in $rel $refusal; the agent is skipped."
        logger.error(msg)
        errors.add(msg)
        return null
    }

    /** Reads and decodes [agentPath], refusing a file over the size cap; `null` = skip it. */
    private fun readAgent(
        agentPath: Path,
        rel: String,
        errors: MutableList<String>,
    ): Agent? {
        // kaml bounds depth and alias expansion but not input size, so cap it before reading (CWE-400).
        val size = Files.size(agentPath)
        if (size > maxConfigFileBytes) {
            logger.error(
                "Agent definition '{}' in {} is {} bytes (cap {} bytes); skipping.",
                agentPath.fileName,
                rel,
                size,
                maxConfigFileBytes,
            )
            errors.add(
                "Agent definition '${agentPath.fileName}' in $rel is $size bytes; cap is $maxConfigFileBytes",
            )
            return null
        }
        val raw = agentPath.readText()
        val agent =
            if (agentPath.extension == "json") {
                json.decodeFromString<Agent>(raw)
            } else {
                Yaml.default.decodeFromString(Agent.serializer(), raw)
            }

        return agent
    }

    /** Claims [validatedName] for [rel], failing every directory that declares the same name; `false` = skip. */
    private fun claimName(
        validatedName: String,
        rel: String,
        parsed: MutableMap<String, Agent>,
        sourceDirs: MutableMap<String, String>,
        collidedNames: MutableSet<String>,
        errors: MutableList<String>,
    ): Boolean {
        // A duplicate name fails all claimants so a writable dir cannot shadow a trusted agent (ADR-0068).
        if (validatedName in collidedNames) {
            val msg =
                "Agent name '$validatedName' is declared by multiple directories (including $rel); " +
                    "all are skipped. Make each agent.name unique."
            logger.error(msg)
            errors.add(msg)
            return false
        }
        val priorDir = sourceDirs[validatedName]
        if (priorDir != null) {
            collidedNames.add(validatedName)
            parsed.remove(validatedName)
            sourceDirs.remove(validatedName)
            val msg =
                "Agent name '$validatedName' is declared by multiple directories ('$priorDir' and '$rel'); " +
                    "all are skipped. Make each agent.name unique."
            logger.error(msg)
            errors.add(msg)
            return false
        }

        return true
    }
}

/** What one [AgentScanner.scan] found: loaded agents, their directories, every directory present, and errors. */
internal data class AgentScan(
    val parsed: Map<String, Agent>,
    val sourceDirs: Map<String, String>,
    val presentDirNames: Set<String>,
    val errors: List<String>,
) {
    fun toState() = AgentCatalogState(parsed, sourceDirs, AgentLoadErrors.of(errors))
}
