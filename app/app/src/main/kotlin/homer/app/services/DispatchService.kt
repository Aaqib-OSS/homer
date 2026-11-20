package homer.app.services

import homer.app.exceptions.AgentNotDispatchableException
import homer.app.exceptions.DispatchAgentNotFoundException
import homer.app.exceptions.DispatchGroupAnswersTooLargeException
import homer.app.exceptions.DispatchNotFoundException
import homer.app.exceptions.DispatchNotPendingException
import homer.app.exceptions.HomerException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.ServerDrainingException
import homer.app.exceptions.TooManyPendingDispatchesException
import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.infra.reservedControlParams
import homer.app.infra.validatePayloadParam
import homer.app.models.Agent
import homer.app.models.DispatchConfig
import homer.app.models.RunStartRequest
import homer.app.services.persistence.DispatchRepository
import homer.app.services.persistence.RecordPage
import io.opentelemetry.api.common.Attributes
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Status names used as the `dispatches.status` column value. */
enum class DispatchStatus { PENDING, DISPATCHING, DISPATCHED, FAILED, CANCELLED }

/**
 * A [SINGLE] dispatch is one question and one dispatch row. A [GROUP] dispatch is N questions answered
 * independently; the target fires once after the last, with all N answers in question order. See ADR-0109.
 */
enum class DispatchKind { SINGLE, GROUP }

/**
 * A registered ask-and-dispatch: an operator question plus the agent to start
 * (with [params] and the answer under [answerParam]) once it is answered. The
 * dispatch and its question outlive the asking process. See ADR-0021.
 */
data class Dispatch(
    val id: Int,
    val questionId: String,
    val status: DispatchStatus,
    val agentName: String,
    val answerParam: String,
    val params: Map<String, String>,
    val originProcessId: Int,
    val originAgentName: String,
    val expiresAt: Long?,
    val createdAt: Long,
    val dispatchedAt: Long? = null,
    val dispatchedProcessId: Int? = null,
    val error: String? = null,
    // The origin process's owner, copied in at registration (live, since the origin
    // may have finished by fire time) so the fired run inherits it. See ADR-0038.
    val owner: String? = null,
    // Target agent's `secret: true` envNames at registration, so a definition edited before the
    // fire cannot expose a stored value; a pre-v16 row's null falls back to the fire-time flags. See ADR-0052.
    val secretInputKeys: Set<String>? = null,
    // Target agent's `secret: true` paramNames at registration, matched against [params]'s keys
    // without the current definition; a pre-v17 row's null falls back to the envName check. See ADR-0052.
    val secretParamNames: Set<String>? = null,
    // A GROUP row's [questionId] is a sentinel and [questionIds] its ordered member set; a SINGLE
    // row's [questionIds] is empty. See ADR-0109.
    val kind: DispatchKind = DispatchKind.SINGLE,
    val questionIds: List<String> = emptyList(),
)

@OptIn(ExperimentalTime::class)
internal class DispatchService(
    private val repository: DispatchRepository,
    private val questionsService: QuestionsService,
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    private val agentRunStarter: AgentRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val processManager: ProcessManager,
    private val broadcaster: ProcessEventBroadcaster,
    private val groupService: DispatchGroupService,
    private val logger: Logger,
    private val config: DispatchConfig = DispatchConfig(),
    private val nowSec: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val targetGate = DispatchTargetGate(authorizer, agentsService, agentInputResolver, processManager, logger)

    data class CreateResult(
        val dispatchId: Int,
        val questionId: String,
    )

    suspend fun create(
        originProcessId: Int,
        text: String,
        options: List<String>,
        agent: String,
        answerParam: String,
        params: Map<String, String>,
        ttlSec: Int?,
    ): CreateResult {
        val registration = questionsService.liveRegistration(originProcessId)
        validatePayloadParam(answerParam, "answerParam", params)
        questionsService.validateQuestionInput(text, options)
        val effectiveTtl = ttlSec ?: config.defaultTtlSec
        val owner = targetGate.check(originProcessId, registration.agentName, agent, answerParam, params, effectiveTtl, "Dispatch")
        val now = nowSec()
        val question = targetGate.questionRow(originProcessId, registration.agentName, text, options, now)
        val dispatch =
            targetGate.dispatchRow(
                questionId = question.id,
                agent = agent,
                answerParam = answerParam,
                params = params,
                originProcessId = originProcessId,
                originAgentName = registration.agentName,
                effectiveTtl = effectiveTtl,
                now = now,
                owner = owner,
            )
        val dispatchId =
            repository.create(question, dispatch, config.maxPending)
                ?: throw TooManyPendingDispatchesException(config.maxPending)
        logger.info("Process {} registered dispatch {} -> agent '{}'", originProcessId, dispatchId, agent)
        broadcaster.emit(ProcessEvent.QuestionAsked(originProcessId, question.id, registration.agentName))
        return CreateResult(dispatchId, question.id)
    }

    /**
     * Fires the dispatch of the answered [questionId]: claims it PENDING→DISPATCHING before the start, so
     * a crash cannot fire it twice. Null when there is nothing to fire or the claim is lost; after the
     * claim any failure settles it FAILED, except a drain refusal ([startTarget]). See ADR-0021.
     */
    suspend fun fire(
        questionId: String,
        answer: String,
    ): Dispatch? {
        val dispatch = repository.findByQuestionId(questionId) ?: return null
        if (dispatch.status != DispatchStatus.PENDING) return null
        // A member question of a group resolves to the group row, so a group is fired from the
        // same answer route as a single dispatch, with the group's own gate in front of the claim.
        if (dispatch.kind == DispatchKind.GROUP) return fireGroup(dispatch)
        return claimAndStart(dispatch, answer)
    }

    /** Claims [dispatch], re-checks its target, and starts it with [payload]; null if the claim was lost. */
    private suspend fun claimAndStart(
        dispatch: Dispatch,
        payload: String,
    ): Dispatch? {
        if (!repository.claim(dispatch.id)) return null
        val now = nowSec()
        val agent =
            agentsService.agents[dispatch.agentName]
                ?: return markFailed(dispatch, "Target agent '${dispatch.agentName}' not found", now)
        val denial = targetGate.hopDenial(dispatch, agent)
        if (denial != null) return markFailed(dispatch, denial, now)
        return startTarget(dispatch, agent, payload, now)
    }

    /**
     * Fires a GROUP dispatch once every member is answered, null while any is OPEN; the claim CAS is
     * what starts it exactly once. An oversized aggregate settles it FAILED here, since a throw would
     * abort boot reconciliation. See ADR-0109.
     */
    private suspend fun fireGroup(dispatch: Dispatch): Dispatch? {
        val payload =
            try {
                groupService.aggregateAnswers(dispatch.id) ?: return null
            } catch (e: DispatchGroupAnswersTooLargeException) {
                if (!repository.claim(dispatch.id)) return null
                return markFailed(dispatch, e.message ?: "Group answers exceed the payload bound", nowSec())
            }
        return claimAndStart(dispatch, payload)
    }

    private suspend fun markFailed(
        dispatch: Dispatch,
        msg: String,
        now: Long,
    ): Dispatch {
        repository.markFailed(dispatch.id, msg, now)
        return dispatch.copy(status = DispatchStatus.FAILED, error = msg, dispatchedAt = now)
    }

    /**
     * Starts the claimed [dispatch]'s target with the operator's [answer]; any failure settles it
     * FAILED, except a drain refusal, which [deferForDrain] releases for [refirePendingAnswered]. ADR-0102.
     */
    private suspend fun startTarget(
        dispatch: Dispatch,
        agent: Agent,
        answer: String,
        now: Long,
    ): Dispatch =
        try {
            val processId = startDispatchRun(dispatch, agent, answer)
            repository.markDispatched(dispatch.id, processId, now)
            logger.info("Dispatch {} fired -> run {}", dispatch.id, processId)
            dispatch.copy(status = DispatchStatus.DISPATCHED, dispatchedProcessId = processId, dispatchedAt = now)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ServerDrainingException) {
            deferForDrain(dispatch, e)
        } catch (e: HomerException) {
            repository.markFailed(dispatch.id, e.message ?: "Dispatch failed", now)
            logger.warn("Dispatch {} failed to fire: {}", dispatch.id, e.message)
            dispatch.copy(status = DispatchStatus.FAILED, error = e.message ?: "Dispatch failed", dispatchedAt = now)
        } catch (e: Exception) {
            val msg = e.message ?: "Unexpected dispatch failure"
            repository.markFailed(dispatch.id, msg, now)
            logger.warn("Dispatch {} failed to fire (unexpected): {}", dispatch.id, msg, e)
            dispatch.copy(status = DispatchStatus.FAILED, error = msg, dispatchedAt = now)
        }

    /**
     * Releases a drain-refused claim back to PENDING with the answer intact, for the next boot's
     * [refirePendingAnswered]; FAILED would consume the answer of a run that never started. ADR-0102.
     */
    private suspend fun deferForDrain(
        dispatch: Dispatch,
        e: ServerDrainingException,
    ): Dispatch {
        repository.releaseForRetry(dispatch.id)
        logger.info(
            "Dispatch {} deferred: the server is draining ({}); the answer stays pending for the next start-up",
            dispatch.id,
            e.message,
        )
        return dispatch.copy(status = DispatchStatus.PENDING)
    }

    /**
     * Starts the run with the origin run's stored inputs under the dispatch's params and the answer
     * (ADR-0101). Called inside [startTarget]'s try: after the claim, a failed read must settle FAILED.
     */
    private suspend fun startDispatchRun(
        dispatch: Dispatch,
        agent: Agent,
        answer: String,
    ): Int {
        val inherited =
            inheritedOriginInputs(
                { processManager.getProcessInfo(dispatch.originProcessId) },
                dispatch.originAgentName,
                dispatch.owner,
                agent,
                "Dispatch",
                logger,
            )
        val inputParams = inherited.params + dispatch.params + (dispatch.answerParam to answer)
        return agentRunStarter.start(
            RunStartRequest(
                agent = agent,
                invocationChain = emptyList(),
                source = "dispatch",
                owner = dispatch.owner,
                parentProcessId = dispatch.originProcessId,
                // The root is gated on the dispatch owner's visibility: a foreign flow-root id reaches no
                // row, env or span (CWE-200, ADR-0058).
                rootProcessId =
                    processManager.visibleOriginRootProcessId(
                        dispatch.originProcessId,
                        dispatch.owner,
                        authorizer,
                    ),
                resolveInputs = {
                    // Stored inherited values are already transformed — validate, never re-transform.
                    agentInputResolver.resolveFromParams(
                        inputParams,
                        agent,
                        skipTransformFor = inherited.storedParamNames(dispatch.params.keys + dispatch.answerParam),
                    ) + reservedControlParams(inputParams)
                },
            ),
            extraSecretInputKeys = dispatch.secretInputKeys.orEmpty() + inherited.secretInputKeys,
            // The origin asked for this dispatch: while it is `WORKING` the start is admitted and
            // skips the maintenance queue; an ended origin parks. ADR-0092.
            serverDerivedParent = true,
        )
    }

    /**
     * Starts [agent] now, with no question or dispatch row, on a fresh invocation chain, and returns its
     * process id. The origin must be live ([QuestionsClosedForProcessException]) and the target opted in
     * via `dispatch.enabled`; the referer is recorded on the run's span. See ADR-0022.
     */
    suspend fun dispatchNow(
        originProcessId: Int,
        agent: String,
        params: Map<String, String>,
    ): Int {
        val registration = questionsService.liveRegistration(originProcessId)
        val owner = processManager.getProcessInfo(originProcessId)?.owner
        val target = authorizer.dispatchableTarget(agentsService, owner, registration.agentName, agent)
        val refererAttributes =
            Attributes
                .builder()
                .put("referer.process.id", originProcessId.toLong())
                .put("referer.agent.name", registration.agentName)
                .build()
        val processId =
            agentRunStarter.start(
                RunStartRequest(
                    agent = target,
                    invocationChain = emptyList(),
                    source = "dispatch",
                    owner = owner,
                    parentProcessId = originProcessId,
                    // Same root-visibility gate as fire: the run carries the owner's grant (CWE-200, ADR-0058).
                    rootProcessId = processManager.visibleOriginRootProcessId(originProcessId, owner, authorizer),
                    resolveInputs = { agentInputResolver.resolveFromParams(params, target) },
                ),
                extraAttributes = refererAttributes,
                // The origin, proved live above, makes this dispatch: while it is `WORKING` the
                // start is admitted and skips the maintenance queue. ADR-0092.
                serverDerivedParent = true,
            )
        logger.info(
            "Process {} ('{}') dispatched run {} -> agent '{}'",
            originProcessId,
            registration.agentName,
            processId,
            agent,
        )
        return processId
    }

    /**
     * [dispatchNow]'s checks without the start: live origin, dispatchable and authorized target,
     * resolvable [params]. A multi-run request checks every entry before it starts any. See ADR-0026.
     */
    suspend fun validateDispatchNow(
        originProcessId: Int,
        agent: String,
        params: Map<String, String>,
    ) {
        val registration = questionsService.liveRegistration(originProcessId)
        val owner = processManager.getProcessInfo(originProcessId)?.owner
        val target = authorizer.dispatchableTarget(agentsService, owner, registration.agentName, agent)
        agentInputResolver.resolveFromParams(params, target)
    }

    suspend fun get(id: Int): Dispatch? = repository.get(id)

    suspend fun page(
        status: DispatchStatus?,
        agentName: String?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
    ): RecordPage<Dispatch> = repository.page(status, agentName, visibleOwner, visibleAgents, offset, limit)

    suspend fun findByQuestionIds(ids: Collection<String>): Map<String, Dispatch> = repository.findByQuestionIds(ids)

    suspend fun cancel(id: Int) {
        // The pre-read only tells 404 from 409; cancelWithQuestion re-checks PENDING atomically,
        // so a claim racing this read still resolves correctly.
        val existing = repository.get(id) ?: throw DispatchNotFoundException(id)
        if (existing.status != DispatchStatus.PENDING) {
            throw DispatchNotPendingException(id, existing.status.name)
        }
        val cancelled =
            repository.cancelWithQuestion(id)
                ?: throw DispatchNotPendingException(id, repository.get(id)?.status?.name ?: existing.status.name)
        // One event per question the dispatch owned, so a cancelled group's members each leave
        // the questions inbox rather than only the sentinel. See ADR-0109.
        questionIdsOf(existing).forEach {
            broadcaster.emit(ProcessEvent.QuestionExpired(cancelled.originProcessId, it))
        }
    }

    /** The questions a dispatch owns: a GROUP's ordered member set, or a SINGLE's one question. */
    private fun questionIdsOf(dispatch: Dispatch): List<String> =
        if (dispatch.kind == DispatchKind.GROUP) dispatch.questionIds else listOf(dispatch.questionId)

    suspend fun sweepExpired() {
        val cancelled = repository.expirePending(nowSec())
        cancelled.forEach { dispatch ->
            questionIdsOf(dispatch).forEach {
                broadcaster.emit(ProcessEvent.QuestionExpired(dispatch.originProcessId, it))
            }
        }
        if (cancelled.isNotEmpty()) {
            logger.info("Swept {} expired dispatch(es)", cancelled.size)
        }
    }

    /** Deletes settled dispatches older than `settledRetentionSec`, with their questions. See ADR-0021. */
    suspend fun pruneSettled() {
        val pruned = repository.pruneSettled(nowSec() - config.settledRetentionSec)
        if (pruned > 0) {
            logger.info("Pruned {} settled dispatch(es) older than {} s", pruned, config.settledRetentionSec)
        }
    }

    /** Fails dispatches left DISPATCHING by a restart (avoids duplicate re-fire). Boot only. */
    suspend fun failOrphanedDispatching() {
        val orphaned = repository.failOrphanedDispatching(nowSec())
        if (orphaned.isNotEmpty()) {
            logger.warn("Failed {} dispatch(es) interrupted mid-dispatch by restart", orphaned.size)
        }
    }

    suspend fun refirePendingAnswered() {
        for ((dispatch, answer) in repository.listPendingAnswered()) {
            // A GROUP row's own `answer` is empty by construction: fire() re-resolves its sentinel
            // and aggregates the member answers itself. See ADR-0109.
            if (dispatch.kind != DispatchKind.GROUP && answer.isBlank()) continue
            logger.info("Re-firing dispatch {} answered before restart", dispatch.id)
            fire(dispatch.questionId, answer)
        }
    }
}
