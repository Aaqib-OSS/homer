package homer.app.routes

import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path

/** How a served row's body is read back by `SecretRedactionCanaryTest`. */
internal enum class CanaryPayload {
    /** A JSON DTO, read whole. */
    JSON,

    /** A raw artifact body, read whole. */
    PLAIN,

    /** A `text/event-stream` body, read frame by frame under a bounded read. */
    SSE,
}

/**
 * One route, classified for the canary sweep: a [payload] plus the request that reaches its body, or
 * an exemption (`payload == null`) with [note] saying why the route carries no run data.
 *
 * [target] and [body] fill the route's placeholders from the canary run the way `ROUTE_AUTHZ`'s samples
 * do (`{processId}`, `{watchedId}`, `{questionId}`, `{dispatchId}`, `{continuationId}`, `{agentName}`,
 * `{...}`); [expect] is the status the probe must answer before its body is read. [startsRun] marks a
 * row whose request launches a run: the sweep waits for that run, so none outlives the test.
 *
 * A route the canary agent cannot exercise — a callback credential the test does not hold, a
 * runner-protocol endpoint, a command type the agent does not declare — is exempt. An exemption is a
 * decision, so it states its reason: `SecretRedactionCanaryTest` fails on a blank one.
 */
internal data class CanaryRoute(
    val route: String,
    val note: String,
    val payload: CanaryPayload? = null,
    val target: String? = null,
    val body: String? = null,
    val expect: Int = 200,
    val startsRun: Boolean = false,
)

private fun served(
    route: String,
    payload: CanaryPayload,
    note: String,
    target: String? = null,
    body: String? = null,
    expect: Int = 200,
    startsRun: Boolean = false,
) = CanaryRoute(route, note, payload, target, body, expect, startsRun)

private fun exempt(
    route: String,
    note: String,
) = CanaryRoute(route, note)

/** A run's own callback token is minted inside the server and never handed to a test principal. */
private const val CALLBACK_CREDENTIAL = "callback token of a live run; the canary run's token never leaves the server"

/** The runner protocol carries a remote host's work, not this run's data. */
private const val RUNNER_PROTOCOL = "runner protocol; the canary run is local"

/**
 * The run-start body of the canary run: both canaries enter the run here, through the `body`-sourced
 * inputs the canary agent declares. `{canary}` is filled by `SecretRedactionCanaryTest`'s sample map.
 */
internal const val CANARY_START_BODY = """{"canary":"{canary}","tagged":"{canary}"}"""

/**
 * Every route `configureRouting` mounts with every optional surface on, in registration order, each
 * classified `served` or exempt. A new route fails `SecretRedactionCanaryTest` until it has a row here
 * (app/CLAUDE.md, "Adding a new HTTP endpoint"), the same way `ROUTE_AUTHZ` covers authorization.
 */
internal val CANARY_ROUTES: List<CanaryRoute> =
    listOf(
        exempt("GET /api/v1/livez", "liveness probe: no run data"),
        exempt("GET /api/v1/readyz", "readiness probe: no run data"),
        exempt("POST /api/v1/auth/login", "auth: no run data"),
        exempt("POST /api/v1/auth/logout", "auth: no run data"),
        exempt("GET /api/v1/auth/me", "session identity: no run data"),
        exempt("POST /api/v1/callback/questions", CALLBACK_CREDENTIAL),
        exempt("GET /api/v1/callback/questions/{id}", CALLBACK_CREDENTIAL),
        exempt("POST /api/v1/callback/tags", CALLBACK_CREDENTIAL),
        exempt("POST /api/v1/callback/dispatches", CALLBACK_CREDENTIAL),
        exempt("POST /api/v1/callback/dispatch-groups", CALLBACK_CREDENTIAL),
        exempt("POST /api/v1/callback/runs", CALLBACK_CREDENTIAL),
        exempt("POST /api/v1/callback/joins", CALLBACK_CREDENTIAL),
        exempt("GET /api/v1/callback/results/{token}", CALLBACK_CREDENTIAL),
        exempt("POST /api/v1/callback/messages/take", CALLBACK_CREDENTIAL),
        exempt("* /api/v1/callback/whoami", CALLBACK_CREDENTIAL),
        exempt("POST /api/v1/runner/register", RUNNER_PROTOCOL),
        exempt("DELETE /api/v1/runner/instances/{id}", RUNNER_PROTOCOL),
        exempt("POST /api/v1/runner/instances/{id}/claim", RUNNER_PROTOCOL),
        exempt("POST /api/v1/runner/sessions/{sid}/next", RUNNER_PROTOCOL),
        exempt("GET /api/v1/runner/sessions/{sid}/ops/{opId}/tar", RUNNER_PROTOCOL),
        exempt("POST /api/v1/runner/sessions/{sid}/ops/{opId}/tar", RUNNER_PROTOCOL),
        exempt("POST /api/v1/runner/sessions/{sid}/ops/{opId}/output", RUNNER_PROTOCOL),
        exempt("POST /mcp/questions", "MCP transport: $CALLBACK_CREDENTIAL"),
        exempt("GET /mcp/questions", "method-mismatch: the endpoint is POST-only, so the probe answers 405 with no body"),
        exempt("DELETE /mcp/questions", "method-mismatch: the endpoint is POST-only, so the probe answers 405 with no body"),
        exempt("GET /api/v1/heartbeat", "server liveness: no run data"),
        exempt("GET /api/v1/health", "server health: no run data"),
        served(
            "GET /api/v1/costs",
            CanaryPayload.JSON,
            "the cost ledger's aggregates; the canary run makes no priced LLM attempt",
        ),
        served("GET /api/v1/costs/monthly", CanaryPayload.JSON, "monthly cost aggregates per agent"),
        served("GET /api/v1/costs/totals", CanaryPayload.JSON, "cost totals per agent"),
        served(
            "GET /api/v1/costs/by-tag/{key}",
            CanaryPayload.JSON,
            "cost per value of a tag key: the values are screened before they key the map (ADR-0052)",
            target = "/api/v1/costs/by-tag/tagged",
        ),
        exempt("GET /api/v1/status", "server status (start time, last run id): no run data"),
        served("GET /api/v1/status/all", CanaryPayload.JSON, "one page of run rows, input params redacted and tags screened"),
        served("GET /api/v1/status/{processId}", CanaryPayload.JSON, "the canary run's row: input params, tags, executions"),
        served(
            "POST /api/v1/status/{processId}/messages",
            CanaryPayload.JSON,
            "a queued run message, on the run that accepts one",
            target = "/api/v1/status/{watchedId}/messages",
            body = """{"text":"ping"}""",
            expect = 202,
        ),
        served(
            "GET /api/v1/status/{processId}/messages",
            CanaryPayload.JSON,
            "a run's inbox",
            target = "/api/v1/status/{watchedId}/messages",
        ),
        exempt("GET /api/v1/runners", "runner state: no run data"),
        exempt("GET /api/v1/runners/queue", "runner queue: no run data"),
        exempt("GET /api/v1/maintenance", "maintenance flag: no run data"),
        exempt("POST /api/v1/maintenance", "maintenance flag: no run data"),
        served("GET /api/v1/agents", CanaryPayload.JSON, "agent summaries; a secret input's default is dropped (ADR-0071)"),
        served("POST /api/v1/agents/reload", CanaryPayload.JSON, "the reload diff of the canary agent"),
        exempt("POST /api/v1/agents", "writes an agent definition: no run data"),
        served("GET /api/v1/agents/{agentName}/files", CanaryPayload.JSON, "the canary agent's file listing"),
        served(
            "GET /api/v1/agents/{agentName}/files/{...}",
            CanaryPayload.JSON,
            "the canary agent's agent.yaml, which names its secret inputs but holds no value",
            target = "/api/v1/agents/{agentName}/files/agent.yaml",
        ),
        exempt("PUT /api/v1/agents/{agentName}/files/{...}", "writes an agent file: no run data"),
        exempt("DELETE /api/v1/agents/{agentName}/files/{...}", "deletes an agent file: no run data"),
        exempt("GET /api/v1/agents/{agentName}/langgraph", "renders a LangGraph command's topology; the canary agent declares none"),
        exempt("POST /api/v1/agents/{agentName}/debug", "debugs a LangGraph command; the canary agent declares none"),
        exempt("POST /api/v1/agents/{agentName}/cron-run", "fires a cron entry; the canary agent declares none"),
        exempt("DELETE /api/v1/kill/{processId}", "kills a run; the response carries no run data"),
        served(
            "GET /api/v1/artifacts",
            CanaryPayload.PLAIN,
            "a raw artifact body: the canary run writes its own output there and never a secret (ADR-0070)",
            target = "/api/v1/artifacts?processId={processId}&path=cmd_0.out",
        ),
        served(
            "GET /api/v1/artifacts/list",
            CanaryPayload.JSON,
            "the canary run's artifact directory listing",
            target = "/api/v1/artifacts/list?processId={processId}",
        ),
        exempt("GET /api/v1/schemas/agent", "the static agent schema: no run data"),
        served("GET /api/v1/questions", CanaryPayload.JSON, "the question rows"),
        served(
            "POST /api/v1/questions/{id}/answer",
            CanaryPayload.JSON,
            "the answered question's row",
            target = "/api/v1/questions/{questionId}/answer",
            body = """{"answer":"yes"}""",
        ),
        served("GET /api/v1/dispatches", CanaryPayload.JSON, "the dispatch rows"),
        served(
            "GET /api/v1/dispatches/{id}",
            CanaryPayload.JSON,
            "a dispatch's stored params, redacted per ADR-0052",
            target = "/api/v1/dispatches/{dispatchId}",
        ),
        exempt("DELETE /api/v1/dispatches/{id}", "cancels a dispatch; the response carries no run data"),
        served("GET /api/v1/continuations", CanaryPayload.JSON, "the continuation rows"),
        served(
            "GET /api/v1/continuations/{id}",
            CanaryPayload.JSON,
            "a continuation's stored params, redacted per ADR-0052",
            target = "/api/v1/continuations/{continuationId}",
        ),
        exempt("DELETE /api/v1/continuations/{id}", "cancels a continuation; the response carries no run data"),
        served("GET /api/v1/stream/processes", CanaryPayload.SSE, "run events: ids, statuses and labels only"),
        served(
            "GET /api/v1/stream/processes/{id}/logs",
            CanaryPayload.SSE,
            "the raw stdout tail: the canary run writes its own output there and never a secret (ADR-0070)",
            target = "/api/v1/stream/processes/{processId}/logs?cmd=0&stream=out",
        ),
        served(
            "GET /api/v1/stream/processes/{id}/llm-events",
            CanaryPayload.SSE,
            "translated LLM events, each redacted with the run's SecretRedactor; the canary agent " +
                "declares no harness command, so process mode ends at once on `llm.end`",
            target = "/api/v1/stream/processes/{processId}/llm-events",
        ),
        served("GET /api/v1/stream/agents", CanaryPayload.SSE, "agent reload diffs"),
        served(
            "GET /api/v1/agent/{agentName}",
            CanaryPayload.JSON,
            "a run start like the POST row's; the canary agent's body-sourced input makes GET answer " +
                "the 'use POST' refusal, whose body names no input value",
            expect = 400,
        ),
        served(
            "POST /api/v1/agent/{agentName}",
            CanaryPayload.JSON,
            "the run-start response; the request body is where both canaries enter the run",
            body = CANARY_START_BODY,
            startsRun = true,
        ),
        served(
            "POST /api/v1/processes/{id}/retry",
            CanaryPayload.JSON,
            "the replay's start response; the copy carries the source run's inputs",
            target = "/api/v1/processes/{processId}/retry",
            startsRun = true,
        ),
        exempt("GET /api/v1/processes/{id}/langgraph", "renders a LangGraph run's state; the canary agent declares none"),
        exempt("POST /api/v1/processes/{id}/resume", "resumes a LangGraph run; the canary agent declares none"),
        exempt("POST /mcp", "protocol-exempt: MCP JSON-RPC transport; its run tools are covered by McpRunToolsTest"),
        exempt("GET /mcp", "method-mismatch: the endpoint is POST-only, so the probe answers 405 with no body"),
        exempt("DELETE /mcp", "method-mismatch: the endpoint is POST-only, so the probe answers 405 with no body"),
    )

/** The routes [app] registers, in registration order: `"<METHOD> <path>"`, `"* <path>"` for a method-less node. */
internal fun registeredRoutes(app: Application): List<String> =
    app.plugin(RoutingRoot).getAllRoutes().map { node ->
        val method = (node.selector as? HttpMethodRouteSelector)?.method
        if (method == null) "* ${node.path}" else "${method.value} ${node.parent!!.path}"
    }
