package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.infra.SysEnvResolver
import homer.app.infra.matchesTokenConstantTime
import homer.app.models.Action
import homer.app.models.Agent
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.ParamSource
import homer.app.models.RunnerConfig
import homer.app.models.flattenAuthSchemes
import homer.app.models.isHttpsOrLoopbackHttpUrl
import homer.app.models.stripUrlSecrets
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("homer.app.Application")

/**
 * Resolves `@SYSENV:VAR` placeholders in every secret-bearing field of an [AuthConfig] tree, recursing into
 * [AuthConfig.Combined]. Every variant carrying operator secrets must be covered: an unresolved placeholder
 * reaches bcrypt/HMAC/bearer comparison as a literal and looks like invalid credentials.
 */
internal fun resolveAuth(
    auth: AuthConfig,
    sysEnvResolver: SysEnvResolver,
): AuthConfig =
    when (auth) {
        is AuthConfig.Bearer -> {
            auth.copy(
                tokens =
                    auth.tokens.mapValues { (_, cfg) ->
                        cfg.copy(token = sysEnvResolver.resolve(cfg.token))
                    },
            )
        }

        is AuthConfig.Hmac -> {
            auth.copy(
                keys =
                    auth.keys.mapValues { (_, cfg) ->
                        cfg.copy(secret = sysEnvResolver.resolve(cfg.secret))
                    },
            )
        }

        is AuthConfig.Password -> {
            auth.copy(
                users =
                    auth.users.mapValues { (_, cfg) ->
                        cfg.copy(passwordHash = sysEnvResolver.resolve(cfg.passwordHash))
                    },
                sessionSecret = sysEnvResolver.resolve(auth.sessionSecret),
                sessionSecretPrevious = auth.sessionSecretPrevious?.let(sysEnvResolver::resolve),
            )
        }

        is AuthConfig.Combined -> {
            auth.copy(schemes = auth.schemes.map { resolveAuth(it, sysEnvResolver) })
        }

        is AuthConfig.Unsecured -> {
            auth
        }
    }

/**
 * Resolves `@SYSENV:VAR` placeholders in every remote runner's `env` map, as `globalEnv` gets: an unresolvable
 * placeholder refuses startup instead of shipping a literal `@SYSENV:...` to the remote host.
 */
internal fun resolveRunnerEnvs(
    runners: Map<String, RunnerConfig>,
    sysEnvResolver: SysEnvResolver,
): Map<String, RunnerConfig> =
    runners.mapValues { (_, runner) ->
        when (runner) {
            is RunnerConfig.Ssh -> {
                runner.copy(env = sysEnvResolver.resolveMapValues(runner.env))
            }

            is RunnerConfig.Kubernetes -> {
                runner.copy(env = sysEnvResolver.resolveMapValues(runner.env))
            }

            is RunnerConfig.External -> {
                runner.copy(
                    token = sysEnvResolver.resolve(runner.token),
                    env = sysEnvResolver.resolveMapValues(runner.env),
                )
            }
        }
    }

/**
 * Resolves `@SYSENV:VAR` in `server.corsAllowedOrigins` and refuses an entry that is not a bare
 * `http(s)://host[:port]` origin: a variable can complete an origin, never add a path, credentials or a host.
 * Not a secret, so no name set is recorded; the error names the raw entry, never the resolved value.
 */
internal fun resolveCorsOrigins(
    origins: List<String>,
    sysEnvResolver: SysEnvResolver,
): List<String> =
    origins.map { raw ->
        sysEnvResolver.resolve(raw).also { resolved ->
            check(isCorsOrigin(resolved)) {
                val via = if (resolved == raw) "" else " after @SYSENV resolution"
                "server.corsAllowedOrigins entry '$raw' is not an origin$via: expected http(s)://host[:port]"
            }
        }
    }

private const val MAX_PORT = 65535
private val ORIGIN_SCHEMES = setOf("http", "https")

/** No port, or one a browser origin can carry: port 0 would be read as no port and allow the scheme default. */
private fun hasOriginPort(uri: URI): Boolean = uri.port == -1 || uri.port in 1..MAX_PORT

/** True when [value] is exactly `scheme://host[:port]` (an optional trailing `/` allowed), scheme http or https. */
internal fun isCorsOrigin(value: String): Boolean {
    val uri =
        try {
            URI(value)
        } catch (_: URISyntaxException) {
            return false
        }
    val scheme = uri.scheme?.lowercase()
    if (scheme !in ORIGIN_SCHEMES || uri.host == null || !hasOriginPort(uri)) return false
    val port = if (uri.port == -1) "" else ":${uri.port}"
    // Rebuilding the origin from its parts rejects userinfo, a path, a query and a fragment.
    return value.removeSuffix("/").equals("$scheme://${uri.host}$port", ignoreCase = true)
}

/**
 * Fail-fast on the removed `endpoints` path allowlist (ADR-0037). Each principal
 * must declare `role: admin` or a non-empty `grants` list. The message names the
 * mechanical migration so an operator can convert in place.
 */
internal fun validateNoLegacyEndpoints(config: AppConfig) {
    fun check(
        kind: String,
        name: String,
        p: homer.app.models.Principaled,
    ) {
        if (p.endpoints != null) {
            throw ConfigurationException(
                "$kind '$name' uses the removed 'endpoints' allowlist. Migrate to grants: " +
                    "[\"*\"] -> `role: admin`; `/api/v1/agent/X` -> `grants: [{agents: [X], actions: [run]}]`. See ADR-0037.",
            )
        }
        if (p.role == null && p.grants.isEmpty()) {
            throw ConfigurationException(
                "$kind '$name' has neither `role` nor `grants`; it could authenticate but do nothing. " +
                    "Add `role: admin` or a `grants` list. See ADR-0037.",
            )
        }
    }
    flattenAuthSchemes(config.server.apiAuth).forEach { s ->
        when (s) {
            is AuthConfig.Bearer -> {
                s.tokens.forEach { (n, c) -> check("bearer token", n, c) }
            }

            is AuthConfig.Hmac -> {
                s.keys.forEach { (n, c) -> check("hmac key", n, c) }
            }

            is AuthConfig.Password -> {
                s.users.forEach { (n, c) -> check("password user", n, c) }
            }

            else -> {}
        }
    }
}

/**
 * Fail-fast on an extraUploads entry whose local path is missing or unreadable, so a misdeclared dependency stops
 * boot instead of failing runs later. Shape rules are enforced at parse time in the models. See ADR-0042.
 */
internal fun validateExtraUploads(config: AppConfig) {
    config.runners.forEach { (alias, runner) ->
        runner.extraUploads.forEach { spec ->
            if (!Files.isReadable(Path.of(spec.local))) {
                throw ConfigurationException(
                    "runner '$alias' extraUploads.local '${spec.local}' does not exist or is not readable.",
                )
            }
        }
    }
}

/**
 * Fail-fast when the config omits `server.apiAuth`: a typo must not silently expose every endpoint without auth;
 * running unsecured stays an explicit opt-in. See ADR-0036.
 */
internal fun validateExplicitApiAuth(config: AppConfig) {
    if (config.server.apiAuth == null) {
        throw ConfigurationException(
            "server.apiAuth is not set. Configure an auth scheme (bearer/hmac/password/combined), " +
                "or explicitly opt in to an unsecured API with `apiAuth: !<unsecured>` (YAML) / " +
                "`\"apiAuth\": {\"type\": \"unsecured\"}` (JSON). See SERVER.md and ADR-0036.",
        )
    }
}

/**
 * Refuses a runner-domain token equal to any secret an `apiAuth` scheme accepts as proof (HMAC key secret,
 * session signing secret; not a password hash): the two are distinct principal classes (ADR-0066). The error
 * names the alias, never the value.
 */
internal fun validateRunnerDomainTokens(config: AppConfig) {
    val apiAuthSecrets =
        flattenAuthSchemes(config.server.apiAuth).flatMap { scheme ->
            when (scheme) {
                is AuthConfig.Bearer -> scheme.tokens.values.map { it.token }
                is AuthConfig.Hmac -> scheme.keys.values.map { it.secret }
                is AuthConfig.Password -> listOfNotNull(scheme.sessionSecret, scheme.sessionSecretPrevious)
                is AuthConfig.Combined, is AuthConfig.Unsecured -> emptyList()
            }
        }
    config.runners.forEach { (alias, cfg) ->
        if (cfg is RunnerConfig.External && apiAuthSecrets.any { matchesTokenConstantTime(it, cfg.token) }) {
            error("Configuration error: runner '$alias' token equals an apiAuth credential; use a distinct value")
        }
    }
}

/**
 * Fail-fast when a cookie/session ([AuthConfig.Password]) scheme has no pinned CORS origins: cookie auth needs
 * credentialed CORS, which [configureCors] enables only for pinned origins. See ADR-0016.
 */
internal fun validateCorsForCookieAuth(config: AppConfig) {
    val hasPasswordAuth = flattenAuthSchemes(config.server.apiAuth).any { it is AuthConfig.Password }
    if (hasPasswordAuth && config.server.corsAllowedOrigins.isEmpty()) {
        throw ConfigurationException(
            "AuthConfig.Password is configured but server.corsAllowedOrigins is empty. " +
                "Credentialed CORS is only enabled for explicitly pinned origins, so set " +
                "server.corsAllowedOrigins to the console origin(s) (e.g. \"https://console.example.com\"). " +
                "See ADR-0016.",
        )
    }
}

/**
 * Fail-fast on an HMAC key whose signature does not bind the request path while its grants (ADR-0037) reach more
 * than one agent (`role: admin`, a `*` pattern, or two or more exact agents): a captured request replays against
 * every other agent it reaches. Only that config is rejected; the scheme is unchanged.
 */
internal fun validateHmacSignatureScope(config: AppConfig) {
    flattenAuthSchemes(config.server.apiAuth)
        .filterIsInstance<AuthConfig.Hmac>()
        .forEach { hmac ->
            hmac.keys.forEach { (keyId, cfg) ->
                val bindsPath = "{{path}}" in cfg.payloadTemplate
                val agentPatterns = cfg.grants.flatMap { it.agents }.toSet()
                val reachesMultipleAgents =
                    cfg.role == "admin" ||
                        agentPatterns.any { "*" in it } ||
                        agentPatterns.size > 1
                if (!bindsPath && reachesMultipleAgents) {
                    throw ConfigurationException(
                        "HMAC key '$keyId' signs a payload that does not bind {{path}} while its grants reach " +
                            "multiple agents; a captured signature is then replayable across them. Bind the path " +
                            "in payloadTemplate (e.g. \"{{method}}.{{path}}.{{body}}\") or narrow the key's grants.",
                    )
                }
            }
        }
}

/**
 * Fail-fast on a session signing secret (or `sessionSecretPrevious`) shorter than [MIN_SESSION_SECRET_LEN]: it is
 * the raw HMAC-SHA256 cookie key, so a short one is brute-forceable. Checked on the resolved config, so a short
 * `@SYSENV:VAR` placeholder naming a strong secret is fine.
 */
internal fun validateSessionSecretStrength(config: AppConfig) {
    flattenAuthSchemes(config.server.apiAuth)
        .filterIsInstance<AuthConfig.Password>()
        .forEach { pw ->
            listOfNotNull(
                "sessionSecret" to pw.sessionSecret,
                pw.sessionSecretPrevious?.let { "sessionSecretPrevious" to it },
            ).forEach { (field, value) ->
                if (value.length < MIN_SESSION_SECRET_LEN) {
                    throw ConfigurationException(
                        "AuthConfig.Password.$field must be at least $MIN_SESSION_SECRET_LEN characters " +
                            "(it is the HMAC key for the session cookie); generate one with e.g. `openssl rand -hex 32`.",
                    )
                }
            }
        }
}

private const val MIN_SESSION_SECRET_LEN = 32

/**
 * Fail-fast on a runner-domain token shorter than [MIN_RUNNER_TOKEN_LEN], on the resolved config like
 * [validateSessionSecretStrength]: a match drives commands on the instances, so it must not be guessable.
 * The message names the alias, never the value. CWE-331.
 */
internal fun validateRunnerDomainTokenStrength(config: AppConfig) {
    config.runners.forEach { (alias, cfg) ->
        if (cfg is RunnerConfig.External && cfg.token.length < MIN_RUNNER_TOKEN_LEN) {
            throw ConfigurationException(
                "runner '$alias' token must be at least $MIN_RUNNER_TOKEN_LEN characters; " +
                    "generate one with e.g. `openssl rand -hex 32`.",
            )
        }
    }
}

private const val MIN_RUNNER_TOKEN_LEN = 32

/**
 * Fail-fast on a set `langfuse.serverUrl` that is not `https://` or loopback `http://`, whatever the export mode:
 * prompt fetches and the OTLP export both send the Langfuse keys there. The message never echoes userinfo or query.
 * Checked at boot, not in `LangfuseConfig.init`: only the server sends the keys, and its deep link must tolerate any value.
 */
internal fun validateLangfuseServerUrl(config: AppConfig) {
    val url = config.langfuse.serverUrl
    if (url.isBlank()) return
    // Judged after userinfo is dropped, which ADR-0050 permits here; unparseable fails closed. CWE-319.
    val sanitized = stripUrlSecrets(url)
    if (sanitized == null || !isHttpsOrLoopbackHttpUrl(sanitized)) {
        throw ConfigurationException(
            "langfuse.serverUrl (${sanitized ?: "<unparseable URL>"}) must be an https:// URL, or http:// to " +
                "localhost, 127.0.0.1 or [::1]: prompt fetches and the OTLP export send the Langfuse public and " +
                "secret keys to it.",
        )
    }
}

/**
 * Startup WARN for each HMAC key without a `timestampHeader`: with no freshness
 * window a captured signature replays indefinitely. Non-fatal — some webhook
 * senders (e.g. Vikunja) sign no timestamp — but the operator should know. See ADR-0010.
 */
internal fun warnOnHmacReplayWindow(
    config: AppConfig,
    log: org.slf4j.Logger = logger,
) {
    flattenAuthSchemes(config.server.apiAuth)
        .filterIsInstance<AuthConfig.Hmac>()
        .forEach { hmac ->
            hmac.keys.forEach { (keyId, cfg) ->
                if (cfg.timestampHeader == null) {
                    log.warn(
                        "HMAC key '{}' has no timestampHeader, so a captured signature replays indefinitely. " +
                            "Set timestampHeader and add {{timestamp}} to payloadTemplate for replay resistance.",
                        keyId,
                    )
                }
            }
        }
}

/**
 * Startup WARN for each HMAC key that can run an agent reading inputs from the query string while its
 * `payloadTemplate` omits `{{query}}`: a captured signature then replays with altered query parameters.
 * Non-fatal, since signing scope is per-key config (ADR-0010).
 */
internal fun warnOnUnsignedQueryInputs(
    config: AppConfig,
    agents: Collection<Agent>,
    log: org.slf4j.Logger = logger,
) {
    val queryAgents =
        agents.filter { a -> a.effectiveInputs.orEmpty().any { it.src == ParamSource.QUERY } }.map { it.name }
    if (queryAgents.isEmpty()) return
    flattenAuthSchemes(config.server.apiAuth)
        .filterIsInstance<AuthConfig.Hmac>()
        .forEach { hmac ->
            hmac.keys.forEach { (keyId, cfg) ->
                if ("{{query}}" in cfg.payloadTemplate) return@forEach
                val reachable =
                    queryAgents.filter { name ->
                        cfg.role == "admin" || cfg.grants.any { it.matches(name, Action.RUN) }
                    }
                if (reachable.isNotEmpty()) {
                    log.warn(
                        "HMAC key '{}' can run agent(s) that read inputs from the query string ({}), but its " +
                            "payloadTemplate does not bind {{query}}; a captured signature replays with altered " +
                            "query params. Add {{query}} to payloadTemplate " +
                            "(e.g. \"{{method}}.{{path}}.{{query}}.{{body}}\").",
                        keyId,
                        reachable.joinToString(", "),
                    )
                }
            }
        }
}
