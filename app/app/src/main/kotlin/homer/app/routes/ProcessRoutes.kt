package homer.app.routes

import homer.app.HomerHeaders
import homer.app.exceptions.QueryParamValidationException
import homer.app.models.ErrorResponse
import homer.app.models.ProcessInfo
import homer.app.models.ProcessListQuery
import homer.app.models.ProcessListResponse
import homer.app.models.ProcessStatus
import homer.app.models.ProcessStatus.CREATED
import homer.app.models.ProcessStatus.WORKING
import homer.app.models.ProcessStatusResponse
import homer.app.models.TagValidation
import homer.app.runners.runnerType
import homer.app.screenTags
import homer.app.services.ProcessManager
import homer.app.toResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get

/** Process reads and control: `/status/all`, `/status/{processId}`, `/kill/{processId}`, `/artifacts*`. */
internal fun Route.processRoutes(deps: ProcessRouteDeps) {
    processListRoute(deps)
    processStatusRoute(deps)
    processKillRoute(deps)
    artifactRoute(deps)
    artifactListRoute(deps)
}

/** `GET /status/all`: one page of the processes the caller can see. */
private fun Route.processListRoute(deps: ProcessRouteDeps) {
    with(deps) {
        get("/status/all") {
            val principal = call.principalName()
            // Each invalid param throws a 400; the order of checks inside
            // parseStatusAllParams decides which error wins. The parsed query is
            // scoped to the caller here, before it is named.
            val query =
                parseStatusAllParams(call).copy(
                    visibleOwner = principal,
                    // Process-visibility union rule (ADR-0037): null visibleAgents
                    // (admin) means no filter.
                    visibleAgents = visibleAgentNames(authorizer, agentsService, principal),
                    // [CWE-203] Same per-row secret redaction the display
                    // path uses. See ADR-0060.
                    screenTags = { info ->
                        screenTags(info.tags, info.inputParams, agentsService.agents[info.agentName], info.secretInputKeys)
                    },
                    // [information_disclosure / CWE-200] A ?rootProcessId= filter names a
                    // root the caller may have only guessed: it is honoured only when that
                    // root row exists and is visible under the same union rule, else the
                    // page is empty — never "no filter". [sensitive_data_exposure / CWE-200]
                    // A row's rootProcessId is emitted only when the caller can see the root
                    // row; a hidden or missing root fails closed to null ("flow root
                    // unknown"). §12, ADR-0058.
                    canSeeRoot = { owner, agentName -> authorizer.canSee(principal, owner, agentName) },
                )
            // Alias is admin-only (CWE-200, fail closed). Same admin gate as the
            // single-process route. See ADR-0077.
            val aliasVisible = authorizer.isAdmin(principal)

            // One read transaction carries the page, its open-question counts
            // and the root-visibility checks below.
            val result = processManager.getAllProcesses(query)

            call.respond(processListResponse(result, query, aliasVisible))
        }
    }
}

/** `GET /status/{processId}`: one process the caller can see. */
private fun Route.processStatusRoute(deps: ProcessRouteDeps) {
    with(deps) {
        get("/status/{processId}") {
            val processId = call.parameters["processId"]?.toIntOrNull()

            if (processId == null) {
                throw QueryParamValidationException("Invalid process ID")
            }

            val processInfo = processManager.getProcessInfo(processId)
            if (processInfo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@get
            }

            val principal = call.principalName()
            if (!authorizer.canSee(principal, processInfo.owner, processInfo.agentName)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@get
            }

            val openQuestions = questionsService.openCountByProcess()[processId] ?: 0
            // [sensitive_data_exposure / CWE-200] Same root-visibility gate as
            // /status/all: the root id is dropped when its row is not openable by
            // this caller, so a descendant grant cannot probe a foreign flow root.
            val rootVisible =
                processInfo.rootProcessId
                    ?.let { rootId ->
                        val root = processManager.getProcessInfo(rootId)
                        root != null && authorizer.canSee(principal, root.owner, root.agentName)
                    } ?: true
            val holderVisible = holderRunVisible(processInfo, principal)
            val latestCommandArtifacts =
                if (processInfo.currentCommand != null) {
                    processManager.latestCommandArtifacts(processId)
                } else {
                    null
                }
            call.respond(
                processInfo.toResponse(
                    openQuestions = openQuestions,
                    agent = agentsService.agents[processInfo.agentName],
                    langfuse = config.langfuse,
                    rootVisible = rootVisible,
                    currentCommandKind = latestCommandArtifacts?.second?.kind,
                    currentCommandIndex = latestCommandArtifacts?.first,
                    // The alias is admin-only (CWE-200, fail closed). See ADR-0077.
                    aliasVisible = authorizer.isAdmin(principal),
                    runnerType = processInfo.runner?.let { runner -> runnerType(config, runner.alias) },
                    holderVisible = holderVisible,
                ),
            )
        }
    }
}

/**
 * Whether [principal] may open the run a failure reason names as [processInfo]'s blocker (a lock
 * holder), so its id may be appended to the reason. [sensitive_data_exposure / CWE-200] Fail
 * closed, like the root-id gate: a holder that is gone, or one this caller cannot see, withholds
 * the id. See ADR-0058.
 */
private suspend fun ProcessRouteDeps.holderRunVisible(
    processInfo: ProcessInfo,
    principal: String?,
): Boolean =
    processInfo.failureReasonRunId
        ?.let { holderId ->
            // Two columns by primary key, not the holder's whole row: this runs on the polled
            // /status/{id} path, and the holder is a run that was executing, so its row carries
            // the full execution blobs.
            val holder = processManager.getProcessVisibility(holderId)
            holder != null && authorizer.canSee(principal, holder.first, holder.second)
        } ?: false

/** `DELETE /kill/{processId}`: killing a process the caller can control. */
private fun Route.processKillRoute(deps: ProcessRouteDeps) {
    with(deps) {
        delete("/kill/{processId}") {
            val processId = call.parameters["processId"]?.toIntOrNull()

            if (processId == null) {
                throw QueryParamValidationException("Invalid process ID")
            }

            val info = processManager.getProcessInfo(processId)
            if (info == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@delete
            }

            if (!authorizer.canControl(call.principalName(), info.owner, info.agentName)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@delete
            }

            processManager.killProcess(processId)
            call.respond(ProcessStatusResponse(status = processManager.getProcessStatus(processId).name))
        }
    }
}

/** `GET /artifacts`: streaming one artifact of a process the caller can see. */
private fun Route.artifactRoute(deps: ProcessRouteDeps) {
    with(deps) {
        get("/artifacts") {
            val (processId, validatedPath) = artifactQuery(call)

            // Get process info
            val processInfo = processManager.getProcessInfo(processId)
            if (processInfo == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@get
            }

            if (!authorizer.canSee(call.principalName(), processInfo.owner, processInfo.agentName)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@get
            }

            // Resolve artifact path
            val artifactPath =
                artifactService.resolveArtifactPath(processId, validatedPath).getOrElse {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = it.message ?: "Artifact not found"))
                    return@get
                }

            // Check file exists
            if (!fileOperations.fileExists(artifactPath)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Artifact file not found"))
                return@get
            }

            artifactService.checkArtifactSize(artifactPath).onFailure { failure ->
                respondArtifactSizeFailure(call, failure)
                return@get
            }

            // Set response headers
            val isActive = processManager.getProcessStatus(processId) in listOf(WORKING, CREATED)
            call.response.headers.append(HomerHeaders.PROCESS_STATUS, processInfo.status.name)
            call.response.headers.append(HomerHeaders.FILE_COMPLETE, (!isActive).toString())

            // Stream file
            call.respondOutputStream(ContentType.Text.Plain, HttpStatusCode.OK) {
                artifactService.streamArtifact(artifactPath, this)
            }
        }
    }
}

/** `GET /artifacts/list`: the artifacts of a process the caller can see. */
private fun Route.artifactListRoute(deps: ProcessRouteDeps) {
    with(deps) {
        get("/artifacts/list") {
            val processId = call.request.queryParameters["processId"]?.toIntOrNull()
            if (processId == null) {
                throw QueryParamValidationException("Invalid or missing processId parameter")
            }

            val info = processManager.getProcessInfo(processId)
            if (info == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@get
            }

            if (!authorizer.canSee(call.principalName(), info.owner, info.agentName)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Process not found"))
                return@get
            }

            artifactService.listArtifacts(processId).fold(
                onSuccess = { listing -> call.respond(listing) },
                onFailure = { e ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(msg = e.message ?: "Not found"),
                    )
                },
            )
        }
    }
}

/** The `GET /status/all` response for the page [result] of [query]; [aliasVisible] is the caller's admin gate. */
private fun ProcessRouteDeps.processListResponse(
    result: ProcessManager.ProcessListResult,
    query: ProcessListQuery,
    aliasVisible: Boolean,
): ProcessListResponse =
    ProcessListResponse(
        processes =
            result.processes.map {
                it.toResponse(
                    openQuestions = result.openQuestions[it.id] ?: 0,
                    agent = agentsService.agents[it.agentName],
                    langfuse = config.langfuse,
                    rootVisible = it.rootProcessId?.let { rootId -> rootId in result.visibleRootIds } ?: true,
                    aliasVisible = aliasVisible,
                    runnerType = it.runner?.let { runner -> runnerType(config, runner.alias) },
                )
            },
        total = result.total,
        offset = query.offset,
        limit = query.limit,
    )

/**
 * The `processId` and the validated relative `path` query parameters of `GET /artifacts`; a `400`
 * when the id is missing or not an integer, or the path is missing or escapes the artifacts directory.
 */
private fun ProcessRouteDeps.artifactQuery(call: ApplicationCall): Pair<Int, String> {
    // Extract query parameters
    val processId = call.request.queryParameters["processId"]?.toIntOrNull()
    val relativePath = call.request.queryParameters["path"]

    // Validate processId
    if (processId == null) {
        throw QueryParamValidationException("Invalid or missing processId parameter")
    }

    // Validate path parameter
    if (relativePath.isNullOrBlank()) {
        throw QueryParamValidationException("Missing path parameter")
    }

    // Validate path security
    val validatedPath =
        artifactService.validateRelativePath(relativePath).getOrElse {
            throw QueryParamValidationException("Invalid path: ${it.message}")
        }
    return processId to validatedPath
}

/**
 * Parses and validates the `GET /api/v1/status/all` query parameters. On the
 * first invalid parameter it throws a [QueryParamValidationException] that
 * StatusPages answers `400` with the matching message. The validation order
 * (order → limit → offset → types) must be preserved — it determines which
 * error wins when several params are invalid.
 */
private fun parseStatusAllParams(call: ApplicationCall): ProcessListQuery {
    val orderParam = call.request.queryParameters["order"]?.lowercase() ?: "desc"

    val (limitParam, offsetParam) = call.validatePaging(orderParam)

    val filterStatuses = parseStatusTypes(call)

    val agentNameParam = call.request.queryParameters["agentName"]?.takeIf { it.isNotBlank() }

    val parentProcessId = positiveIdParam(call, "parentProcessId")

    // One whole flow in a single paginated read (ADR-0058).
    val rootProcessId = positiveIdParam(call, "rootProcessId")

    val roots = parseRootsParam(call)

    val tags = parseTagFilters(call)

    return ProcessListQuery(
        filterStatuses = filterStatuses,
        orderAsc = (orderParam == "asc"),
        offset = offsetParam,
        limit = limitParam,
        agentName = agentNameParam,
        parentProcessId = parentProcessId,
        rootProcessId = rootProcessId,
        rootsOnly = roots,
        tags = tags,
    )
}

/**
 * Throws the `400` for an invalid [order] first, then returns the validated `limit`/`offset` page
 * from the shared [pageParams] helper, so the checks run in the order → limit → offset order the
 * message selection depends on.
 */
private fun ApplicationCall.validatePaging(order: String): Pair<Int, Int> {
    if (order !in listOf("asc", "desc")) {
        throw QueryParamValidationException("Invalid order parameter. Must be 'asc' or 'desc'")
    }

    return pageParams { field, message -> QueryParamValidationException("Invalid $field parameter. $message") }
}

/** The `types` filter (comma-separated, repeatable), or null when absent; an unknown status is a `400`. */
private fun parseStatusTypes(call: ApplicationCall): Set<ProcessStatus>? {
    val typesParam =
        call.request.queryParameters
            .getAll("types")
            ?.flatMap { it.split(",").map { type -> type.trim() } }
            ?.filter { it.isNotEmpty() }
            ?: return null
    val validStatusNames =
        ProcessStatus
            .entries
            .map { it.name }
            .toSet()
    val invalidStatuses = typesParam.filterNot { it.uppercase() in validStatusNames }

    if (invalidStatuses.isNotEmpty()) {
        throw QueryParamValidationException("Invalid status types: ${invalidStatuses.joinToString(", ")}")
    }

    return typesParam.map { ProcessStatus.valueOf(it.uppercase()) }.toSet()
}

/** A process-id query parameter: null when absent or blank, a `400` unless a positive integer. */
private fun positiveIdParam(
    call: ApplicationCall,
    name: String,
): Int? {
    val raw = call.request.queryParameters[name]?.takeIf { it.isNotBlank() } ?: return null
    return raw.toIntOrNull()?.takeIf { it > 0 }
        ?: throw QueryParamValidationException("Invalid $name parameter. Must be a positive integer")
}

/**
 * Roots-only: the first run of each flow. Accepted values are the literal
 * `true` / `false` only — anything else is a caller error, not a silent
 * "off", so a typo cannot widen a roots view into the whole list.
 */
private fun parseRootsParam(call: ApplicationCall): Boolean {
    val rootsRaw =
        call.request.queryParameters["roots"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase()
    if (rootsRaw != null && rootsRaw !in listOf("true", "false")) {
        throw QueryParamValidationException("Invalid roots parameter. Must be 'true' or 'false'")
    }
    return rootsRaw == "true"
}

/**
 * Repeated `?tag=` params AND together (ADR-0058). Cap the count locally so a
 * caller cannot force an arbitrarily wide IN (...) / GROUP BY tag query — the
 * bound lives in this handler, not in the repository (§12 rule 4). De-dup here
 * so `tag=a&tag=a` is one predicate, not two.
 */
private fun parseTagFilters(call: ApplicationCall): List<String> {
    val tags =
        call.request.queryParameters
            .getAll("tag")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
    if (tags.size > TagValidation.MAX_TAG_QUERY_PARAMS) {
        throw QueryParamValidationException("At most ${TagValidation.MAX_TAG_QUERY_PARAMS} tag filters are allowed")
    }
    // [denial_of_service / CWE-400] Bound each value's length too: the count cap alone
    // still lets a caller bind 10 arbitrarily long strings that can never match a stored
    // tag (at most MAX_TAG_QUERY_LENGTH chars). Reject over-long values here, in the
    // handler, before they reach the DB bind (§12 rule 4).
    if (tags.any { it.length > TagValidation.MAX_TAG_QUERY_LENGTH }) {
        throw QueryParamValidationException("A tag filter may be at most ${TagValidation.MAX_TAG_QUERY_LENGTH} characters")
    }
    return tags
}
