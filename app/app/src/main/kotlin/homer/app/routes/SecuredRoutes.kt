package homer.app.routes

import homer.app.models.AgentNameMatcher
import homer.app.models.ErrorResponse
import homer.app.services.AgentsService
import homer.app.services.Authorizer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/** The bearer/HMAC/session principal name whichever auth provider authenticated this call; null in unsecured mode. */
internal fun ApplicationCall.principalName(): String? = principal<UserIdPrincipal>()?.name

/**
 * The admin gate, called explicitly at the top of each admin-only handler: answers 403
 * and returns true when the caller is not an admin.
 */
internal suspend fun ApplicationCall.deniedUnlessAdmin(authorizer: Authorizer): Boolean {
    if (authorizer.isAdmin(principalName())) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Admin role required"))
    return true
}

/**
 * The server-state read gate for handlers reporting runner state (`GET /runners`, `/runners/queue`): answers 403
 * and returns true without admin or a `read` grant on `*`, naming no alias or instance (ADR-0111).
 */
internal suspend fun ApplicationCall.deniedUnlessServerReader(authorizer: Authorizer): Boolean {
    if (authorizer.canReadServerState(principalName())) return false
    respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Read grant on * required"))
    return true
}

/**
 * Live state a reader polls: a cached copy would let a proxy or a browser serve a stale
 * answer after the state changed. See ADR-0092, ADR-0111.
 */
internal suspend fun ApplicationCall.respondNoStore(body: Any) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respond(body)
}

/**
 * The denial: the exact body-less `404` a path that was never mounted answers. An `ErrorResponse`
 * here would let a grant-probing caller tell a denied handler from a missing route, which is the
 * existence the denial exists to hide (ADR-0047 rule 3).
 */
internal suspend fun ApplicationCall.respondNotFound() = respond(HttpStatusCode.NotFound)

/**
 * The secured /api/v1 routes (everything under the bearer/HMAC authenticate wrapper), mounting the
 * per-resource files under routes/ in registration order.
 */
internal fun Route.apiV1Routes(deps: ApiDeps) {
    with(deps) {
        val agentDeps = agentRouteDeps()
        val dispatchDeps = dispatchContinuationDeps()
        route("/api/v1") {
            serverRoutes(serverRouteDeps())
            maintenanceRoutes(maintenanceDeps())
            agentListRoutes(agentDeps)
            processRoutes(processRouteDeps())
            agentRoutes(agentDeps)
            questionRoutes(questionRouteDeps())
            runMessageRoutes(runMessageDeps())
            dispatchRoutes(dispatchDeps)
            continuationRoutes(dispatchDeps)

            streamingRoute(processEventBroadcaster, logTailService, authorizer, processManager, llmEventStreamService)

            runRoutes(runRouteDeps())
        }
    }
}

/**
 * The loaded agents [principal] holds READ on, for the SQL visibility filter of a list read; null for an admin.
 * With an `owner = principal` clause it is the union rule of [Authorizer.canSee] (ADR-0037).
 */
internal fun visibleAgentNames(
    authorizer: Authorizer,
    agentsService: AgentsService,
    principal: String?,
): Set<String>? =
    authorizer.readableAgents(principal)?.let { patterns ->
        agentsService.agents.keys
            .filter { name -> patterns.any { AgentNameMatcher.matches(it, name) } }
            .toSet()
    }
