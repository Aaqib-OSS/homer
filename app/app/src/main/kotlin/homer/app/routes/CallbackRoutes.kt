package homer.app.routes

import homer.app.EnvConstants
import homer.app.HomerHeaders
import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.QuestionNotFoundException
import homer.app.exceptions.RunNotAcceptingMessagesException
import homer.app.exceptions.ValidationException
import homer.app.infra.FileOperations
import homer.app.infra.ResultToEnvMapper
import homer.app.models.Agent
import homer.app.models.AskQuestionRequest
import homer.app.models.AskQuestionResponse
import homer.app.models.CreateDispatchGroupRequest
import homer.app.models.CreateDispatchGroupResponse
import homer.app.models.CreateDispatchRequest
import homer.app.models.CreateDispatchResponse
import homer.app.models.CreateJoinRequest
import homer.app.models.CreateRunRequest
import homer.app.models.DeclareTagRequest
import homer.app.models.DeclareTagResponse
import homer.app.models.ErrorResponse
import homer.app.models.ResultEnvParam
import homer.app.models.TagValidation
import homer.app.models.TakeRunMessagesResponse
import homer.app.models.TakenRunMessage
import homer.app.models.WhoamiResponse
import homer.app.services.ArtifactTooLargeException
import homer.app.services.DispatchGroupService
import homer.app.services.HookEvent
import homer.app.services.QuestionsService
import homer.app.services.RESULT_TRANSFORM_MAX_BYTES
import homer.app.services.RunMessageHookOutput
import homer.app.toResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val logger = LoggerFactory.getLogger("homer.app.routes.CallbackRoutes")

/**
 * Question callback routes for running commands, authenticated by the per-process `HMR_CALLBACK_TOKEN`,
 * never by operator credentials; mounted outside `authenticate(...)` (ADR-0019).
 */
internal fun Route.callbackRoutes(deps: CallbackDeps) {
    callbackQuestionRoutes(deps)
    callbackTagRoutes(deps)
    callbackDispatchRoutes(deps)
    callbackDispatchGroupRoutes(deps)
    callbackRunRoutes(deps)
    callbackJoinRoutes(deps)
    callbackResultRoutes(deps)
    callbackMessageRoutes(deps)
    callbackWhoamiRoutes(deps)
}

/** `POST /api/v1/callback/questions` and `GET …/{id}`: asking a question and polling its answer. */
private fun Route.callbackQuestionRoutes(deps: CallbackDeps) {
    with(deps) {
        route("/api/v1/callback/questions") {
            // Ask a question: { text, options? } → { id }. 409 when the
            // process is terminal, 429 at the open-question cap.
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request = call.receiveOr400<AskQuestionRequest>("Invalid JSON body: expected { text, options? }")
                val question = questionsService.ask(processId, request.text, request.options)
                call.respond(AskQuestionResponse(id = question.id))
            }

            // waitSec=0 returns the current snapshot; otherwise the clamped wait holds until the question leaves
            // OPEN, so the response may still be OPEN and the caller polls again with the same id.
            get("/{id}") {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@get
                val id = call.pathParameter("id")
                val waitSecParam = call.request.queryParameters["waitSec"]
                val waitSec =
                    if (waitSecParam == null) {
                        0
                    } else {
                        waitSecParam.toIntOrNull()
                            ?: throw ValidationException("waitSec must be an integer", "waitSec")
                    }

                // Ownership check BEFORE waiting: a foreign question id must
                // 404 immediately, not after holding the poll.
                val question = questionsService.get(id)
                if (question == null || question.processId != processId) {
                    throw QuestionNotFoundException(id)
                }

                val result =
                    questionsService.awaitAnswer(id, waitSec)
                        ?: throw QuestionNotFoundException(id)
                call.respond(result.toResponse())
            }
        }
    }
}

/** `POST /api/v1/callback/tags`: a run declaring one runtime subject tag. */
private fun Route.callbackTagRoutes(deps: CallbackDeps) {
    with(deps) {
        route("/api/v1/callback/tags") {
            // The run is identified by the callback token alone; a process id in the body is ignored (ADR-0019).
            // 409 for a terminal/closed run; 400 for an undeclared key, a malformed value or the per-run ceiling.
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                // A terminal/closed run can no longer describe its subject; mirror the
                // 409 the questions route gives a closed process.
                val registration = questionsService.registrationFor(processId)
                if (registration == null || registration.closed) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(msg = "Process $processId is no longer accepting tags"),
                    )
                    return@post
                }
                val request = call.receiveOr400<DeclareTagRequest>("Invalid JSON body: expected { tag }")
                // Fail closed if the run's agent definition was unloaded underneath us:
                // without it there is no runtime-key allowlist to authorize against.
                val agent = agentsService.agents[registration.agentName]
                if (agent == null) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse(msg = "Agent '${registration.agentName}' is no longer loaded"),
                    )
                    return@post
                }
                val (key, value) = runtimeTag(request.tag, agent)
                // A run tag is readable by every principal with a read grant, so a value carrying one of this run's
                // secrets is refused and never logged (CWE-200, ADR-0060).
                refuseSecretTagValue(registration, processId, key, value)
                val result =
                    processManager.addRuntimeTag(
                        processId,
                        "$key:$value",
                        TagValidation.MAX_RUNTIME_TAGS_PER_RUN,
                        agent.effectiveFromInputTags.toSet(),
                    )
                if (!result.written) {
                    throw QueryParamValidationException(
                        "Run already has the maximum of ${TagValidation.MAX_RUNTIME_TAGS_PER_RUN} runtime tags",
                    )
                }
                call.respond(DeclareTagResponse(tag = "$key:$value", count = result.count))
            }
        }
    }
}

/** `POST /api/v1/callback/dispatches`: a question that dispatches an agent once answered. */
private fun Route.callbackDispatchRoutes(deps: CallbackDeps) {
    with(deps) {
        route("/api/v1/callback/dispatches") {
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request =
                    call.receiveOr400<CreateDispatchRequest>(
                        "Invalid JSON body: expected { text, agent, options?, answerParam?, params?, ttlSec? }",
                    )
                val result =
                    dispatchService.create(
                        originProcessId = processId,
                        text = request.text,
                        options = request.options,
                        agent = request.agent,
                        answerParam = request.answerParam,
                        params = request.params,
                        ttlSec = request.ttlSec,
                    )
                call.respond(CreateDispatchResponse(result.dispatchId, result.questionId))
            }
        }
    }
}

/**
 * `POST /api/v1/callback/dispatch-groups`: N questions registered as one dispatch, the target
 * started once after the last of them with every answer in question order. See ADR-0109.
 */
private fun Route.callbackDispatchGroupRoutes(deps: CallbackDeps) {
    with(deps) {
        route("/api/v1/callback/dispatch-groups") {
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request =
                    call.receiveOr400<CreateDispatchGroupRequest>(
                        "Invalid JSON body: expected { questions: [{ text, options? }], agent, answerParam?, params?, ttlSec? }",
                    )
                val result =
                    dispatchGroupService.create(
                        originProcessId = processId,
                        questions = request.questions.map { DispatchGroupService.QuestionSpec(it.text, it.options) },
                        agent = request.agent,
                        answerParam = request.answerParam,
                        params = request.params,
                        ttlSec = request.ttlSec,
                    )
                call.respond(CreateDispatchGroupResponse(result.dispatchId, result.questionIds))
            }
        }
    }
}

/** `POST /api/v1/callback/runs`: starting an agent at once, optionally with a completion continuation. */
private fun Route.callbackRunRoutes(deps: CallbackDeps) {
    with(deps) {
        route("/api/v1/callback/runs") {
            // Starts an opt-in target agent as a fresh chain, the asking process recorded only as referer (ADR-0022);
            // `continueAgent` also registers a completion continuation on the parent (ADR-0023).
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request =
                    call.receiveOr400<CreateRunRequest>(
                        "Invalid JSON body: expected { agent, params?, continueAgent?, continueThread?, " +
                            "continueParams?, resumeParam?, resultJsonPath?, ttlSec? }",
                    )
                call.respond(continuationService.startWithContinuation(processId, request))
            }
        }
    }
}

/** `POST /api/v1/callback/joins`: a parallel fan-out resumed by one join continuation. */
private fun Route.callbackJoinRoutes(deps: CallbackDeps) {
    with(deps) {
        route("/api/v1/callback/joins") {
            // One join continuation resumes the parent once after the last sub ends; the service validates before
            // starting anything and kills started subs on a later failure, so a 4xx orphans none (ADR-0026).
            post {
                val processId = call.resolveCallbackProcess(questionsService) ?: return@post
                val request =
                    call.receiveOr400<CreateJoinRequest>(
                        "Invalid JSON body: expected { subs:[{ agent, params?, resultJsonPath?, interruptId }], " +
                            "continueAgent, continueThread?, continueParams?, resumeParam?, ttlSec? }",
                    )
                call.respond(continuationService.startJoin(processId, request))
            }
        }
    }
}

/** `GET /api/v1/callback/results/{token}`: a continuation result read by reference. */
private fun Route.callbackResultRoutes(deps: CallbackDeps) {
    with(deps) {
        route("/api/v1/callback/results") {
            // Triple-auth: callback token, a path capability token scoped to one (watched process, artifact), and the
            // caller's agent matching the token's audience, so a leaked token is useless to others (ADR-0025).
            get("/{token}") {
                val callerProcessId = call.resolveCallbackProcess(questionsService) ?: return@get
                val token = call.pathParameter("token")
                val record = resultTokenService.resolve(token)
                if (record == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Invalid or expired result token"))
                    return@get
                }
                // Audience check: the caller must run the agent the token was minted for.
                // Fail closed when the caller's registration is gone (e.g. after a restart).
                val callerAgent = questionsService.registrationFor(callerProcessId)?.agentName
                if (callerAgent != record.consumerAgent) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse(msg = "Result token not valid for this agent"))
                    return@get
                }
                val artifactPath =
                    artifactService.resolveArtifactPath(record.watchedProcessId, record.path).getOrElse {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = it.message ?: "Artifact not found"))
                        return@get
                    }
                if (!fileOperations.fileExists(artifactPath)) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Artifact file not found"))
                    return@get
                }
                val size =
                    artifactService.checkArtifactSize(artifactPath).getOrElse { failure ->
                        respondArtifactSizeFailure(call, failure)
                        return@get
                    }
                respondTransformedResult(call, fileOperations, artifactPath, size, record.resultTransformer)
            }
        }
    }
}

/** `POST /api/v1/callback/messages/take`: a run taking the operator messages queued for it. */
private fun Route.callbackMessageRoutes(deps: CallbackDeps) {
    with(deps) {
        // Takes only the token's own run's QUEUED messages (a child run has its own token), marking them DELIVERED.
        // `?hook=` answers as hook JSON, or 204 when empty. Never long-polls, so hooks stay fast (ADR-0097).
        post("/api/v1/callback/messages/take") {
            val processId = call.resolveCallbackProcess(questionsService) ?: return@post
            val hook =
                call.request.queryParameters["hook"]?.let { param ->
                    HookEvent.fromParam(param)
                        ?: throw QueryParamValidationException("hook must be one of: ${HookEvent.entries.joinToString { it.param }}")
                }
            val registration = questionsService.registrationFor(processId)
            if (registration == null || registration.closed) throw RunNotAcceptingMessagesException(processId)
            val messages = runMessagesService.take(processId)
            when {
                hook == null -> call.respond(TakeRunMessagesResponse(messages.map { TakenRunMessage(it.id, it.text, it.createdAt) }))
                messages.isEmpty() -> call.respond(HttpStatusCode.NoContent)
                else -> call.respond(RunMessageHookOutput.render(hook, messages))
            }
        }
    }
}

/**
 * `/api/v1/callback/whoami`: the run holding the presented callback token, for an egress proxy
 * that decides per agent. A missing, unknown or ended run's token gets one identical `401`.
 * Any method: envoy's `ext_authz` HTTP check reuses the proxied request's method. See ADR-0110.
 */
private fun Route.callbackWhoamiRoutes(deps: CallbackDeps) {
    with(deps) {
        route("/api/v1/callback/whoami") {
            handle {
                val token = call.request.headers[HomerHeaders.CALLBACK_TOKEN]?.takeIf { it.isNotBlank() } ?: call.bearerToken()
                val processId = token?.takeIf { it.isNotEmpty() }?.let { questionsService.resolveToken(it) }
                val registration = processId?.let { questionsService.registrationFor(it) }
                if (processId == null || registration == null || registration.closed) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Invalid callback token"))
                    return@handle
                }
                call.response.header(HomerHeaders.AGENT, registration.agentName)
                call.response.header(HomerHeaders.PROCESS_ID, processId.toString())
                call.respond(WhoamiResponse(processId = processId, agent = registration.agentName))
            }
        }
    }
}

/** Refuses with a `400` a runtime tag [value] carrying any secret of the run's [registration]; logs the key, never the value. */
private fun refuseSecretTagValue(
    registration: QuestionsService.Registration,
    processId: Int,
    key: String,
    value: String,
) {
    if (registration.secretRedactor.redact(value) != value) {
        logger.warn(
            "Process {} (agent '{}'): runtime tag with key '{}' refused — value contains a secret",
            processId,
            registration.agentName,
            key,
        )
        throw QueryParamValidationException("Tag value must not contain a secret value")
    }
}

/**
 * Answers a result fetch with [transformer] applied to the artifact at [artifactPath], [size] bytes long
 * when checked; a transform that matches nothing yields an empty body. The read stops at the transform cap.
 */
private suspend fun respondTransformedResult(
    call: ApplicationCall,
    fileOperations: FileOperations,
    artifactPath: Path,
    size: Long,
    transformer: ResultEnvParam,
) {
    if (size > RESULT_TRANSFORM_MAX_BYTES) {
        call.respond(
            HttpStatusCode.PayloadTooLarge,
            ErrorResponse(msg = "Result too large to transform ($size > $RESULT_TRANSFORM_MAX_BYTES)"),
        )
        return
    }
    val text = fileOperations.readTextBounded(artifactPath, RESULT_TRANSFORM_MAX_BYTES)
    if (text == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "Artifact file not found"))
        return
    }
    val value =
        runCatching {
            ResultToEnvMapper(0, readStdOut = { text }, readStdErr = { "" })
                .mapToEnv(listOf(transformer))[transformer.envName]
        }.getOrNull()
    call.respondText(value ?: "", ContentType.Text.Plain)
}

/**
 * The key and value of the runtime tag [tag] a run of [agent] declares: a 400 for anything but
 * `key:value` with a declared `tags.runtime` key and a value matching [TagValidation.VALUE_PATTERN].
 */
internal fun runtimeTag(
    tag: String,
    agent: Agent,
): Pair<String, String> {
    val separator = tag.indexOf(':')
    if (separator <= 0 || separator == tag.length - 1) {
        throw QueryParamValidationException("Tag must be 'key:value'")
    }
    val key = tag.substring(0, separator)
    val value = tag.substring(separator + 1)
    if (key !in agent.tags.runtime) {
        val why =
            if (key in agent.effectiveFromInputTags) {
                "key '$key' is a tags.fromInputs key; runtime tags cannot override it"
            } else {
                "key '$key' is not a declared tags.runtime key"
            }
        throw QueryParamValidationException(why)
    }
    if (!TagValidation.VALUE_PATTERN.matches(value)) {
        throw QueryParamValidationException("Tag value must match ^[A-Za-z0-9._-]{1,${TagValidation.MAX_TAG_VALUE_LENGTH}}\$")
    }
    return key to value
}

/**
 * Extracts the Bearer callback token and resolves it to a process id.
 * Responds 401 and returns null when the token is missing or unknown
 * (a restart wipes the registry, so helpers blocked across one land here).
 */
private suspend fun ApplicationCall.resolveCallbackProcess(questionsService: QuestionsService): Int? {
    val token = bearerToken()
    if (token.isNullOrEmpty()) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Missing callback token"))
        return null
    }
    val processId = questionsService.resolveToken(token)
    if (processId == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Invalid callback token"))
        return null
    }
    return processId
}

/** The token of an `Authorization: Bearer <token>` header, or null. */
private fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null }
