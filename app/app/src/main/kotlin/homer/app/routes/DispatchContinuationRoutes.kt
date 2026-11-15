package homer.app.routes

import homer.app.exceptions.ContinuationNotFoundException
import homer.app.exceptions.DispatchNotFoundException
import homer.app.exceptions.ValidationException
import homer.app.models.ContinuationListResponse
import homer.app.models.DispatchListResponse
import homer.app.redactStoredParams
import homer.app.services.Continuation
import homer.app.services.ContinuationStatus
import homer.app.services.Dispatch
import homer.app.services.DispatchStatus
import homer.app.toResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlin.enums.EnumEntries

/** `/dispatches`: list, read and cancel ask-and-dispatch rows. */
internal fun Route.dispatchRoutes(deps: ApiDeps) {
    with(deps) {
        route("/dispatches") {
            get {
                val query = call.recordListQuery(DispatchStatus.entries)
                val principal = call.principalName()
                // Visibility runs in SQL before the limit, so the page and `total` hold only
                // rows this caller can see (§12, ADR-0039).
                val page =
                    dispatchService.page(
                        query.status,
                        query.agentName,
                        principal,
                        visibleAgentNames(authorizer, agentsService, principal),
                        query.offset,
                        query.limit,
                    )
                call.respond(
                    DispatchListResponse(
                        dispatches = page.items.map { it.toResponse(redactedParams(it)) },
                        total = page.total,
                        offset = query.offset,
                        limit = query.limit,
                    ),
                )
            }
            get("/{id}") {
                val dispatch = storedDispatch(call)
                if (!authorizer.canSee(call.principalName(), dispatch.owner, dispatch.agentName)) {
                    throw DispatchNotFoundException(dispatch.id)
                }
                call.respond(dispatch.toResponse(redactedParams(dispatch)))
            }
            delete("/{id}") {
                val dispatch = storedDispatch(call)
                if (!authorizer.canControl(call.principalName(), dispatch.owner, dispatch.agentName)) {
                    throw DispatchNotFoundException(dispatch.id)
                }
                dispatchService.cancel(dispatch.id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** `/continuations`: list, read and cancel completion continuations. */
internal fun Route.continuationRoutes(deps: ApiDeps) {
    with(deps) {
        route("/continuations") {
            get {
                val query = call.recordListQuery(ContinuationStatus.entries)
                val principal = call.principalName()
                // Visibility runs in SQL before the limit, so the page and `total` hold only
                // rows this caller can see (§12, ADR-0039).
                val page =
                    continuationService.page(
                        query.status,
                        query.agentName,
                        principal,
                        visibleAgentNames(authorizer, agentsService, principal),
                        query.offset,
                        query.limit,
                    )
                call.respond(
                    ContinuationListResponse(
                        continuations = page.items.map { it.toResponse(redactedParams(it)) },
                        total = page.total,
                        offset = query.offset,
                        limit = query.limit,
                    ),
                )
            }
            get("/{id}") {
                val continuation = storedContinuation(call)
                if (!authorizer.canSee(call.principalName(), continuation.owner, continuation.targetAgentName)) {
                    throw ContinuationNotFoundException(continuation.id)
                }
                call.respond(continuation.toResponse(redactedParams(continuation)))
            }
            delete("/{id}") {
                val continuation = storedContinuation(call)
                if (!authorizer.canControl(call.principalName(), continuation.owner, continuation.targetAgentName)) {
                    throw ContinuationNotFoundException(continuation.id)
                }
                continuationService.cancel(continuation.id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** The filters and page of a dispatch or continuation list request. */
private class RecordListQuery<S>(
    val status: S?,
    val agentName: String?,
    val limit: Int,
    val offset: Int,
)

/**
 * `status` (one of [statuses], any case), `agentName`, and the [recordPageParams] page of a dispatch
 * or continuation list request; an unknown status is a `400`.
 */
private fun <S : Enum<S>> ApplicationCall.recordListQuery(statuses: EnumEntries<S>): RecordListQuery<S> {
    val status =
        request.queryParameters["status"]?.let { raw ->
            statuses.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw ValidationException("Invalid status: $raw", "status")
        }
    val agentName = request.queryParameters["agentName"]
    val (limit, offset) = recordPageParams()
    return RecordListQuery(status, agentName, limit, offset)
}

/**
 * `limit` (default [DEFAULT_PAGE_LIMIT], 1..[MAX_PAGE_LIMIT]) and `offset` (default 0) of the
 * question, dispatch and continuation lists, as on `/status/all`: the shared [pageParams] helper
 * with this family's own `ValidationException` wording.
 */
internal fun ApplicationCall.recordPageParams(): Pair<Int, Int> = pageParams { field, message -> ValidationException(message, field) }

/** The `{id}` path parameter of a [kind] (dispatch or continuation) request; a non-integer is a `400`. */
private fun ApplicationCall.recordId(kind: String): Int =
    pathParameter("id").toIntOrNull() ?: throw ValidationException("Invalid $kind id", "id")

/** The dispatch the `{id}` path parameter names; a non-integer id is a `400`, an unknown one a `404`. */
private suspend fun ApiDeps.storedDispatch(call: ApplicationCall): Dispatch {
    val id = call.recordId("dispatch")
    return dispatchService.get(id) ?: throw DispatchNotFoundException(id)
}

/** The continuation the `{id}` path parameter names; a non-integer id is a `400`, an unknown one a `404`. */
private suspend fun ApiDeps.storedContinuation(call: ApplicationCall): Continuation {
    val id = call.recordId("continuation")
    return continuationService.get(id) ?: throw ContinuationNotFoundException(id)
}

/** [dispatch]'s stored params, secrets masked against its agent's current definition. */
private fun ApiDeps.redactedParams(dispatch: Dispatch): Map<String, String> =
    redactStoredParams(
        dispatch.params,
        dispatch.secretInputKeys,
        agentsService.agents[dispatch.agentName],
        dispatch.secretParamNames,
    )

/** [continuation]'s stored params, secrets masked against its target agent's current definition. */
private fun ApiDeps.redactedParams(continuation: Continuation): Map<String, String> =
    redactStoredParams(
        continuation.params,
        continuation.secretInputKeys,
        agentsService.agents[continuation.targetAgentName],
        continuation.secretParamNames,
    )
