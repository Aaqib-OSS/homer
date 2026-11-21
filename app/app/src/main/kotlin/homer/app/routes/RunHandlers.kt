package homer.app.routes

import homer.app.EnvConstants
import homer.app.HomerHeaders
import homer.app.SpanAttributes
import homer.app.exceptions.QueryParamValidationException
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.AgentInputResolver
import homer.app.infra.ReservedEnvNames
import homer.app.infra.TelemetryService
import homer.app.infra.langfuseTraceTags
import homer.app.infra.stampLangfuseTrace
import homer.app.infra.startAgentRootSpan
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.DebugAgentRequest
import homer.app.models.ErrorResponse
import homer.app.models.ExecuteResponse
import homer.app.models.ProcessInfo
import homer.app.models.RunStartRequest
import homer.app.services.AgentRunStarter
import homer.app.services.Authorizer
import homer.app.services.CostMeter
import homer.app.services.MaintenanceModeService
import homer.app.services.ProcessManager
import homer.app.services.gated
import homer.app.services.renderCooldownKey
import homer.app.services.visibleOriginRootProcessId
import homer.app.services.visibleRootProcessId
import homer.app.services.withoutFailureBackoff
import io.ktor.http.HttpStatusCode
import io.ktor.http.ParametersBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context

internal suspend fun handleAgentExecution(
    call: ApplicationCall,
    agent: Agent,
    agentInputResolver: AgentInputResolver,
    agentRunStarter: AgentRunStarter,
    // Already validated against the caller's visibility by resolveParentProcessHeader;
    // a link is metadata only and never joins the chain or lock scope below. ADR-0058.
    parentProcessId: Int? = null,
    // The validated parent's own root (`parent.rootProcessId ?: parent.id`), so an external run joins the
    // parent's flow; no new caller-settable input (ADR-0058).
    rootProcessId: Int? = null,
) {
    // External callers always start a fresh chain with no inherited lock keys; the route trusts no
    // chain or lock headers (ADR-0030).
    val processId =
        agentRunStarter.start(
            RunStartRequest(
                agent = agent,
                invocationChain = emptyList(),
                source = "http",
                owner = call.principalName(),
                parentProcessId = parentProcessId,
                rootProcessId = rootProcessId,
                resolveInputs = { agentInputResolver.resolve(call, agent) },
            ),
        )

    call.respond(ExecuteResponse(processId = processId))
}

/** [AgentInputResolver.resolve] over a live call; the body is read only if an input needs it. */
internal suspend fun AgentInputResolver.resolve(
    call: ApplicationCall,
    agent: Agent,
): Map<String, String> =
    resolve(
        method = call.request.httpMethod,
        queryParameters = call.request.queryParameters,
        headers = call.request.headers,
        readBody = { call.receiveText() },
        agent = agent,
    )

/**
 * Reads the optional `X-Homer-Parent-Process-Id` header and resolves the parent row. A missing and an invisible
 * parent answer the same 422, so ids cannot be probed (ADR-0058); a non-integer value is a 400.
 */
internal suspend fun resolveParentProcessHeader(
    call: ApplicationCall,
    processManager: ProcessManager,
    authorizer: Authorizer,
): ProcessInfo? {
    val raw =
        call.request.headers[HomerHeaders.PARENT_PROCESS_ID]
            ?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return null
    val parentId =
        raw.toIntOrNull()?.takeIf { it > 0 }
            ?: throw QueryParamValidationException(
                "Invalid ${HomerHeaders.PARENT_PROCESS_ID} header. Must be a positive integer",
                HttpStatusCode.BadRequest,
            )
    val parent = processManager.getProcessInfo(parentId)
    if (parent == null || !authorizer.canSee(call.principalName(), parent.owner, parent.agentName)) {
        throw QueryParamValidationException("Parent process $parentId not found", HttpStatusCode.UnprocessableEntity)
    }
    return parent
}

/**
 * The parent id and flow root of an external run, both derived from the one visibility-checked parent row
 * [resolveParentProcessHeader] fetched, so they cannot disagree (ADR-0058).
 */
internal suspend fun resolveParentHeaderLinks(
    call: ApplicationCall,
    processManager: ProcessManager,
    authorizer: Authorizer,
): Pair<Int?, Int?> {
    val parent = resolveParentProcessHeader(call, processManager, authorizer) ?: return null to null
    // The parent is visible by construction, its root may not be: a hidden root is dropped, never replaced
    // by the parent id, so the run is stored rootless rather than mis-rooted (CWE-200, ADR-0058).
    val rootId =
        if (parent.rootProcessId == null) {
            parent.id
        } else {
            processManager.visibleRootProcessId(call.principalName(), authorizer, parent.rootProcessId)
        }
    return parent.id to rootId
}

internal suspend fun handleAgentDebug(
    serverId: String,
    call: ApplicationCall,
    agent: Agent,
    agentInputResolver: AgentInputResolver,
    processExecutor: ProcessExecutor,
    processFactory: ProcessFactory,
    telemetryService: TelemetryService,
    maintenanceGate: MaintenanceModeService,
) {
    val req =
        call.receiveOr400<DebugAgentRequest>(
            "Invalid JSON body: expected { commandIndex, env, queryParams, body, headers }",
        )
    if (req.commandIndex < 0 || req.commandIndex >= agent.commands.size) {
        throw QueryParamValidationException("commandIndex out of range: ${req.commandIndex} (0..${agent.commands.size - 1})")
    }
    val reservedOverrides = req.env.keys.filter { ReservedEnvNames.isReserved(it) }
    if (reservedOverrides.isNotEmpty()) {
        throw QueryParamValidationException(
            "env overrides reserved names (Homer / host-controlled): " +
                reservedOverrides.joinToString(", "),
        )
    }
    respondStartedUnderRootSpan(call, telemetryService, agent, "agent_debug_request", maintenanceGate) { context, span ->
        // Manual recovery is not gated by `afterFailure`; `minInterval` still holds. See ADR-0051.
        val mergedEnvAgent = agent.copy(env = agent.env + req.env)
        val debugAgent = mergedEnvAgent.withoutFailureBackoff()
        val resolvedQueryParams = resolveDebugParams(agentInputResolver, debugAgent, req)
        // Stamped before `execute`, so the run's own steps and generations inherit both.
        span.stampLangfuseTrace(
            agent,
            listOf("debug") + langfuseTraceTags(agent, resolvedQueryParams, agent.secretEnvNames),
        )
        // Rendered from the merged-env agent with its cooldown intact, as retry and resume do:
        // `withoutFailureBackoff` would drop an afterFailure-only cooldown and render a null key (ADR-0106).
        val cooldownKey = mergedEnvAgent.renderCooldownKey(resolvedQueryParams)
        val result =
            processExecutor.execute(
                processFactory.create(
                    debugAgent,
                    resolvedQueryParams,
                    context,
                    commandIndices = listOf(req.commandIndex),
                    owner = call.principalName(),
                    cooldownKey = cooldownKey,
                ),
                context,
            )
        result.processId to
            startedRunAttributes(result.processId, serverId, agent.name, "debug") {
                put("debug.command_index", req.commandIndex.toLong())
            }
    }
}

/**
 * The debug request's inputs, resolved through the same extraction and validation as a live run, from this
 * request rather than storage, so no historical value could have been secret under an older definition (ADR-0052).
 */
private fun resolveDebugParams(
    agentInputResolver: AgentInputResolver,
    agent: Agent,
    req: DebugAgentRequest,
): Map<String, String> {
    val builder = ParametersBuilder()
    req.queryParams.forEach { (k, v) -> builder.append(k, v) }
    return agentInputResolver.resolveDebug(
        queryParams = builder.build(),
        bodyJson = req.body,
        headers = req.headers,
        agent = agent,
        enforceRequired = false,
    )
}

internal suspend fun handleRetry(
    serverId: String,
    call: ApplicationCall,
    sourceProcess: ProcessInfo,
    agent: Agent,
    authorizer: Authorizer,
    processManager: ProcessManager,
    processExecutor: ProcessExecutor,
    processFactory: ProcessFactory,
    telemetryService: TelemetryService,
    costMeter: CostMeter,
    agentInputResolver: AgentInputResolver,
    maintenanceGate: MaintenanceModeService,
) {
    respondStartedUnderRootSpan(call, telemetryService, agent, "agent_retry_request", maintenanceGate) { context, span ->
        // Re-validate the stored inputs against the CURRENT agent definition (a fresh
        // request would); retry must not replay values the current input policy rejects.
        val retryParams = agentInputResolver.resolveRetryParams(agent, sourceProcess.inputParams)
        // A replay keeps the SOURCE run's secret flags, unioned with the current ones; this drives the
        // trace tags and the new row's own snapshot (ADR-0052).
        val retrySecretEnvNames = agent.secretEnvNames + sourceProcess.secretInputKeys.orEmpty()
        // Pre-flight cost cap check — same rationale as handleAgentExecution.
        if (agent.commands.any { it is Command.LlmCommand }) {
            costMeter.checkCaps(agent.name)
        }
        // The replaying caller may see the source run but not its flow root; the root is carried
        // only when they can (CWE-200, ADR-0058).
        val retryRootProcessId =
            processManager.visibleRootProcessId(call.principalName(), authorizer, sourceProcess.rootProcessId)
        // Union, not the current definition alone: an input flipped to `secret: false` since would be tagged in the clear.
        // Stamped before `execute` so the run's own spans inherit it (ADR-0052).
        span.stampLangfuseTrace(agent, listOf("retry") + langfuseTraceTags(agent, retryParams, retrySecretEnvNames))
        // A replay renders the cooldown key from the source run's subject and stored-secret snapshot, so a key
        // naming a then-secret input is refused before any row exists (ADR-0106).
        val retryCooldownKey = agent.renderCooldownKey(retryParams, retrySecretEnvNames)

        val result =
            processExecutor.execute(
                processFactory.create(
                    // Manual recovery is not gated by `afterFailure`; `minInterval` still holds. See ADR-0051.
                    agent.withoutFailureBackoff(),
                    retryParams,
                    context,
                    owner = call.principalName(),
                    extraSecretInputKeys = retrySecretEnvNames,
                    // A replay is still part of the flow that caused the source run.
                    parentProcessId = sourceProcess.parentProcessId,
                    rootProcessId = retryRootProcessId,
                    cooldownKey = retryCooldownKey,
                ),
                context,
            )

        result.processId to
            startedRunAttributes(
                result.processId,
                serverId,
                agent.name,
                "retry",
                parentProcessId = sourceProcess.parentProcessId,
                rootProcessId = retryRootProcessId,
            ) {
                put("retry.of_process_id", sourceProcess.id.toLong())
            }
    }
}

internal suspend fun handleResume(
    serverId: String,
    call: ApplicationCall,
    sourceProcess: ProcessInfo,
    agent: Agent,
    threadId: String,
    authorizer: Authorizer,
    processManager: ProcessManager,
    processExecutor: ProcessExecutor,
    processFactory: ProcessFactory,
    telemetryService: TelemetryService,
    costMeter: CostMeter,
    agentInputResolver: AgentInputResolver,
    maintenanceGate: MaintenanceModeService,
) {
    respondStartedUnderRootSpan(call, telemetryService, agent, "agent_resume_request", maintenanceGate) { context, span ->
        // Re-validated like retry, plus the recover markers; a stored HMR_LG_RESUME survives the reserved-name
        // passthrough and wins over recover, replaying a segment that crashed mid-resume (ADR-0044).
        val resumeParams =
            agentInputResolver.resolveRetryParams(agent, sourceProcess.inputParams) +
                mapOf(
                    EnvConstants.LG_THREAD to threadId,
                    EnvConstants.LG_RECOVER to "1",
                )
        // Same reasoning as retry: these are the source run's stored inputs.
        val resumeSecretEnvNames = agent.secretEnvNames + sourceProcess.secretInputKeys.orEmpty()
        // Pre-flight cost cap check — same rationale as handleAgentExecution.
        if (agent.commands.any { it is Command.LlmCommand }) {
            costMeter.checkCaps(agent.name)
        }
        // Same root-visibility gate as retry: the resuming caller may see the source
        // run without being able to see the flow root it points at. See ADR-0058.
        val resumeRootProcessId =
            processManager.visibleRootProcessId(call.principalName(), authorizer, sourceProcess.rootProcessId)
        // Same as retry: the stored inputs carry the source run's secrecy, and the stamp has to
        // land before `execute` for the run's own spans to inherit it.
        span.stampLangfuseTrace(agent, listOf("resume") + langfuseTraceTags(agent, resumeParams, resumeSecretEnvNames))
        // As retry: the stored-secret snapshot refuses a key naming an input that was secret when stored (ADR-0106).
        val resumeCooldownKey = agent.renderCooldownKey(resumeParams, resumeSecretEnvNames)

        val result =
            processExecutor.execute(
                processFactory.create(
                    // Manual recovery is not gated by `afterFailure`; `minInterval` still holds. See ADR-0051.
                    agent.withoutFailureBackoff(),
                    resumeParams,
                    context,
                    owner = call.principalName(),
                    extraSecretInputKeys = resumeSecretEnvNames,
                    parentProcessId = sourceProcess.parentProcessId,
                    rootProcessId = resumeRootProcessId,
                    cooldownKey = resumeCooldownKey,
                ),
                context,
            )

        result.processId to
            startedRunAttributes(
                result.processId,
                serverId,
                agent.name,
                "resume",
                parentProcessId = sourceProcess.parentProcessId,
                rootProcessId = resumeRootProcessId,
            ) {
                put("resume.of_process_id", sourceProcess.id.toLong())
            }
    }
}

/**
 * The root span's attributes of a run a retry, resume or debug path starts itself: identity, start mode, the
 * source run's parent and flow root, and [extra]. One shared shape, so a key typo cannot stop matching a trace query.
 */
private fun startedRunAttributes(
    processId: Int,
    serverId: String,
    agentName: String,
    source: String,
    parentProcessId: Int? = null,
    rootProcessId: Int? = null,
    extra: AttributesBuilder.() -> Unit,
): Attributes =
    Attributes
        .builder()
        .put(SpanAttributes.PROCESS_ID, processId.toLong())
        .put(SpanAttributes.SERVER_ID, serverId)
        .put(SpanAttributes.AGENT_NAME, agentName)
        .put(SpanAttributes.REQUEST_SOURCE, source)
        .apply { parentProcessId?.let { put(SpanAttributes.PARENT_PROCESS_ID, it.toLong()) } }
        .apply { rootProcessId?.let { put(SpanAttributes.ROOT_PROCESS_ID, it.toLong()) } }
        .apply(extra)
        .build()

/**
 * Starts a run under a fresh root span [spanName] and answers its process id. [start] stamps the Langfuse trace and
 * returns the process id with the span's remaining attributes; a failure marks the span ERROR and propagates.
 * Applies the tracing gate and [maintenanceGate]'s parking itself, as [AgentRunStarter.start] does (ADR-0050, ADR-0092).
 */
@Suppress("SuspendFunSwallowedCancellation") // records the span error and rethrows
private suspend fun respondStartedUnderRootSpan(
    call: ApplicationCall,
    telemetryService: TelemetryService,
    agent: Agent,
    spanName: String,
    maintenanceGate: MaintenanceModeService,
    start: suspend (Context, Span) -> Pair<Int, Attributes>,
) {
    val span = telemetryService.tracer.startAgentRootSpan(agent, spanName)
    try {
        val (processId, attributes) =
            maintenanceGate.gated(call.principalName()) { start(Context.current().with(span), span) }
        span.setAllAttributes(attributes)
        span.setStatus(StatusCode.OK)
        call.respond(ExecuteResponse(processId = processId))
    } catch (e: Exception) {
        span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
        span.recordException(e)
        throw e
    } finally {
        span.end()
    }
}
