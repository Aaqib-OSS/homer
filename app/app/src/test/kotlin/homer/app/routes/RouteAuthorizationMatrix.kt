package homer.app.routes

/**
 * The credential domain a route authenticates in. Operator: `apiAuth` (bearer, HMAC, session
 * cookie); callback: a run's `HMR_CALLBACK_TOKEN` (ADR-0019); runner: a `runners.*.token` domain
 * token (ADR-0066); public: no credential is read.
 */
internal enum class CredentialDomain { OPERATOR, CALLBACK, RUNNER, PUBLIC }

/** The agent the no-grant principal holds no grant on; every seeded resource belongs to it. */
internal const val HIDDEN_AGENT = "secret-agent"

/**
 * One registered route and the status each denial case must answer (ADR-0112): [noGrant] is a live credential of
 * the route's domain owning no seeded resource, [wrongDomain] any other domain's credential, [none] no credential.
 * A no-grant `404` must match a nonexistent resource byte for byte; [sample] and [body] fill `{name}` placeholders.
 */
internal data class RouteAuthz(
    val route: String,
    val domain: CredentialDomain,
    val rule: String,
    val noGrant: Int,
    val wrongDomain: Int,
    val none: Int,
    val sample: String? = null,
    val body: String? = null,
    val listFiltered: Boolean = false,
)

private val OPERATOR = CredentialDomain.OPERATOR
private val CALLBACK = CredentialDomain.CALLBACK
private val RUNNER = CredentialDomain.RUNNER
private val PUBLIC = CredentialDomain.PUBLIC

internal const val MCP_INITIALIZE =
    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
        """"capabilities":{},"clientInfo":{"name":"authz-matrix","version":"1.0"}}}"""

private fun operator(
    route: String,
    rule: String,
    noGrant: Int,
    sample: String? = null,
    body: String? = null,
    listFiltered: Boolean = false,
) = RouteAuthz(route, OPERATOR, rule, noGrant, 401, 401, sample, body, listFiltered)

private fun callback(
    route: String,
    rule: String,
    noGrant: Int,
    sample: String? = null,
    body: String? = null,
) = RouteAuthz(route, CALLBACK, rule, noGrant, 401, 401, sample, body)

private fun runner(
    route: String,
    rule: String,
    noGrant: Int,
    sample: String? = null,
    body: String? = null,
) = RouteAuthz(route, RUNNER, rule, noGrant, 401, 401, sample, body)

private fun public(
    route: String,
    rule: String,
    status: Int,
    body: String? = null,
) = RouteAuthz(route, PUBLIC, rule, status, status, status, body = body)

private const val VISIBLE = "visible: admin, owner or READ"
private const val CONTROL = "control: admin, owner or visible + CONTROL"
private const val ANY_AUTHENTICATED = "any authenticated"
private const val EDIT_FILES = "EDIT on agent; 404 without any grant"
private const val CALLBACK_TOKEN = "callback token (own run)"
private const val HOP = "callback token; dispatch hop + dispatch.enabled; 404 like an unknown agent"
private const val OWN_INSTANCE = "runner domain token; instance of own domain"
private const val OWN_SESSION = "runner domain token; session of own domain (none seeded: 404 either way)"

/**
 * Every route `configureRouting` mounts with every optional surface on, in registration order. A new
 * route fails [RouteAuthorizationMatrixTest] until it has a row here (app/CLAUDE.md, "Adding a new HTTP endpoint").
 */
internal val ROUTE_AUTHZ: List<RouteAuthz> =
    listOf(
        public("GET /api/v1/livez", "none: liveness probe", 200),
        public("GET /api/v1/readyz", "none: readiness probe", 200),
        public("POST /api/v1/auth/login", "username + password", 401, body = """{"username":"nobody","password":"wrong"}"""),
        public("POST /api/v1/auth/logout", "none: clears the session cookie", 204),
        operator("GET /api/v1/auth/me", "bearer token or session cookie; answers the caller's own role and grants", 200),
        callback("POST /api/v1/callback/questions", CALLBACK_TOKEN, 200, body = """{"text":"own?"}"""),
        callback(
            "GET /api/v1/callback/questions/{id}",
            "callback token; question of own run",
            404,
            "/api/v1/callback/questions/{questionId}",
        ),
        callback("POST /api/v1/callback/tags", "callback token; key declared by own agent", 400, body = """{"tag":"team:x"}"""),
        callback("POST /api/v1/callback/dispatches", HOP, 404, body = """{"text":"go?","agent":"{agentName}"}"""),
        callback("POST /api/v1/callback/dispatch-groups", HOP, 404, body = """{"questions":[{"text":"go?"}],"agent":"{agentName}"}"""),
        callback("POST /api/v1/callback/runs", HOP, 404, body = """{"agent":"{agentName}"}"""),
        callback(
            "POST /api/v1/callback/joins",
            HOP,
            404,
            body = """{"subs":[{"agent":"{agentName}","interruptId":"i1"}],"continueAgent":"{agentName}"}""",
        ),
        callback(
            "GET /api/v1/callback/results/{token}",
            "callback token + result token minted for the caller's agent (403 otherwise, ADR-0025)",
            403,
            "/api/v1/callback/results/{resultToken}",
        ),
        callback("POST /api/v1/callback/messages/take", CALLBACK_TOKEN, 200),
        callback("* /api/v1/callback/whoami", "callback token of a live run; any method", 200),
        runner("POST /api/v1/runner/register", "runner domain token; registers in own domain", 201, body = """{"name":"probe"}"""),
        runner("DELETE /api/v1/runner/instances/{id}", OWN_INSTANCE, 404, "/api/v1/runner/instances/{instanceId}"),
        runner("POST /api/v1/runner/instances/{id}/claim", OWN_INSTANCE, 404, "/api/v1/runner/instances/{instanceId}/claim"),
        runner("POST /api/v1/runner/sessions/{sid}/next", OWN_SESSION, 404),
        runner("GET /api/v1/runner/sessions/{sid}/ops/{opId}/tar", OWN_SESSION, 404),
        runner("POST /api/v1/runner/sessions/{sid}/ops/{opId}/tar", OWN_SESSION, 404),
        runner(
            "POST /api/v1/runner/sessions/{sid}/ops/{opId}/output",
            OWN_SESSION,
            404,
            "/api/v1/runner/sessions/{sid}/ops/{opId}/output?stream=stdout&seq=0",
        ),
        RouteAuthz("POST /mcp/questions", CALLBACK, CALLBACK_TOKEN, 200, 401, 401, body = MCP_INITIALIZE),
        public("GET /mcp/questions", "none: always 405, the endpoint is POST-only", 405),
        public("DELETE /mcp/questions", "none: always 405, the endpoint is POST-only", 405),
        operator("GET /api/v1/heartbeat", ANY_AUTHENTICATED, 200),
        operator("GET /api/v1/health", "any authenticated; runner aliases admin-only", 200),
        operator("GET /api/v1/costs", "admin", 403),
        operator("GET /api/v1/costs/monthly", "admin", 403),
        operator("GET /api/v1/costs/totals", "admin", 403),
        operator("GET /api/v1/costs/by-tag/{key}", "admin", 403),
        operator("GET /api/v1/status", "admin", 403),
        operator("GET /api/v1/status/all", "rows filtered to visible runs", 200, listFiltered = true),
        operator("GET /api/v1/status/{processId}", VISIBLE, 404),
        operator("POST /api/v1/status/{processId}/messages", CONTROL, 404, body = """{"text":"hi"}"""),
        operator("GET /api/v1/status/{processId}/messages", VISIBLE, 404),
        operator("GET /api/v1/runners", "admin or READ on *", 403),
        operator("GET /api/v1/runners/queue", "admin or READ on *", 403),
        operator("GET /api/v1/maintenance", "admin or READ on *; 404 like an unmounted path", 404),
        operator("POST /api/v1/maintenance", "admin; 404 like an unmounted path", 404, body = """{"enabled":true}"""),
        operator("GET /api/v1/agents", "rows filtered to agents with any grant", 200, listFiltered = true),
        operator("POST /api/v1/agents/reload", "admin", 403),
        operator("GET /api/v1/agents/load-errors", "admin; 404 like an unmounted path", 404),
        operator("POST /api/v1/agents", "EDIT on the body's name", 403, body = """{"name":"{agentName}"}"""),
        operator("GET /api/v1/agents/{agentName}/files", EDIT_FILES, 404),
        operator("GET /api/v1/agents/{agentName}/files/{...}", EDIT_FILES, 404),
        operator("PUT /api/v1/agents/{agentName}/files/{...}", EDIT_FILES, 404, body = """{"content":"x"}"""),
        operator("DELETE /api/v1/agents/{agentName}/files/{...}", EDIT_FILES, 404),
        operator("GET /api/v1/agents/{agentName}/langgraph", "any grant on agent", 404),
        operator("POST /api/v1/agents/{agentName}/debug", "DEBUG on agent; 404 without any grant", 404),
        operator("POST /api/v1/agents/{agentName}/cron-run", "admin; 404 like an unknown agent", 404),
        operator("DELETE /api/v1/kill/{processId}", CONTROL, 404),
        operator("GET /api/v1/artifacts", VISIBLE, 404, "/api/v1/artifacts?processId={processId}&path=out.txt"),
        operator("GET /api/v1/artifacts/list", VISIBLE, 404, "/api/v1/artifacts/list?processId={processId}"),
        operator("GET /api/v1/schemas/agent", ANY_AUTHENTICATED, 200),
        operator("GET /api/v1/questions", "rows filtered to visible asking runs", 200, listFiltered = true),
        operator("POST /api/v1/questions/{id}/answer", CONTROL, 404, "/api/v1/questions/{questionId}/answer", """{"answer":"yes"}"""),
        operator("GET /api/v1/dispatches", "rows filtered to visible dispatches", 200, listFiltered = true),
        operator("GET /api/v1/dispatches/{id}", VISIBLE, 404, "/api/v1/dispatches/{dispatchId}"),
        operator("DELETE /api/v1/dispatches/{id}", CONTROL, 404, "/api/v1/dispatches/{dispatchId}"),
        operator("GET /api/v1/continuations", "rows filtered to visible continuations", 200, listFiltered = true),
        operator("GET /api/v1/continuations/{id}", VISIBLE, 404, "/api/v1/continuations/{continuationId}"),
        operator("DELETE /api/v1/continuations/{id}", CONTROL, 404, "/api/v1/continuations/{continuationId}"),
        operator("GET /api/v1/stream/processes", "events filtered to visible runs", 200),
        operator("GET /api/v1/stream/processes/{id}/logs", VISIBLE, 404, "/api/v1/stream/processes/{processId}/logs"),
        operator("GET /api/v1/stream/processes/{id}/llm-events", VISIBLE, 404, "/api/v1/stream/processes/{processId}/llm-events"),
        operator("GET /api/v1/stream/agents", "events filtered to agents with any grant", 200),
        operator("GET /api/v1/agent/{agentName}", "RUN on agent; 404 without any grant", 404),
        operator("POST /api/v1/agent/{agentName}", "RUN on agent; 404 without any grant", 404),
        operator("POST /api/v1/processes/{id}/retry", "visible + RUN", 404, "/api/v1/processes/{processId}/retry"),
        operator("GET /api/v1/processes/{id}/langgraph", VISIBLE, 404, "/api/v1/processes/{processId}/langgraph"),
        operator("POST /api/v1/processes/{id}/resume", "visible + RUN", 404, "/api/v1/processes/{processId}/resume"),
        operator("POST /mcp", "any authenticated; tools filtered per principal", 200, body = MCP_INITIALIZE),
        operator("GET /mcp", "any authenticated; always 405", 405),
        operator("DELETE /mcp", "any authenticated; always 405", 405),
    )

/** How a no-grant caller is refused one MCP tool. */
internal enum class McpDenial {
    /** Not in the caller's `tools/list`, and a `tools/call` of it fails. */
    UNLISTED,

    /** Listed, but a call naming the hidden resource is refused exactly like one naming an unknown resource. */
    NOT_FOUND,

    /** Takes no input naming another run's resource: it acts on the caller's own run only. */
    OWN_RUN_ONLY,
}

/** One MCP tool: [arguments] (seed placeholders filled) are what the no-grant probe sends. */
internal data class McpToolAuthz(
    val tool: String,
    val rule: String,
    val denial: McpDenial,
    val arguments: String = "{}",
)

/** The `/mcp` tool set as the admin sees it: the opted-in hidden agent plus the run tools. */
internal val OPERATOR_MCP_TOOLS: List<McpToolAuthz> =
    listOf(
        McpToolAuthz(HIDDEN_AGENT, "RUN on agent", McpDenial.UNLISTED),
        McpToolAuthz("get_run_info", VISIBLE, McpDenial.NOT_FOUND, """{"runId":{processId}}"""),
        McpToolAuthz("list_artifacts", VISIBLE, McpDenial.NOT_FOUND, """{"runId":{processId}}"""),
        McpToolAuthz("get_artifact", VISIBLE, McpDenial.NOT_FOUND, """{"runId":{processId},"path":"out.txt"}"""),
        McpToolAuthz("cancel_run", CONTROL, McpDenial.NOT_FOUND, """{"runId":{processId}}"""),
    )

/** The `/mcp/questions` tool set; the no-grant probe uses the callback token of the no-grant principal's run. */
internal val QUESTIONS_MCP_TOOLS: List<McpToolAuthz> =
    listOf(
        McpToolAuthz("ask_user", CALLBACK_TOKEN, McpDenial.OWN_RUN_ONLY),
        McpToolAuthz("check_answer", "question of own run", McpDenial.NOT_FOUND, """{"questionId":"{questionId}"}"""),
        McpToolAuthz("ask_user_and_wait", CALLBACK_TOKEN, McpDenial.OWN_RUN_ONLY),
        McpToolAuthz(
            "ask_and_dispatch",
            "dispatch hop + dispatch.enabled",
            McpDenial.NOT_FOUND,
            """{"text":"go?","agent":"{agentName}"}""",
        ),
        McpToolAuthz(
            "ask_and_dispatch_group",
            "dispatch hop + dispatch.enabled",
            McpDenial.NOT_FOUND,
            """{"questions":[{"text":"go?"}],"agent":"{agentName}"}""",
        ),
        McpToolAuthz("check_messages", CALLBACK_TOKEN, McpDenial.OWN_RUN_ONLY),
    )

/**
 * A denial case that does not answer as its row expects: [description] in one line, and [issue], the
 * open GitHub issue (`#NNN`) tracking the fix. A gap merges only together with that issue (ADR-0112).
 */
internal data class KnownGap(
    val description: String,
    val issue: String,
) {
    init {
        require(ISSUE_REFERENCE.matches(issue)) { "KnownGap.issue must be a GitHub issue reference like #123, got '$issue'" }
    }
}

internal val ISSUE_REFERENCE = Regex("""^#\d+$""")

/**
 * The gaps, keyed `"<route> [<case>]"` (an MCP tool: `"mcp:<tool> [no grant]"` or
 * `"mcp/questions:<tool> [no grant]"`). Exact both ways: a listed gap that no longer reproduces fails
 * the test. Empty: every route and tool denies as designed.
 */
internal val KNOWN_GAPS: Map<String, KnownGap> = emptyMap()
