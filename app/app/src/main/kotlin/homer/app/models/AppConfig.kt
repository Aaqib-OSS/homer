package homer.app.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

@Serializable
data class AppConfig(
    val server: Server = Server(),
    val agentsSearchPath: String,
    val workspaceRootPath: String,
    // Required, no default, `@SYSENV:`-resolvable; in containers it MUST be on a persisted volume
    // or process history and open questions are lost on restart. See ADR-0020.
    val databasePath: String,
    val langfuse: LangfuseConfig = LangfuseConfig(),
    val globalEnv: Map<String, String> = emptyMap(),
    // Host-env keys layered under `globalEnv` for the `local` runner only; an allowlisted value is
    // NOT added to the telemetry redaction set (unlike `@SYSENV:`). See ADR-0065.
    val parentEnvAllowlist: List<String> = listOf("PATH", "HOME", "LANG", "TZ"),
    /** Host variable names an agent `env` may reference with `@SYSENV:`, beside `globalEnv`'s. See ADR-0037. */
    val agentSysEnvAllowlist: List<String> = emptyList(),
    val openTelemetryExport: OpenTelemetryExport = OpenTelemetryExport.LANGFUSE,
    val runners: Map<String, RunnerConfig> = emptyMap(),
    val costLimits: CostLimits = CostLimits(),
    /** Optional pricing of Claude runs from sources other than the CLI estimate; null = price from `total_cost_usd`. See ADR-0061. */
    val llmPrice: LlmPriceConfig? = null,
    /** Subject-tag keys queryable via `GET /api/v1/costs/by-tag/{key}`; validated and capped. See ADR-0062. */
    val costTrackedTagKeys: List<String> = emptyList(),
    /** Runtime-only (not a config-file field): names to redact for `globalEnv`, seeded by `Application`. */
    @Transient
    val sysEnvBackedGlobalEnvNames: Set<String> = emptySet(),
    /** Runtime-only: per-runner-alias redaction names, the runner-`env` counterpart of [sysEnvBackedGlobalEnvNames]. */
    @Transient
    val sysEnvBackedRunnerEnvNames: Map<String, Set<String>> = emptyMap(),
    /** Runtime-only: the host variable names the raw `globalEnv` references via `@SYSENV:`, seeded by `Application`. */
    @Transient
    val globalEnvSysEnvNames: Set<String> = emptySet(),
) {
    /** The host variable names an agent `env` may reference: [agentSysEnvAllowlist] plus [globalEnvSysEnvNames]. */
    val agentSysEnvNames: Set<String>
        get() = agentSysEnvAllowlist.toSet() + globalEnvSysEnvNames

    init {
        require("local" !in runners.keys) {
            "Runner alias 'local' is reserved for the built-in local runner and must not appear in AppConfig.runners."
        }
        // Alias-aware so config-load violations name the runner (ADR-0042); each
        // variant runs its own rules, in its own order, through the base member.
        runners.forEach { (alias, runner) -> runner.validate(alias) }
        val domainTokens = runners.values.filterIsInstance<RunnerConfig.External>().map { it.token }
        require(domainTokens.toSet().size == domainTokens.size) {
            "Two external runner domains share a token value; every domain needs its own token."
        }
        require(costTrackedTagKeys.size <= TagValidation.MAX_TAG_QUERY_PARAMS) {
            "costTrackedTagKeys must not exceed ${TagValidation.MAX_TAG_QUERY_PARAMS} entries, " +
                "found ${costTrackedTagKeys.size}."
        }
        agentSysEnvAllowlist.forEach { name ->
            require(SYSENV_NAME_PATTERN.matches(name)) {
                "agentSysEnvAllowlist entry '$name' is not a host variable name matching $SYSENV_NAME_PATTERN."
            }
        }
        costTrackedTagKeys.forEach { key ->
            require(TagValidation.KEY_PATTERN.matches(key)) {
                "costTrackedTagKeys entry '$key' does not match ${TagValidation.KEY_PATTERN}."
            }
        }
    }

    // Per-owner split is a boundary: a thread id from the agent-writable `langgraph.json` must not
    // address another principal's checkpoints. See ADR-0046.
    fun resolveLangGraphStateDir(owner: String?): String {
        val root =
            server.langGraph.stateDir?.let { Path.of(it) }
                ?: (Path.of(databasePath).toAbsolutePath().parent ?: Path.of(".").toAbsolutePath())
                    .resolve("langgraph")
        return root
            .resolve(ownerStateSegment(owner))
            .toAbsolutePath()
            .normalize()
            .toString()
    }
}

/** The grammar of a variable name `@SYSENV:` can reference; `SysEnvResolver` builds its placeholder pattern from it. */
internal const val SYSENV_NAME = "[A-Za-z_][A-Za-z0-9_]*"

/** An allowlist entry outside [SYSENV_NAME] could never match a reference. */
private val SYSENV_NAME_PATTERN = Regex(SYSENV_NAME)

/** Bucket for LangGraph state of runs with no owner (unsecured mode, agentdebug). */
private const val UNOWNED_STATE_SEGMENT = "_unowned"

/** Marks a hashed segment, keeping hashes in their own namespace. */
private const val HASHED_STATE_SEGMENT_PREFIX = "_h-"

// A segment is used verbatim only when lowercase, <=64 chars, drawn from [a-z0-9._-], and not
// starting with `_` or `.` — each rule closes a collision; anything else is hashed. See ADR-0046.
private fun ownerStateSegment(owner: String?): String {
    if (owner.isNullOrEmpty()) return UNOWNED_STATE_SEGMENT
    val verbatim =
        owner.length <= 64 &&
            owner.all { it in 'a'..'z' || it in '0'..'9' || it in "._-" } &&
            !owner.startsWith("_") &&
            !owner.startsWith(".")
    if (verbatim) return owner
    val digest = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
    return HASHED_STATE_SEGMENT_PREFIX + HexFormat.of().formatHex(digest, 0, 16)
}

/** Soft caps on Claude spend; omitting a cap disables that window. Evaluated pre-flight against a rolling window. See ADR-0035. */
@Serializable
data class CostLimits(
    val globalUsdPerMin: Double? = null,
    val perAgentUsdPerDay: Map<String, Double> = emptyMap(),
) {
    init {
        require(globalUsdPerMin == null || globalUsdPerMin > 0.0) {
            "costLimits.globalUsdPerMin must be > 0 (or null to disable); got $globalUsdPerMin"
        }
        perAgentUsdPerDay.forEach { (agent, cap) ->
            require(cap > 0.0) {
                "costLimits.perAgentUsdPerDay['$agent'] must be > 0; got $cap"
            }
        }
    }
}

/** Container for the pricing sources. Only `sessionSpend` exists today; a per-model rate table is a deliberate later rung. */
@Serializable
data class LlmPriceConfig(
    val sessionSpend: SessionSpendConfig? = null,
)

/** Prices a Claude attempt from an external spend API; the shape is a URL template, headers and JSONPath. See ADR-0061. */
@Serializable
data class SessionSpendConfig(
    /** Must contain `{sessionId}` exactly once, in the path or query — not the host, port, userinfo or fragment. */
    val url: String,
    /** Sent verbatim on the GET. Values are `@SYSENV:`-resolvable. Never logged. */
    val headers: Map<String, String> = emptyMap(),
    /** Jayway JSONPath yielding one number or a list of numbers (summed). */
    val costPath: String,
    /** Optional JSONPath for the endpoint's ingested call count; when set, re-poll until it reaches the CLI's calls. See ADR-0061. */
    val callsPath: String? = null,
    // Optional JSONPath for per-request rows: with [startedPath]/[requestSpendPath] prices each attempt
    // from its own window, not the session-cumulative [costPath] figure. All three or none. See ADR-0061.
    val requestsPath: String? = null,
    /** Optional Jayway JSONPath into one [requestsPath] row yielding its start time, e.g. `$.started`. */
    val startedPath: String? = null,
    /** Optional Jayway JSONPath into one [requestsPath] row yielding its cost, e.g. `$.spend`. */
    val requestSpendPath: String? = null,
    /** Milliseconds added on each side of the attempt window when matching [requestsPath] rows (clock skew). */
    val windowGraceMs: Long = 5000,
    /** Whole-request timeout. */
    val timeoutMs: Long = 3000,
) {
    init {
        val placeholders = Regex(Regex.escape(SESSION_ID_PLACEHOLDER)).findAll(url).count()
        require(placeholders == 1) {
            "llmPrice.sessionSpend.url must contain $SESSION_ID_PLACEHOLDER exactly once; got $placeholders occurrence(s)"
        }
        val lower = url.lowercase()
        require(lower.startsWith("https://") || lower.startsWith("http://")) {
            "llmPrice.sessionSpend.url must use https:// (or http:// to a loopback host)"
        }
        if (lower.startsWith("http://")) {
            require(LOOPBACK_HTTP_URL.containsMatchIn(url)) {
                "llmPrice.sessionSpend.url uses plaintext http:// to a non-loopback host; the request carries the " +
                    "configured headers (typically a bearer token). Use https:// or a localhost endpoint."
            }
        }

        // [CWE-918] sessionId is read from agent-controlled stdout; if the placeholder lands in the
        // authority or fragment, a crafted session id picks the request host and exfiltrates the headers.
        fun parseUri(s: String): java.net.URI =
            try {
                java.net.URI(s)
            } catch (e: java.net.URISyntaxException) {
                throw IllegalArgumentException("llmPrice.sessionSpend.url is not a valid URL: ${e.reason}", e)
            }
        val probeX = parseUri(url.replace(SESSION_ID_PLACEHOLDER, "x"))
        val probeY = parseUri(url.replace(SESSION_ID_PLACEHOLDER, "y"))
        require(probeX.authority == probeY.authority) {
            "llmPrice.sessionSpend.url must place {sessionId} in the path or query, not in the host, port or userinfo"
        }
        require(probeX.fragment == probeY.fragment) {
            "llmPrice.sessionSpend.url must place {sessionId} in the path or query, not in the fragment"
        }
        require(!probeX.authority.isNullOrBlank()) { "llmPrice.sessionSpend.url is not a valid URL: no host" }
        require(timeoutMs in 1..MAX_TIMEOUT_MS) {
            "llmPrice.sessionSpend.timeoutMs must be in 1..$MAX_TIMEOUT_MS; got $timeoutMs"
        }
        try {
            com.jayway.jsonpath.JsonPath
                .compile(costPath)
        } catch (e: com.jayway.jsonpath.InvalidPathException) {
            throw IllegalArgumentException("llmPrice.sessionSpend.costPath is not a valid JSONPath: ${e.message}", e)
        }
        if (callsPath != null) {
            try {
                com.jayway.jsonpath.JsonPath
                    .compile(callsPath)
            } catch (e: com.jayway.jsonpath.InvalidPathException) {
                throw IllegalArgumentException("llmPrice.sessionSpend.callsPath is not a valid JSONPath: ${e.message}", e)
            }
        }
        // The window keys price an attempt from its own requests; all three or none.
        val windowKeys = listOf("requestsPath" to requestsPath, "startedPath" to startedPath, "requestSpendPath" to requestSpendPath)
        val setWindowKeys = windowKeys.filter { it.second != null }
        require(setWindowKeys.isEmpty() || setWindowKeys.size == windowKeys.size) {
            "llmPrice.sessionSpend.requestsPath, startedPath and requestSpendPath must all be set together or all left null; got " +
                setWindowKeys.joinToString { it.first }
        }
        windowKeys.forEach { (name, path) ->
            if (path != null) {
                try {
                    com.jayway.jsonpath.JsonPath
                        .compile(path)
                } catch (e: com.jayway.jsonpath.InvalidPathException) {
                    throw IllegalArgumentException("llmPrice.sessionSpend.$name is not a valid JSONPath: ${e.message}", e)
                }
            }
        }
        require(windowGraceMs >= 0) {
            "llmPrice.sessionSpend.windowGraceMs must be >= 0; got $windowGraceMs"
        }
    }

    fun withResolvedHeaders(resolve: (String) -> String): SessionSpendConfig = copy(headers = headers.mapValues { (_, v) -> resolve(v) })

    // Header values are credentials; keep them out of any accidental config dump.
    override fun toString(): String =
        "SessionSpendConfig(url=$url, headers=${headers.keys.associateWith { "***" }}, costPath=$costPath, " +
            "callsPath=$callsPath, requestsPath=$requestsPath, startedPath=$startedPath, " +
            "requestSpendPath=$requestSpendPath, windowGraceMs=$windowGraceMs, timeoutMs=$timeoutMs)"

    companion object {
        const val SESSION_ID_PLACEHOLDER = "{sessionId}"
        const val MAX_TIMEOUT_MS = 30_000L
    }
}

/** The only hosts a secret-carrying URL may reach over plain `http://`, shared so the rules cannot drift. */
val LOOPBACK_HTTP_URL = Regex("^http://(localhost|127\\.0\\.0\\.1|\\[::1])(?::\\d+)?(/|$)", RegexOption.IGNORE_CASE)

// `https://` with a host, or plain `http://` matching [LOOPBACK_HTTP_URL], and no userinfo — refused,
// not stripped, because every caller sends a credential (CWE-522). Gates on authority, not `URI.host`.
fun isHttpsOrLoopbackHttpUrl(url: String): Boolean {
    val uri = runCatching { java.net.URI(url) }.getOrNull()
    val authority = uri?.authority
    if (authority != null && authority.contains('@')) return false
    return (uri?.scheme.equals("https", ignoreCase = true) && !authority.isNullOrBlank()) ||
        LOOPBACK_HTTP_URL.containsMatchIn(url)
}

@Serializable
data class LangfuseConfig(
    val serverUrl: String = "",
    val publicKey: String = "",
    val secretKey: String = "",
    /** Langfuse project id, used only to build a run's trace deep link; Langfuse derives it from the key pair. */
    val projectId: String = "",
) {
    // Redacted so a stray `logger.info("config=$config")` or a serialization-error
    // message can't leak the secret into app.log.
    override fun toString() = "LangfuseConfig(serverUrl=$serverUrl, publicKey=$publicKey, secretKey=${redactSecret(secretKey)})"
}

/** Masks a secret for `toString()`: `***` when set, empty when blank (so it doesn't imply a value). */
internal fun redactSecret(value: String?): String = if (value.isNullOrEmpty()) "" else "***"

enum class OpenTelemetryExport {
    LANGFUSE,
    LOG,
}
