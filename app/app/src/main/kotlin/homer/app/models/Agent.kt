package homer.app.models

import homer.app.EnvConstants
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import java.nio.file.FileSystems

@Serializable
data class CronConfig(
    val expression: String,
    val timezone: String = "UTC",
)

@Serializable
data class Agent(
    val name: String,
    val description: String? = null,
    /** Optional operator-facing label; the console's Agents page renders one tab per distinct group. */
    val group: String? = null,
    val workspace: Workspace = Workspace(),
    val commands: List<Command>,
    /** Steps run in the same session after a graceful stop, before the workspace syncs down. See ADR-0108. */
    val onStop: List<Command> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val inputs: List<Param>? = null,
    @Deprecated("Use 'inputs' instead", ReplaceWith("inputs"))
    val queryParams: List<Param>? = null,
    val lockStrategy: LockStrategy = LockStrategy(),
    val cron: CronConfig? = null,
    val maxParallelRuns: Int = 10,
    val onMaxParallelRuns: OnMaxParallelRunsStrategy = OnMaxParallelRunsStrategy.WAIT,
    // Under `onMaxParallelRuns: WAIT`, max runs queued for a slot or a lock (one shared cap);
    // default 10 x maxParallelRuns. See ADR-0104.
    val maxQueuedRuns: Int = (maxParallelRuns.toLong() * 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    /** Runner alias this agent runs on; null = `local`. Mutually exclusive with [runnerTags]. See ADR-0066. */
    val runner: String? = null,
    /** Tags a runner must serve to take this agent's runs; empty = select by [runner]. See ADR-0066. */
    val runnerTags: List<String> = emptyList(),
    /** Seconds to wait for a runner to claim a run; null = `server.runnerClaimTimeoutSec`. */
    val runnerClaimTimeoutSec: Int? = null,
    /** Opt-in preference for the runner already holding this flow's host-local caches. See ADR-0099. */
    val runnerAffinity: RunnerAffinity? = null,
    // Optional JSON Schema (Draft 2020-12) validating POST /api/v1/agent/{name}'s body; a malformed
    // schema skips the agent at load, a violation -> 422. Null = no payload validation.
    @Serializable(with = JsonObjectSerializer::class)
    val inputSchema: JsonObject? = null,
    // Opt-in exposure of this agent as an MCP tool on the /mcp endpoint
    // (requires server.mcp.enabled). Null or enabled=false = not exposed.
    val mcp: AgentMcpConfig? = null,
    // Opt-in: may this agent be the TARGET of an ask-and-dispatch dispatch?
    // Null or enabled=false = cannot be dispatched. See the ask-and-dispatch ADR.
    val dispatch: AgentDispatchConfig? = null,
    // Opt-in: a continuation fired to THIS agent from a run of the same agent inherits that run's
    // resolved inputs, so the resumed segment keeps its subject. See ADR-0101.
    val continuation: AgentContinuationConfig? = null,
    /** How this agent's runs appear in the OTel collector. See [AgentTelemetry]. */
    val telemetry: AgentTelemetry = AgentTelemetry(),
    /** The run's subject tags: what it runs over, and how they are declared. See [Tags]. */
    val tags: Tags = Tags(),
    /** Optional minimum spacing between runs of this agent; enforced at every `AgentRunStarter` path. */
    val cooldown: Cooldown? = null,
    /** Each one-key `{STATUS: agentName}` entry starts its agent when a run of *this* agent reaches that status. See ADR-0107. */
    val hooks: List<AgentHook> = emptyList(),
    // Template for the *stem* of per-command stdout/stderr artifact files (`.out`/`.err` appended);
    // the default renders the same path every attempt, truncating in place.
    val artifactNameTemplate: String = EnvConstants.DEFAULT_ARTIFACT_NAME_TEMPLATE,
    // Runtime-only: reflects whether the on-disk agent directory is writable. Not serialized.
    @Transient
    val writable: Boolean = true,
    // Runtime-only, not serialized: names to redact for this agent's `env` (the `@SYSENV:` keys and
    // the host variables they referenced); populated by AgentsService at scan time.
    @Transient
    val sysEnvBackedEnvNames: Set<String> = emptySet(),
    // Runtime-only, not serialized: the keys half of [sysEnvBackedEnvNames] (no host-variable names).
    // The `cooldown.key` load check reads this, not the redaction composite. See ADR-0106.
    @Transient
    val sysEnvBackedEnvKeys: Set<String> = emptySet(),
) {
    init {
        // Reads the `queryParams` alias (ADR-0031): an agent may set only one of the two forms.
        @Suppress("DEPRECATION")
        require(inputs == null || queryParams == null) {
            "Agent '$name': set either 'inputs' or legacy 'queryParams', not both"
        }
        require(maxParallelRuns >= 1) { "Agent '$name': maxParallelRuns must be >= 1, got $maxParallelRuns" }
        require(maxQueuedRuns >= 0) {
            "Agent '$name': maxQueuedRuns must be >= 0, got $maxQueuedRuns"
        }
        require(runner == null || runnerTags.isEmpty()) {
            "Agent '$name': runner and runnerTags are mutually exclusive"
        }
        require(runner == null || runner.isNotBlank()) { "Agent '$name': runner must not be blank" }
        RunnerTagValidation.validate("Agent '$name'", runnerTags)
        AgentGroupValidation.validate("Agent '$name'", group)
        require(runnerClaimTimeoutSec == null || runnerClaimTimeoutSec >= 1) {
            "Agent '$name': runnerClaimTimeoutSec must be >= 1 when set, got $runnerClaimTimeoutSec"
        }
        runnerAffinity?.let { RunnerAffinityValidation.validate("Agent '$name'", it) }
        require(hooks.size <= MAX_AGENT_HOOKS) {
            "Agent '$name': at most $MAX_AGENT_HOOKS hooks are allowed, got ${hooks.size}"
        }
        // A self-target is an immediate cycle; rejected here so both the load path and the
        // definition-write gate see it. The transitive form is AgentHookValidation's job.
        hooks.forEach { hook ->
            require(hook.agent != name) {
                "Agent '$name': hook '${hook.status}' must not target the declaring agent"
            }
        }
        // Both `secretEnvNames` and `effectiveInputs` initialize after this block: read the alias directly.
        @Suppress("DEPRECATION")
        val declaredSecretInputNames =
            (inputs ?: queryParams)
                .orEmpty()
                .filter { it.secret }
                .map { it.envName }
                .toSet()
        val secretKeyRefs = cooldown?.keyRefs.orEmpty() intersect declaredSecretInputNames
        require(secretKeyRefs.isEmpty()) {
            "Agent '$name': cooldown.key must not reference a secret input: ${secretKeyRefs.sorted().joinToString(", ")}"
        }
        // An env entry whose raw value referenced `@SYSENV:` is a secret by construction; a key
        // naming it would store that value in the plain-text `cooldown_key` column. See ADR-0106.
        val sysEnvKeyRefs = cooldown?.keyRefs.orEmpty() intersect sysEnvBackedEnvKeys
        require(sysEnvKeyRefs.isEmpty()) {
            "Agent '$name': cooldown.key must not reference an @SYSENV-backed env value: ${sysEnvKeyRefs.sorted().joinToString(", ")}"
        }
    }

    // The `queryParams` alias is the fallback for agents still using it. See ADR-0031.
    @Transient
    @Suppress("DEPRECATION") // reads the queryParams alias on purpose
    val effectiveInputs: List<Param>? = inputs ?: queryParams

    /** The claim-queue filter this agent's runs wait on. See ADR-0066. */
    @Transient
    val runnerFilter: RunnerFilter =
        if (runnerTags.isNotEmpty()) RunnerFilter.Tags(runnerTags.toSet()) else RunnerFilter.Alias(runner ?: "local")

    // envNames of inputs declared `secret: true` right now; replay paths union this with the source
    // run's snapshot, since the definition may have changed. See ADR-0052.
    @Transient
    val secretEnvNames: Set<String> =
        effectiveInputs
            .orEmpty()
            .filter { it.secret }
            .map { it.envName }
            .toSet()

    // paramNames of inputs declared `secret: true` right now — the paramName counterpart of
    // [secretEnvNames], snapshotted because dispatch/continuation `params` are paramName-keyed. See ADR-0052.
    @Transient
    val secretParamNames: Set<String> =
        effectiveInputs
            .orEmpty()
            .filter { it.secret }
            .map { it.paramName }
            .toSet()

    // `tags.fromInputs` unioned with the deprecated `telemetry.traceTags`; one list feeds both the
    // trace tags and the process-tag rows, so the two never diverge. See ADR-0050, ADR-0060.
    @Transient
    @Suppress("DEPRECATION") // unions the telemetry.traceTags alias on purpose (ADR-0060)
    val effectiveFromInputTags: List<String> =
        (tags.fromInputs + telemetry.traceTags).distinct()
}

// Opt-in, flow-scoped runner affinity: a run prefers the instance already holding the flow's
// host-local caches; soft (orders only runners matching the agent's filter). See ADR-0099.
@Serializable
data class RunnerAffinity(
    val group: String,
    val waitSec: Int,
)

// Minimum spacing between runs of one agent — the frequency bound `lockStrategy` and `costLimits`
// do not provide. At least one window must be set; `key` narrows both to one subject. See ADR-0106.
@Serializable
data class Cooldown(
    /** Minimum time since the previous run of this agent *started*. */
    val minInterval: String? = null,
    /** Minimum time since the previous run *ended*, applied only when that run FAILED. */
    val afterFailure: String? = null,
    // Optional `{{VAR}}` template naming the subject both windows are counted per; a placeholder
    // that does not resolve fails the run. See ADR-0106.
    val key: String? = null,
) {
    init {
        require(minInterval != null || afterFailure != null) {
            "cooldown must set minInterval, afterFailure, or both"
        }
        minInterval?.let { PurgeAfterParser.toSeconds(it, "cooldown.minInterval") }
        afterFailure?.let { PurgeAfterParser.toSeconds(it, "cooldown.afterFailure") }
        key?.let { template ->
            require(template.isNotBlank()) { "cooldown.key must not be blank" }
            require(template.length <= MAX_COOLDOWN_KEY_TEMPLATE_LENGTH) {
                "cooldown.key must be at most $MAX_COOLDOWN_KEY_TEMPLATE_LENGTH characters, got ${template.length}"
            }
            // A `{{` the placeholder pattern cannot match survives rendering as a literal, giving
            // every subject the same key. See ADR-0106.
            require(!templatePlaceholderPattern.replace(template, "").contains("{{")) {
                "cooldown.key has a malformed placeholder: use {{NAME}} with a name of letters, digits or underscore"
            }
        }
    }

    val minIntervalSeconds: Long? get() = minInterval?.let { PurgeAfterParser.toSeconds(it, "cooldown.minInterval") }

    val afterFailureSeconds: Long? get() = afterFailure?.let { PurgeAfterParser.toSeconds(it, "cooldown.afterFailure") }

    /** Names [key]'s placeholders reference; derived, so `@Transient` and must not reach JSON. */
    @Transient
    val keyRefs: Set<String> = templatePlaceholderPattern.findAll(key.orEmpty()).map { it.groupValues[1] }.toSet()
}

/** The `{{VAR}}` placeholder syntax every templated agent field shares; one copy so the grammar cannot drift. */
internal val templatePlaceholderPattern = Regex("""\{\{(\w+)\}\}""")

/** Load-time cap on the `cooldown.key` *template*; the rendered key has its own cap. */
private const val MAX_COOLDOWN_KEY_TEMPLATE_LENGTH = 256

/** Everything this agent decides about its own telemetry; server-wide export still gates all of it. See ADR-0050. */
@Serializable
data class AgentTelemetry(
    val export: TelemetryExport = TelemetryExport.ALL,
    // paramNames of inputs whose resolved values become `paramName:value` trace tags; must match a
    // declared, non-secret input (checked at load). See ADR-0050, ADR-0060.
    @Deprecated("Use the top-level 'tags.fromInputs' block instead", ReplaceWith("tags.fromInputs"))
    val traceTags: List<String> = emptyList(),
)

// A run's subject tags. [fromInputs]: declared inputs by `paramName`, each resolved into a tag at start.
// [runtime]: keys a command may declare later via `homer-tag`; disjoint from [fromInputs]. See ADR-0060.
@Serializable
data class Tags(
    val fromInputs: List<String> = emptyList(),
    val runtime: List<String> = emptyList(),
)

/** Whether an agent's runs reach the collector at all. See [AgentTelemetry.export]. */
@Serializable
enum class TelemetryExport {
    /** Export this agent's runs (default). */
    ALL,

    /** Export nothing for this agent's runs — the whole trace, not just the root span. */
    OFF,
}

@Serializable
data class AgentMcpConfig(
    val enabled: Boolean = false,
)

@Serializable
data class AgentDispatchConfig(
    val enabled: Boolean = false,
)

/** Opt-in inheritance of the registering run's inputs by a continuation to the same agent; default off. See ADR-0101. */
@Serializable
data class AgentContinuationConfig(
    val inheritInputs: Boolean = false,
)

@Serializable
data class AgentCommandParam(
    val envName: String,
    val paramName: String,
    val transformer: ResultTransformer = ResultTransformer.Pass,
)

/**
 * A non-null `whenClause` gates a command: each expression runs as `bash -c` against the command's env,
 * 5 s each. Exit 0 = true, 1 = false, >=2 = eval error that fails the agent; ALL = AND, ANY = OR. See ADR-0009.
 */
@Serializable(with = WhenSerializer::class)
data class When(
    val operator: Operator,
    val expressions: List<String>,
) {
    enum class Operator { ALL, ANY }

    init {
        require(expressions.isNotEmpty()) { "when must have at least one expression" }
        require(expressions.all { it.isNotBlank() }) { "when expressions must be non-blank" }
    }
}

@Serializable
data class ResultEnvParam(
    val src: SrcType,
    val envName: String,
    val transformer: ResultTransformer = ResultTransformer.Pass,
)

@Serializable
sealed class ResultTransformer {
    @Serializable
    @SerialName("jsonPath")
    data class JsonPath(
        val path: String,
    ) : ResultTransformer()

    @Serializable
    @SerialName("regex")
    data class Regex(
        val regex: String,
        val group: Int? = null,
    ) : ResultTransformer()

    @Serializable
    @SerialName("pass")
    object Pass : ResultTransformer()
}

@Serializable
enum class SrcType {
    STD_OUT,
    STD_ERR,
    RESULT_CODE,
}

@Serializable
data class Workspace(
    val path: String = "{{${EnvConstants.PROCESS_ID}}}",
    val cleanAfterFinish: Boolean = true,
    val purgeAfter: String = "5d",
    /** What outlives [purgeAfter], and for how long; null = nothing does. See ADR-0053. */
    val retain: Retain? = null,
    // Removed in favour of retain.recordFor (ADR-0053); kept so a leftover config fails with a
    // migration hint rather than being silently ignored (JSON loading ignores unknown keys).
    val purgeRecordAfter: String? = null,
) {
    init {
        // A workspace.path containing '..' could normalize outside, or collapse back onto, the workspace root.
        require(!path.contains("..")) {
            "workspace.path must not contain '..': $path"
        }
        require(purgeRecordAfter == null) {
            "workspace.purgeRecordAfter was replaced by workspace.retain.recordFor: " +
                "use retain: { recordFor: $purgeRecordAfter } (ADR-0053)"
        }
        retain?.let { r ->
            if (r.artifacts.isNotEmpty()) {
                // Retained files must not land in a directory a later run can reuse: the next run's
                // artifact routes would serve the previous run's retained files across owners. ADR-0053.
                require(path.contains("{{${EnvConstants.PROCESS_ID}}}")) {
                    "workspace.retain.artifacts requires a per-run workspace path: " +
                        "workspace.path must contain {{${EnvConstants.PROCESS_ID}}} (ADR-0053)"
                }
            }
            val record = PurgeAfterParser.toSeconds(r.recordFor, "workspace.retain.recordFor")
            val workspace = PurgeAfterParser.toSeconds(purgeAfter, "workspace.purgeAfter")
            require(record >= workspace) {
                "workspace.retain.recordFor (${r.recordFor}) must be >= workspace.purgeAfter ($purgeAfter): " +
                    "the record has to outlive the directory, not the other way round"
            }
        }
    }
}

/** What survives the workspace purge: the record for [recordFor], plus [artifacts] kept in place. See ADR-0053. */
@Serializable
data class Retain(
    val recordFor: String,
    val artifacts: List<String> = emptyList(),
) {
    init {
        artifacts.forEach { glob ->
            require(glob.isNotBlank()) { "workspace.retain.artifacts entries must not be blank" }
            require(!glob.startsWith("/") && !glob.contains("..") && !glob.contains('\\')) {
                "workspace.retain.artifacts glob '$glob' must be a relative forward-slash path without '..'"
            }
            try {
                FileSystems.getDefault().getPathMatcher("glob:$glob")
            } catch (e: RuntimeException) {
                throw IllegalArgumentException("workspace.retain.artifacts glob '$glob' is not a valid glob: ${e.message}", e)
            }
        }
    }
}

@Serializable
data class LockStrategy(
    val key: String = "{{${EnvConstants.PROCESS_ID}}}",
    val onCollision: OnKeyCollisionStrategy = OnKeyCollisionStrategy.WAIT,
    val acceptParentLock: Boolean = false,
)

enum class OnKeyCollisionStrategy {
    KILL_OLD_RUN_CURRENT,
    SKIP_CURRENT,
    WAIT,
}

enum class OnMaxParallelRunsStrategy {
    WAIT,
    REJECT,
}
