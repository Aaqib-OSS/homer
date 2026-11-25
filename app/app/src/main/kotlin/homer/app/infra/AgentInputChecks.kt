package homer.app.infra

import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ParamSource
import homer.app.models.ShellEnvNames
import homer.app.models.TagValidation
import homer.app.models.bypassesPermissionsWithoutToolLimit
import homer.app.models.templatePlaceholderPattern
import org.slf4j.Logger

/** The agent's commands and onStop steps, each tagged with the phase name the messages use (ADR-0108). */
internal val Agent.phaseTaggedSteps: List<Pair<String, Command>>
    get() = commands.map { "command" to it } + onStop.map { "onStop step" to it }

/**
 * Load-time checks on an agent's inputs and their command bindings (envNames, tags, duplicates, result
 * captures, input-chosen executables), plus warn-only notes. A failing `check*` reports into `errors`
 * and returns `false` to skip the agent.
 */
internal class AgentInputChecks(
    private val logger: Logger,
    private val paramValidator: ParamValidator,
) {
    private val envNamePattern = Regex(ShellEnvNames.PATTERN)

    /** Rejects reserved header params and reserved or malformed input envNames; `false` = skip the agent. */
    fun checkInputEnvNames(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        val invalidHeader =
            agent.effectiveInputs
                ?.firstOrNull { it.src == ParamSource.HEADER && ReservedHeaders.isReserved(it.paramName) }
        if (invalidHeader != null) {
            logger.error(
                "Agent '{}': header param '{}' is reserved and cannot be bound. Skipping.",
                agent.name,
                invalidHeader.paramName,
            )
            errors.add(
                "Agent '${agent.name}': header param '${invalidHeader.paramName}' is reserved and cannot be bound",
            )
            return false
        }

        val invalidEnvName =
            agent.effectiveInputs
                ?.firstOrNull { ReservedEnvNames.isReserved(it.envName) }
        if (invalidEnvName != null) {
            logger.error(
                "Agent '{}': input '{}' targets reserved envName '{}' (Homer / host-controlled). Skipping.",
                agent.name,
                invalidEnvName.paramName,
                invalidEnvName.envName,
            )
            errors.add(
                "Agent '${agent.name}': input '${invalidEnvName.paramName}' targets reserved envName '${invalidEnvName.envName}'",
            )
            return false
        }

        val malformedEnvName =
            agent.effectiveInputs?.firstOrNull { !envNamePattern.matches(it.envName) }
        if (malformedEnvName != null) {
            logger.error(
                "Agent '{}': input '{}' has envName '{}' that is not a valid environment variable name " +
                    "(^[A-Za-z_][A-Za-z0-9_]*$). Declare an explicit envName. Skipping.",
                agent.name,
                malformedEnvName.paramName,
                malformedEnvName.envName,
            )
            errors.add(
                "Agent '${agent.name}': input '${malformedEnvName.paramName}' has invalid envName '${malformedEnvName.envName}'",
            )
            return false
        }

        return true
    }

    /** Validates tags.fromInputs and tags.runtime (ADR-0060); `false` = skip the agent. */
    fun checkTags(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean = checkFromInputTags(agent, errors) && checkRuntimeTags(agent, errors)

    /** Validates tags.fromInputs and its telemetry.traceTags alias; `false` = skip the agent. */
    private fun checkFromInputTags(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        @Suppress("DEPRECATION") // reads the traceTags alias on purpose, to warn about it
        if (agent.telemetry.traceTags.isNotEmpty()) {
            logger.warn(
                "Agent '{}': telemetry.traceTags is deprecated; use the top-level tags.fromInputs block instead.",
                agent.name,
            )
        }
        val badFromInputTag = fromInputTagProblem(agent)
        if (badFromInputTag != null) {
            logger.error(
                "Agent '{}': tags.fromInputs entry '{}' {}. Skipping.",
                agent.name,
                badFromInputTag.first,
                badFromInputTag.second,
            )
            errors.add("Agent '${agent.name}': tags.fromInputs entry '${badFromInputTag.first}' ${badFromInputTag.second}")
            return false
        }

        return true
    }

    /** The first tags.fromInputs entry that cannot become a tag, paired with why; null when every entry can. */
    private fun fromInputTagProblem(agent: Agent): Pair<String, String>? {
        val declaredParamNames = agent.effectiveInputs.orEmpty().associateBy { it.paramName }
        return agent.effectiveFromInputTags.firstNotNullOfOrNull { name ->
            val param = declaredParamNames[name]
            when {
                param == null -> {
                    "names no declared input"
                }

                param.secret -> {
                    "names the secret input '$name'; a run tag is exported in the clear"
                }

                // The paramName becomes the stored tag key, so it must fit the key charset (CWE-400).
                !TagValidation.KEY_PATTERN.matches(name) -> {
                    "names input '$name', whose paramName is not ^[A-Za-z0-9._-]{1,64}\$ and cannot be a tag key"
                }

                else -> {
                    null
                }
            }?.let { name to it }
        }
    }

    /** Validates tags.runtime: each key's charset, and disjointness from tags.fromInputs (ADR-0060); `false` = skip. */
    private fun checkRuntimeTags(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        val badRuntimeKey = agent.tags.runtime.firstOrNull { !TagValidation.KEY_PATTERN.matches(it) }
        if (badRuntimeKey != null) {
            logger.error(
                "Agent '{}': tags.runtime key '{}' is not ^[A-Za-z0-9._-]{{1,64}}$. Skipping.",
                agent.name,
                badRuntimeKey,
            )
            errors.add("Agent '${agent.name}': tags.runtime key '$badRuntimeKey' is not ^[A-Za-z0-9._-]{1,64}\$")
            return false
        }
        // A runtime key may not also be a fromInputs key: it could shadow an input-derived tag (ADR-0060).
        val overlappingKey = agent.tags.runtime.firstOrNull { it in agent.effectiveFromInputTags }
        if (overlappingKey != null) {
            logger.error(
                "Agent '{}': tags.runtime key '{}' also appears in tags.fromInputs; keys must be disjoint. Skipping.",
                agent.name,
                overlappingKey,
            )
            errors.add(
                "Agent '${agent.name}': tags.runtime key '$overlappingKey' also appears in tags.fromInputs; keys must be disjoint",
            )
            return false
        }

        return true
    }

    /** Rejects duplicate input envNames / paramNames and invalid input declarations; `false` = skip the agent. */
    fun checkInputUniqueness(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        val duplicateEnvName =
            agent.effectiveInputs
                ?.groupBy { it.envName }
                ?.entries
                ?.firstOrNull { it.value.size > 1 }
        if (duplicateEnvName != null) {
            logger.error(
                "Agent '{}': multiple inputs target envName '{}'. Skipping.",
                agent.name,
                duplicateEnvName.key,
            )
            errors.add("Agent '${agent.name}': multiple inputs target envName '${duplicateEnvName.key}'")
            return false
        }

        val duplicateParam =
            agent.effectiveInputs
                ?.groupBy { it.src to it.paramName }
                ?.entries
                ?.firstOrNull { it.value.size > 1 }
        if (duplicateParam != null) {
            logger.error(
                "Agent '{}': duplicate input paramName '{}' for src '{}'. Skipping.",
                agent.name,
                duplicateParam.key.second,
                duplicateParam.key.first,
            )
            errors.add(
                "Agent '${agent.name}': duplicate input paramName '${duplicateParam.key.second}' for src '${duplicateParam.key.first}'",
            )
            return false
        }

        val declarationErrors =
            agent.effectiveInputs.orEmpty().flatMap { paramValidator.validateDeclaration(it) }
        if (declarationErrors.isNotEmpty()) {
            logger.error(
                "Agent '{}': invalid input declaration: {}. Skipping.",
                agent.name,
                declarationErrors.joinToString("; "),
            )
            errors.add("Agent '${agent.name}': " + declarationErrors.joinToString("; "))
            return false
        }

        return true
    }

    /** Rejects result captures into invalid envNames and input- or capture-chosen LLM executables; `false` = skip the agent. */
    fun checkCommandBindings(
        agent: Agent,
        errors: MutableList<String>,
    ): Boolean {
        // Both binding guards sweep the stop phase too — a stop step cannot opt out of a
        // check the phase it runs in does not weaken (ADR-0047, ADR-0108).
        val steps = agent.phaseTaggedSteps

        // Captured output is attacker-influenceable and merged into later commands' and onStop steps'
        // env, so it gets the same reserved-name guard as inputs (ADR-0047 rule 2).
        val invalidResultEnvName =
            steps
                .flatMap { (kind, cmd) -> cmd.resultEnvParam.map { Triple(kind, cmd, it) } }
                .firstOrNull { (_, _, param) ->
                    ReservedEnvNames.isReserved(param.envName) || !envNamePattern.matches(param.envName)
                }
        if (invalidResultEnvName != null) {
            val (kind, cmd, param) = invalidResultEnvName
            logger.error(
                "Agent '{}': {} '{}' captures a result into invalid envName '{}' " +
                    "(reserved Homer/host name, or not matching ^[A-Za-z_][A-Za-z0-9_]*$). Skipping.",
                agent.name,
                kind,
                cmd.label,
                param.envName,
            )
            errors.add(
                "Agent '${agent.name}': $kind '${cmd.label}' captures a result into invalid envName '${param.envName}'",
            )
            return false
        }

        // An input or a captured result in an LLM command's `cmd` (argv[0]) would let a caller choose the
        // host executable (CWE-78, ADR-0047 rule 2); a capture on any step reaches every later step's env.
        val runTimeNames =
            steps.flatMap { (_, cmd) -> cmd.resultEnvParam }.associate { it.envName to "captured result" } +
                agent.effectiveInputs.orEmpty().associate { it.envName to "input" }
        val runTimeBoundExecutable =
            steps
                .mapNotNull { (kind, cmd) -> (cmd as? Command.LlmCommand)?.let { kind to cmd } }
                .firstNotNullOfOrNull { (kind, cmd) ->
                    templatePlaceholderPattern
                        .findAll(cmd.cmd)
                        .map { it.groupValues[1] }
                        .firstOrNull { it in runTimeNames }
                        ?.let { Triple(kind, cmd, it) }
                }
        if (runTimeBoundExecutable != null) {
            val (kind, cmd, name) = runTimeBoundExecutable
            val source = runTimeNames.getValue(name)
            val msg =
                "Agent '${agent.name}': $kind '${cmd.label}' cmd references $source '{{$name}}'; " +
                    "${if (source == "input") "an input" else "a captured result"} must not choose the executable"
            logger.error("{}. Skipping.", msg)
            errors.add(msg)
            return false
        }

        return true
    }

    /** WARNs once per agent on the STRING/NUMBER/ANY param types and the `queryParams` alias. */
    fun warnOnDeprecatedInputs(agent: Agent) {
        if (agent.effectiveInputs.orEmpty().any { it.type.isLegacy }) {
            logger.warn(
                "Agent '{}' uses deprecated param types (STRING/NUMBER/ANY); " +
                    "migrate to typed params (string, integer, number, boolean).",
                agent.name,
            )
        }
        @Suppress("DEPRECATION") // reads the queryParams alias on purpose, to warn about it
        if (agent.queryParams != null) {
            logger.warn(
                "Agent '{}' uses the deprecated 'queryParams' alias; rename it to 'inputs'.",
                agent.name,
            )
        }
    }

    /**
     * WARNs when an input-bound envName feeds the workspace path, lock key, cooldown key or a RunAgent
     * target or Claude option, letting a caller pick another run's directory, lock, pacing window or
     * target agent. Legitimate, so only a warning; `onStop:` steps are swept as `commands:` steps are.
     */
    fun warnOnInputSteering(agent: Agent) {
        val inputEnvNames =
            agent.effectiveInputs
                .orEmpty()
                .map { it.envName }
                .toSet()
        if (inputEnvNames.isEmpty()) return

        fun refs(template: String) = templatePlaceholderPattern.findAll(template).map { it.groupValues[1] }.toSet() intersect inputEnvNames
        // Both phases: a stop step's target or options steer the run exactly as a command's do (ADR-0108).
        val steps = agent.phaseTaggedSteps
        val steered =
            buildList {
                refs(agent.workspace.path).forEach { add("workspace.path uses input '{{$it}}'") }
                refs(agent.lockStrategy.key).forEach { add("lockStrategy.key uses input '{{$it}}'") }
                agent.cooldown?.key?.let { key -> refs(key).forEach { add("cooldown.key uses input '{{$it}}'") } }
                steps
                    .mapNotNull { (kind, cmd) -> (cmd as? Command.RunAgent)?.let { kind to it } }
                    .forEach { (kind, cmd) ->
                        refs(cmd.agentName).forEach { add("agent $kind '${cmd.label}' target uses input '{{$it}}'") }
                    }
                // Claude path/mode options grant filesystem or permission power.
                steps
                    .mapNotNull { (kind, cmd) -> (cmd as? Command.Claude)?.let { kind to it } }
                    .forEach { (kind, cmd) ->
                        val o = cmd.options

                        fun flag(
                            name: String,
                            it: String,
                        ) = add("claude $kind '${cmd.label}' option '$name' uses input '{{$it}}'")
                        mapOf(
                            "settings" to o.settings,
                            "systemPromptFile" to o.systemPromptFile,
                            "appendSystemPromptFile" to o.appendSystemPromptFile,
                            "permissionMode" to o.permissionMode,
                            "permissionPromptTool" to o.permissionPromptTool,
                        ).forEach { (name, value) ->
                            if (value != null) refs(value).forEach { flag(name, it) }
                        }
                        mapOf(
                            "addDir" to o.addDir,
                            "mcpConfig" to o.mcpConfig,
                            "pluginDir" to o.pluginDir,
                            "pluginUrl" to o.pluginUrl,
                            "file" to o.file,
                        ).forEach { (name, values) ->
                            values?.forEach { value -> refs(value).forEach { flag(name, it) } }
                        }
                    }
            }
        if (steered.isNotEmpty()) {
            logger.warn(
                "Agent '{}': caller-supplied inputs steer run identity — {}. " +
                    "Ensure this is intentional (see AGENT.md security notes).",
                agent.name,
                steered.joinToString("; "),
            )
        }
    }

    /**
     * WARNs per Claude step that skips permissions with `allowedTools` as its only tool setting:
     * `allowedTools` pre-approves without removing tools, so the step is not contained.
     */
    fun warnOnUncontainedClaudeTools(agent: Agent) {
        // Phase-tagged so a stop step is never reported as a "command" (ADR-0108).
        val steps = agent.phaseTaggedSteps
        steps
            .mapNotNull { (kind, cmd) -> (cmd as? Command.Claude)?.let { kind to it } }
            .filter { (_, cmd) -> cmd.options.bypassesPermissionsWithoutToolLimit() }
            .forEach { (kind, cmd) ->
                logger.warn(
                    "Agent '{}': claude {} '{}' skips permissions and sets only 'allowedTools', " +
                        "which pre-approves tools without removing any. Contain the step with 'tools' " +
                        "or 'disallowedTools: [Agent, Task]' (see AGENT.md).",
                    agent.name,
                    kind,
                    cmd.label,
                )
            }
    }
}
