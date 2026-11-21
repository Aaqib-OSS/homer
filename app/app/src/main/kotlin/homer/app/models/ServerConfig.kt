package homer.app.models

import kotlinx.serialization.Serializable

@Serializable
data class RateLimitConfig(
    val limit: Int = 1000,
    val refillPeriod: Long = 60, // seconds
)

@Serializable
data class Server(
    val port: Int = 8080,
    /** Required: `null` means the config omitted it and startup fails; running without auth needs an explicit
     * `apiAuth: !<unsecured>` opt-in. See ADR-0036. */
    val apiAuth: AuthConfig? = null,
    val rateLimit: RateLimitConfig = RateLimitConfig(),
    val perCallerRateLimit: RateLimitConfig = RateLimitConfig(limit = 300, refillPeriod = 60),
    val loginRateLimit: RateLimitConfig = RateLimitConfig(limit = 5, refillPeriod = 60),
    val corsAllowedOrigins: List<String> = emptyList(),
    /** Maximum depth of nested RunAgent invocations (the root API call is depth 1); a self-calling agent would
     * otherwise burn unbounded tokens. Raise it explicitly for deeper chains. */
    val maxAgentInvocationDepth: Int = 5,
    /** Maximum process rows retained; at the cap a new run evicts the oldest *terminal* row (deleting its
     * workspace), or is refused with 503 when none can be evicted. Raise this too for a longer record history. */
    val maxProcesses: Int = 10_000,
    /** Maximum accepted HTTP request body size in bytes; over-cap bodies are rejected with 413 before being read into memory. */
    val maxRequestBodyBytes: Long = 10L * 1024 * 1024,
    val shutdownDrainSec: Int = 30,
    /** If set, every successful Claude run must report this exact `claude_code_version`; a mismatch (or a missing
     * field when the pin is set) fails the run and is *not* retried. Leave null to skip the check. */
    val requireClaudeCodeVersion: String? = null,
    /** `system`-event subtypes dropped from a Claude step's stdout; entries must match `^[a-zA-Z0-9_]+$`, and
     * `init` is refused (it carries `claude_code_version`). See ADR-0063. */
    val claudeDropSystemSubtypes: List<String> = listOf("thinking_tokens"),
    /** Maximum size in bytes of a single artifact served by `GET /api/v1/artifacts`; larger files are rejected
     * with 413 before any bytes are sent. */
    val maxArtifactBytes: Long = 100L * 1024 * 1024,
    /** Per-run byte cap on artifacts retained past the workspace purge; overflow keeps declared-glob order and
     * flags the record `retainedTruncated`. See ADR-0053. */
    val maxRetainedArtifactBytes: Long = 16L * 1024 * 1024,
    /** Maximum size in bytes of an agent definition file, an agent-directory file accessed via the files API, or
     * the top-level Homer config file; rejected pre-flight, before parsing. */
    val maxConfigFileBytes: Long = 1L * 1024 * 1024,
    val localRunner: LocalRunnerConfig = LocalRunnerConfig(),
    val runnerClaimTimeoutSec: Int = DEFAULT_RUNNER_CLAIM_TIMEOUT_SEC,
    val maxPendingRunnerClaims: Int = DEFAULT_MAX_PENDING_RUNNER_CLAIMS,
    /** Maximum size in bytes of a single command's `cmd_N.out` / `cmd_N.err` stream; exceeding it kills the
     * command and surfaces `ExecutionInfo.outputTruncated`. Enforced by the 1-second poll, so it is a soft ceiling. */
    val maxOutputBytes: Long = 10L * 1024 * 1024,
    val claudeStreamPollMs: Int = 150,
    val mcp: McpServerConfig = McpServerConfig(),
    val questions: QuestionsConfig = QuestionsConfig(),
    val messages: RunMessagesConfig = RunMessagesConfig(),
    val dispatch: DispatchConfig = DispatchConfig(),
    val hooks: HookConfig = HookConfig(),
    val langGraph: LangGraphConfig = LangGraphConfig(),
    val maintenance: MaintenanceConfig = MaintenanceConfig(),
    /** Per-agent grants keyed by agent name: which target agents (and actions) that agent's dispatches may reach. See ADR-0037. */
    val agentGrants: Map<String, List<Grant>> = emptyMap(),
) {
    init {
        require(maxRequestBodyBytes > 0) { "server.maxRequestBodyBytes must be > 0" }
        claudeDropSystemSubtypes.forEach { subtype ->
            require(SYSTEM_SUBTYPE_PATTERN.matches(subtype)) {
                "server.claudeDropSystemSubtypes entries must match ^[a-zA-Z0-9_]+$; got '$subtype'"
            }
            require(subtype != "init") {
                "server.claudeDropSystemSubtypes must not contain 'init': that event carries " +
                    "claude_code_version, which server.requireClaudeCodeVersion reads"
            }
        }
        require(runnerClaimTimeoutSec >= 1) { "server.runnerClaimTimeoutSec must be >= 1; got $runnerClaimTimeoutSec" }
        require(maxPendingRunnerClaims >= 1) { "server.maxPendingRunnerClaims must be >= 1; got $maxPendingRunnerClaims" }
    }

    companion object {
        private val SYSTEM_SUBTYPE_PATTERN = Regex("^[a-zA-Z0-9_]+$")

        /** Default [maxPendingRunnerClaims], and the bound a `RunnerRegistry` built without an explicit queue
         * uses — the two must not drift. See ADR-0066. */
        const val DEFAULT_MAX_PENDING_RUNNER_CLAIMS = 1000

        /** Default [runnerClaimTimeoutSec], and the claim timeout a `RunnerRegistry` built without an explicit
         * one uses — the two must not drift. See ADR-0066. */
        const val DEFAULT_RUNNER_CLAIM_TIMEOUT_SEC = 300
    }
}

/** Admin maintenance mode: while on, cron fires are skipped and every other agent start waits for it to go off.
 * See ADR-0092. */
@Serializable
data class MaintenanceConfig(
    /** Starts that may wait at once; a start arriving at the bound is shed with 429. §12 rule 4. */
    val maxQueuedStarts: Int = 500,
    /** Starts a single caller may hold in the queue; a start past it is shed with 429. */
    val maxQueuedStartsPerPrincipal: Int = 50,
    /** Longest a queued start waits before it fails with 503. §12 rule 5. */
    val queueWaitSec: Int = 600,
    /** Starts released per moment when the mode goes off, so the release cannot flood runners. */
    val releaseConcurrency: Int = 4,
) {
    init {
        require(maxQueuedStarts >= 1) { "server.maintenance.maxQueuedStarts must be >= 1; got $maxQueuedStarts" }
        require(maxQueuedStartsPerPrincipal >= 1) {
            "server.maintenance.maxQueuedStartsPerPrincipal must be >= 1; got $maxQueuedStartsPerPrincipal"
        }
        require(queueWaitSec >= 1) { "server.maintenance.queueWaitSec must be >= 1; got $queueWaitSec" }
        require(releaseConcurrency >= 1) {
            "server.maintenance.releaseConcurrency must be >= 1; got $releaseConcurrency"
        }
    }
}

/** LangGraph runtime config. `stateDir` is the durable directory for SQLite checkpoints, which must live OUTSIDE
 * the ephemeral per-process workspace so continuations survive across runs; null derives it beside `databasePath`. */
@Serializable
data class LangGraphConfig(
    val stateDir: String? = null,
)

/** Streamable HTTP MCP endpoint at `/mcp`; disabled by default. See ADR-0018. */
@Serializable
data class McpServerConfig(
    val enabled: Boolean = false,
    /** Upper bound (seconds) for the per-call `_wait` argument; requested waits are clamped to `0..maxWaitSec`. */
    val maxWaitSec: Int = 300,
    /** Maximum artifact size (bytes) the `get_artifact` tool inlines; larger files are truncated to this many bytes. */
    val maxInlineArtifactBytes: Long = 65_536,
) {
    init {
        require(maxWaitSec >= 0) { "server.mcp.maxWaitSec must be >= 0; got $maxWaitSec" }
        require(maxInlineArtifactBytes > 0) {
            "server.mcp.maxInlineArtifactBytes must be > 0; got $maxInlineArtifactBytes"
        }
    }
}

/** Per-run inbox: operator messages sent to a running run. See ADR-0097. */
@Serializable
data class RunMessagesConfig(
    val maxTextLength: Int = 10_000,
    val maxPerRun: Int = 100,
) {
    init {
        require(maxTextLength > 0) { "server.messages.maxTextLength must be > 0; got $maxTextLength" }
        require(maxPerRun > 0) { "server.messages.maxPerRun must be > 0; got $maxPerRun" }
    }
}

/** Human-in-the-loop questions asked by running commands. See ADR-0019. */
@Serializable
data class QuestionsConfig(
    /** Upper bound (seconds) for a single answer-wait; caller-supplied waits are clamped to it. Waits are
     * resumable, so this caps one HTTP hold, not the question's total lifetime. */
    val maxWaitSec: Int = 86_400,
    val maxOpenPerProcess: Int = 10,
    val maxTextLength: Int = 10_000,
    val maxOptions: Int = 20,
    /** Maximum simultaneously held answer-waits per process; each pins a connection and a coroutine, so further
     * waits are rejected with 429. */
    val maxConcurrentWaitsPerProcess: Int = 32,
) {
    init {
        require(maxWaitSec > 0) { "server.questions.maxWaitSec must be > 0; got $maxWaitSec" }
        require(maxOpenPerProcess > 0) {
            "server.questions.maxOpenPerProcess must be > 0; got $maxOpenPerProcess"
        }
        require(maxTextLength > 0) { "server.questions.maxTextLength must be > 0; got $maxTextLength" }
        require(maxOptions > 0) { "server.questions.maxOptions must be > 0; got $maxOptions" }
        require(maxConcurrentWaitsPerProcess > 0) {
            "server.questions.maxConcurrentWaitsPerProcess must be > 0; got $maxConcurrentWaitsPerProcess"
        }
    }
}

/** ask-and-dispatch: a question plus a target agent, fired after the operator answers. See ADR-0021. */
@Serializable
data class DispatchConfig(
    val maxPending: Int = 100,
    val defaultTtlSec: Int = 604_800,
    val sweepIntervalSec: Int = 60,
    /** How long (seconds, from creation) a settled dispatch or continuation is kept before the sweeps delete it;
     * floored at one hour. */
    val settledRetentionSec: Long = 604_800,
    /** TTL (seconds) for read-by-reference result tokens; floored at 60 s so a token outlives the continuation's
     * dispatch and the resumed parent's first segment. See ADR-0025. */
    val resultTokenTtlSec: Int = 3600,
    val maxJoinSubs: Int = 32,
    /** Max questions one grouped ask-and-dispatch may hold; over this is rejected with 400 before any row is
     * written. See ADR-0109. */
    val maxGroupSize: Int = 8,
) {
    init {
        require(maxPending > 0) { "server.dispatch.maxPending must be > 0; got $maxPending" }
        require(maxJoinSubs > 0) { "server.dispatch.maxJoinSubs must be > 0; got $maxJoinSubs" }
        require(maxGroupSize > 0) { "server.dispatch.maxGroupSize must be > 0; got $maxGroupSize" }
        require(defaultTtlSec > 0) {
            "server.dispatch.defaultTtlSec must be > 0; got $defaultTtlSec"
        }
        require(sweepIntervalSec > 0) { "server.dispatch.sweepIntervalSec must be > 0; got $sweepIntervalSec" }
        require(settledRetentionSec >= 3600) {
            "server.dispatch.settledRetentionSec must be >= 3600; got $settledRetentionSec"
        }
        require(resultTokenTtlSec >= 60) {
            "server.dispatch.resultTokenTtlSec must be >= 60; got $resultTokenTtlSec"
        }
    }
}

/** Agent-level status hooks: the local bounds on durable hook rows and chain depth. See ADR-0107. */
@Serializable
data class HookConfig(
    /** Global cap on PENDING hook rows; a transition that would exceed it drops the excess with a log line
     * rather than failing the transition. */
    val maxPending: Int = 200,
    /** Longest chain of hook-started runs; a hook whose triggering run is already at this depth is dropped with
     * a log line. */
    val maxChainDepth: Int = 8,
    /** How long (seconds, from creation) a settled hook is kept before the sweep deletes it; floored at one hour. */
    val settledRetentionSec: Long = 604_800,
) {
    init {
        require(maxPending > 0) { "server.hooks.maxPending must be > 0; got $maxPending" }
        require(maxChainDepth >= 1) { "server.hooks.maxChainDepth must be >= 1; got $maxChainDepth" }
        require(settledRetentionSec >= 3600) {
            "server.hooks.settledRetentionSec must be >= 3600; got $settledRetentionSec"
        }
    }
}
