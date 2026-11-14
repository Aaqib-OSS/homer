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
 * One registered route and the status each denial case must answer (root CLAUDE.md §12 rules 3
 * and 8). The cases, driven by [RouteAuthorizationMatrixTest]:
 * - [noGrant]: the bearer token of an operator principal holding no grant on the seeded agent;
 * - [wrongDomain]: every credential of another domain (callback, runner or operator token);
 * - [none]: no credential at all.
 *
 * A no-grant `404` aimed at a seeded resource must also match, byte for byte, the answer for a
 * resource that does not exist (existence hidden, §12 rule 3).
 *
 * [sample] is the request target when the route path alone does not reach the check: a `{name}`
 * placeholder is filled from the seeded data (`{processId}` is a run of the hidden agent owned by
 * the admin). [listFiltered] marks a list read whose `200` body must not name the hidden agent.
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
    sample: String? = null,
) = RouteAuthz(route, CALLBACK, rule, 401, 401, 401, sample)

private fun runner(
    route: String,
    sample: String? = null,
) = RouteAuthz(route, RUNNER, "runner domain token", 401, 401, 401, sample)

private fun public(
    route: String,
    rule: String,
    status: Int,
    body: String? = null,
) = RouteAuthz(route, PUBLIC, rule, status, status, status, body = body)

private const val VISIBLE = "visible: admin, owner or READ"
private const val CONTROL = "control: admin, owner or visible + CONTROL"
private const val ANY_AUTHENTICATED = "any authenticated"
private const val CALLBACK_TOKEN = "callback token (own run)"

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
        operator("GET /api/v1/auth/me", "session cookie only; a bearer token is not read", 401),
        callback("POST /api/v1/callback/questions", CALLBACK_TOKEN),
        callback("GET /api/v1/callback/questions/{id}", "callback token; question of own run", "/api/v1/callback/questions/{questionId}"),
        callback("POST /api/v1/callback/tags", CALLBACK_TOKEN),
        callback("POST /api/v1/callback/dispatches", "callback token; dispatch hop + dispatch.enabled"),
        callback("POST /api/v1/callback/dispatch-groups", "callback token; dispatch hop + dispatch.enabled"),
        callback("POST /api/v1/callback/runs", "callback token; dispatch hop + dispatch.enabled"),
        callback("POST /api/v1/callback/joins", "callback token; dispatch hop + dispatch.enabled"),
        callback("GET /api/v1/callback/results/{token}", "callback token + result token for the caller's agent"),
        callback("POST /api/v1/callback/messages/take", CALLBACK_TOKEN),
        callback("* /api/v1/callback/whoami", "callback token of a live run; any method"),
        runner("POST /api/v1/runner/register"),
        runner("DELETE /api/v1/runner/instances/{id}", "/api/v1/runner/instances/x"),
        runner("POST /api/v1/runner/instances/{id}/claim", "/api/v1/runner/instances/x/claim"),
        runner("POST /api/v1/runner/sessions/{sid}/next"),
        runner("GET /api/v1/runner/sessions/{sid}/ops/{opId}/tar"),
        runner("POST /api/v1/runner/sessions/{sid}/ops/{opId}/tar"),
        runner("POST /api/v1/runner/sessions/{sid}/ops/{opId}/output", "/api/v1/runner/sessions/x/ops/x/output?stream=stdout&seq=0"),
        RouteAuthz("POST /mcp/questions", CALLBACK, CALLBACK_TOKEN, 401, 401, 401, body = MCP_INITIALIZE),
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
        operator("POST /api/v1/agents", "EDIT on the body's name", 403, body = """{"name":"$HIDDEN_AGENT"}"""),
        operator("GET /api/v1/agents/{agentName}/files", "EDIT on agent", 403),
        operator("GET /api/v1/agents/{agentName}/files/{...}", "EDIT on agent", 403),
        operator("PUT /api/v1/agents/{agentName}/files/{...}", "EDIT on agent", 403, body = """{"content":"x"}"""),
        operator("DELETE /api/v1/agents/{agentName}/files/{...}", "EDIT on agent", 403),
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
 * Denial cases that do not answer as their row expects, keyed `"<route> [<case>]"` (an MCP tool:
 * `"mcp:<tool> [no grant]"` or `"mcp/questions:<tool> [no grant]"`) with a one-line description and a
 * `// TODO-ISSUE` comment naming the issue that fixes it. Exact both ways: a listed gap that no longer
 * reproduces fails the test. Empty: every route and tool denies as designed.
 */
internal val KNOWN_GAPS: Map<String, String> = emptyMap()
