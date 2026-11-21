package homer.app.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface LlmPrompt {
    @Serializable
    @SerialName("prompt")
    data class Prompt(
        val prompt: String,
    ) : LlmPrompt

    @Serializable
    @SerialName("langfuse")
    data class Langfuse(
        val promptName: String,
        val version: Int? = null,
        val label: String? = null,
    ) : LlmPrompt
}

/**
 * Retry for the `claude` command, wrapping the whole CLI invocation: a non-zero exit retries (except a terminal
 * stop opted into via `maxTurnsExitCode` / `isErrorExitCode`), as does `MalformedLlmOutputException`; others
 * propagate. Full-jitter backoff `min(maxDelayMs, baseDelayMs * 2^attemptIndex + random(0, baseDelayMs))`.
 */
@Serializable
data class LlmRetry(
    val attempts: Int = 1,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
) {
    init {
        require(attempts >= 1) { "LlmRetry.attempts must be >= 1" }
        require(baseDelayMs >= 0) { "LlmRetry.baseDelayMs must be >= 0" }
        require(maxDelayMs >= baseDelayMs) {
            "LlmRetry.maxDelayMs ($maxDelayMs) must be >= baseDelayMs ($baseDelayMs)"
        }
    }
}

/**
 * One escalation tier of an LLM step (ADR-0079): rerun the same prompt on [model], with the raw CLI
 * [options] list replaced when set, if [whenClause] — evaluated after the previous tier, with
 * `HMR_LLM_OUTCOME` and `HMR_LLM_EXIT_CODE` describing it — is true.
 */
@Serializable
data class LlmEscalation(
    val model: String,
    val options: List<String>? = null,
    @SerialName("when")
    val whenClause: When,
) {
    init {
        require(model.isNotBlank()) { "escalate[].model must be non-blank" }
    }
}

@Serializable
data class ClaudeSubagent(
    val prompt: String,
    val description: String? = null,
    val tools: List<String>? = null,
    val model: String? = null,
)

@Serializable
data class ClaudeCmdOptions(
    val agent: String? = null,
    val agents: Map<String, ClaudeSubagent>? = null,
    val maxTurns: Int = 40,
    // Opt-in: exit code returned instead of failing when Claude stops with `error_max_turns`, so a
    // later step can handle the partial work. Unset fails closed.
    val maxTurnsExitCode: Int? = null,
    // Opt-in: when the result event is a `success` subtype carrying `is_error`, return this exit
    // code instead of failing the run as malformed output. Unset keeps the fail-closed behaviour.
    val isErrorExitCode: Int? = null,
    val maxMcpOutputTokens: Int? = null,
    val model: String? = null,
    val fallbackModel: String? = null,
    val systemPrompt: String? = null,
    val systemPromptFile: String? = null,
    val appendSystemPrompt: String? = null,
    val appendSystemPromptFile: String? = null,
    val permissionMode: String? = null,
    val permissionPromptTool: String? = null,
    val maxBudgetUsd: Double? = null,
    val sessionId: String? = null,
    @Serializable(with = StringOrStringListSerializer::class) val addDir: List<String>? = null,
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String>? = null,
    @Serializable(with = StringOrStringListSerializer::class) val mcpConfig: List<String>? = null,
    val strictMcpConfig: Boolean = false,
    val includeHookEvents: Boolean = false,
    val includePartialMessages: Boolean = false,
    val inputFormat: String? = null,
    @Serializable(with = StringOrStringListSerializer::class) val pluginDir: List<String>? = null,
    @Serializable(with = StringOrStringListSerializer::class) val pluginUrl: List<String>? = null,
    @Serializable(with = StringOrStringListSerializer::class) val file: List<String>? = null,
    val settings: String? = null,
    val settingSources: List<String>? = null,
    val tools: List<String>? = null,
    val disableSlashCommands: Boolean = false,
    val effort: String? = null,
    val excludeDynamicSystemPromptSections: Boolean = false,
    val noSessionPersistence: Boolean = false,
    val bare: Boolean = false,
    val jsonSchema: String? = null,
    val betas: String? = null,
    val debugFile: String? = null,
    // Opt-in `--dangerously-skip-permissions`; only safe in isolated environments (ADR-0017). A set
    // permissionMode takes precedence and the flag is omitted.
    val dangerouslySkipPermissions: Boolean = false,
    val options: List<String>? = null,
) {
    init {
        // CWE-394: maxTurnsExitCode must be in 1..255; a value of 0
        // would signal success on a max-turns failure.
        maxTurnsExitCode?.let {
            require(it in 1..255) {
                "maxTurnsExitCode must be in 1..255 (got $it); 0 would signal success on a max-turns failure"
            }
        }
        // CWE-394: the same bound for isErrorExitCode; a value of 0
        // would signal success on an is_error failure.
        isErrorExitCode?.let {
            require(it in 1..255) {
                "isErrorExitCode must be in 1..255 (got $it); 0 would signal success on an is_error failure"
            }
        }
    }
}

/**
 * Options for the `codex` command, mapped 1:1 to `codex exec` CLI flags.
 * The executor always appends `--json --color never` and reads the prompt
 * from stdin (`-` positional), mirroring how [ClaudeCmdOptions] is driven.
 */
@Serializable
data class CodexCmdOptions(
    val model: String? = null,
    val profile: String? = null,
    // read-only | workspace-write | danger-full-access
    val sandbox: String? = null,
    val cd: String? = null,
    val addDir: String? = null,
    val skipGitRepoCheck: Boolean = false,
    val ephemeral: Boolean = false,
    val ignoreUserConfig: Boolean = false,
    val ignoreRules: Boolean = false,
    val outputSchema: String? = null,
    val outputLastMessage: String? = null,
    // Opt-in `--dangerously-bypass-approvals-and-sandbox`; only safe in isolated environments (ADR-0017).
    // A set sandbox takes precedence and the flag is omitted.
    val dangerouslyBypassApprovalsAndSandbox: Boolean = false,
    // Repeated `-c key=value` config overrides (TOML values).
    val config: List<String>? = null,
    val options: List<String>? = null,
)

/**
 * Options for the `opencode` command, mapped 1:1 to `opencode run` CLI flags.
 * The executor always appends `--format json` and reads the prompt from stdin,
 * mirroring how [CodexCmdOptions] is driven.
 */
@Serializable
data class OpencodeCmdOptions(
    val model: String? = null,
    val agent: String? = null,
    val dir: String? = null,
    // Repeated `-f path` attachments.
    val file: List<String>? = null,
    // Opt-in `--auto`: approves every permission the configuration does not deny; only safe in
    // isolated environments (ADR-0017).
    val auto: Boolean = false,
    val options: List<String>? = null,
)
