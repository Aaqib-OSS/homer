package homer.app.routes

import homer.app.models.SetMaintenanceRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * `GET`/`POST /api/v1/maintenance`: the maintenance-mode toggle and its state. `GET` admits an admin or a
 * `read` grant on `*` (ADR-0111); `POST` is admin-only. The gate runs before the body is parsed, and a denial
 * is a `404`, never a `403` (ADR-0037, ADR-0047 rule 3, ADR-0092).
 */
internal fun Route.maintenanceRoutes(deps: MaintenanceDeps) {
    with(deps) {
        get("/maintenance") {
            if (!authorizer.canReadServerState(call.principalName())) return@get call.respondNotFound()
            call.respondNoStore(maintenanceModeService.snapshot())
        }

        post("/maintenance") {
            if (!authorizer.isAdmin(call.principalName())) return@post call.respondNotFound()
            val request = call.receiveOr400<SetMaintenanceRequest>("Invalid JSON body: expected { enabled }")
            maintenanceModeService.setEnabled(request.enabled)
            call.respondNoStore(maintenanceModeService.snapshot())
        }
    }
}

/**
 * The denial: the exact `404` a path that was never mounted answers, body included — an
 * `ErrorResponse` here would let a grant-probing caller tell a denied handler from a
 * missing route, which is the existence the denial exists to hide.
 */
private suspend fun ApplicationCall.respondNotFound() = respond(HttpStatusCode.NotFound)
