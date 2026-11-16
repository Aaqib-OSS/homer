package homer.app.services

import homer.app.SpanAttributes
import homer.app.exceptions.InvocationCycleDetectedException
import homer.app.exceptions.InvocationDepthExceededException
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.infra.TelemetryService
import homer.app.infra.langfuseTraceTags
import homer.app.infra.stampLangfuseTrace
import homer.app.infra.startAgentRootSpan
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ProcessStatus
import homer.app.models.RunStartRequest
import homer.app.services.persistence.ProcessRecordReads
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context

/** Call-independent core of starting a run: span, cycle/depth checks, cost-cap preflight, create → launch. */
internal class AgentRunStarter(
    private val serverId: String,
    private val processExecutor: ProcessExecutor,
    private val processFactory: ProcessFactory,
    private val telemetryService: TelemetryService,
    private val costMeter: CostMeter,
    private val maxInvocationDepth: Int,
    private val cooldownGate: CooldownGate,
    // Admission gate above the claim queue: every start source queues here. See ADR-0092.
    private val maintenanceGate: MaintenanceModeService,
    // Read for the parent row's status: a start whose server-derived parent is still `WORKING`
    // is part of admitted work and does not park at the gate. See ADR-0092.
    private val processReads: ProcessRecordReads,
) {
    /** Starts a run; returns the new process id. Ordering: cycle → depth → cost cap → input resolution. */
    @Suppress("SuspendFunSwallowedCancellation") // records the span error and rethrows
    suspend fun start(
        request: RunStartRequest,
        extraAttributes: Attributes = Attributes.empty(),
        // Replay of stored inputs must honour the target's secret flags in force then. See ADR-0052.
        extraSecretInputKeys: Set<String> = emptySet(),
        // Set only where the server derived the parent from a run it knows is live; a
        // caller-supplied parent link leaves it false. See ADR-0092.
        serverDerivedParent: Boolean = false,
    ): Int {
        val span = telemetryService.tracer.startAgentRootSpan(request.agent, "agent_request")

        try {
            val context = Context.current().with(span)
            val admitted = admittedByLiveParent(serverDerivedParent, request.parentProcessId)
            // Gate runs before preflight: a released start re-runs the checks and resolves
            // inputs at dequeue, not enqueue. See ADR-0092.
            val processId =
                maintenanceGate.gated(request.owner, admitted) {
                    startGated(
                        request = request,
                        span = span,
                        context = context,
                        admitted = admitted,
                        extraSecretInputKeys = extraSecretInputKeys,
                        extraAttributes = extraAttributes,
                    )
                }
            span.setStatus(StatusCode.OK)

            return processId
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }

    /** Preflight, input resolution, record creation and launch of one admitted start. */
    private suspend fun startGated(
        request: RunStartRequest,
        span: Span,
        context: Context,
        admitted: Boolean,
        extraSecretInputKeys: Set<String>,
        extraAttributes: Attributes,
    ): Int {
        val agent = request.agent
        val source = request.source
        // A keyed cooldown is checked after inputs resolve (its key is a template over them);
        // a keyless one is refused by `checkPreflight`. See ADR-0106.
        val keyedCooldown = agent.cooldown?.key != null
        checkPreflight(agent, request.invocationChain, withCooldown = !keyedCooldown)
        val newChain = request.invocationChain + agent.name
        val queryParamsEnv = request.resolveInputs()
        // Rendered before `create`: an unusable key fails with no row to clean up, and one
        // value goes to both the gate and the row. See ADR-0106.
        val cooldownKey = if (keyedCooldown) agent.renderCooldownKey(queryParamsEnv, extraSecretInputKeys) else null
        if (keyedCooldown) cooldownGate.check(agent, cooldownKey)
        // Current flags for a fresh run, unioned with a replay's snapshot.
        val secretEnvNames = agent.secretEnvNames + extraSecretInputKeys
        val traceTags = listOf(source) + langfuseTraceTags(agent, queryParamsEnv, secretEnvNames)
        span.stampLangfuseTrace(agent, traceTags)

        val result =
            processExecutor.execute(
                processFactory.create(
                    agent = agent,
                    queryParams = queryParamsEnv,
                    parentContext = context,
                    invocationChain = newChain,
                    ancestorLockKeys = request.ancestorLockKeys,
                    owner = request.owner,
                    extraSecretInputKeys = secretEnvNames,
                    parentProcessId = request.parentProcessId,
                    rootProcessId = request.rootProcessId,
                    cooldownKey = cooldownKey,
                ),
                context,
            )

        span.setAllAttributes(
            runAttributes(
                result.processId,
                agent,
                source,
                request.parentProcessId,
                request.rootProcessId,
                admitted,
                extraAttributes,
            ),
        )
        return result.processId
    }

    // Reads the parent's own row, never the caller's link; a false flag short-circuits before
    // the read, so naming a parent admits and probes nothing. See ADR-0092, ADR-0102.
    private suspend fun admittedByLiveParent(
        serverDerivedParent: Boolean,
        parentProcessId: Int?,
    ): Boolean =
        serverDerivedParent &&
            parentProcessId != null &&
            (maintenanceGate.isEnabled() || maintenanceGate.isDraining()) &&
            processReads.getProcessStatus(parentProcessId) == ProcessStatus.WORKING

    // Request-boundary refusals in order: cycle, depth, cost cap, cooldown. [withCooldown]
    // false leaves the cooldown check to the caller, once the key is rendered. See ADR-0106.
    private suspend fun checkPreflight(
        agent: Agent,
        invocationChain: List<String>,
        withCooldown: Boolean = true,
    ) {
        if (agent.name in invocationChain) {
            throw InvocationCycleDetectedException(agent.name, invocationChain)
        }
        if (invocationChain.size + 1 > maxInvocationDepth) {
            throw InvocationDepthExceededException(agent.name, maxInvocationDepth, invocationChain)
        }
        // Cost cap is checked at the request boundary so a refusal reaches the caller; agents
        // with no LLM command cannot accrue spend and are skipped. See ADR-0007.
        if (agent.commands.any { it is Command.LlmCommand }) {
            costMeter.checkCaps(agent.name)
        }
        if (withCooldown) cooldownGate.check(agent)
    }

    /** The `agent_request` span's attributes for the started run [processId]. */
    private fun runAttributes(
        processId: Int,
        agent: Agent,
        source: String,
        parentProcessId: Int?,
        rootProcessId: Int?,
        admitted: Boolean,
        extraAttributes: Attributes,
    ): Attributes =
        Attributes
            .builder()
            .put(SpanAttributes.PROCESS_ID, processId.toLong())
            .put(SpanAttributes.SERVER_ID, serverId)
            .put(SpanAttributes.AGENT_NAME, agent.name)
            .put(SpanAttributes.REQUEST_SOURCE, source)
            .apply { parentProcessId?.let { put(SpanAttributes.PARENT_PROCESS_ID, it.toLong()) } }
            .apply { rootProcessId?.let { put(SpanAttributes.ROOT_PROCESS_ID, it.toLong()) } }
            // Boolean only, never a parent's inputs: true only for a start admitted while the mode was on.
            .put("maintenance.bypassed", admitted)
            .putAll(extraAttributes)
            .build()
}

// Flow-root id a write path may carry: [candidate] only when the root row exists and [principal]
// can see it under the read union (ADR-0037); a missing root fails closed (null). §12.
internal suspend fun ProcessManager.visibleRootProcessId(
    principal: String?,
    authorizer: Authorizer,
    candidate: Int?,
): Int? =
    candidate?.let { rootId ->
        getProcessInfo(rootId)
            ?.takeIf { authorizer.canSee(principal, it.owner, it.agentName) }
            ?.id
    }

// The origin's own root when it has one, else the origin itself; a derived root the principal
// cannot see is dropped, never replaced by the origin's id. See ADR-0058. §12.
internal suspend fun ProcessManager.visibleOriginRootProcessId(
    originProcessId: Int,
    principal: String?,
    authorizer: Authorizer,
): Int? {
    if (originProcessId <= 0) return null
    val origin = getProcessInfo(originProcessId) ?: return null
    val derived = origin.rootProcessId ?: return origin.id
    return visibleRootProcessId(principal, authorizer, derived)
}
