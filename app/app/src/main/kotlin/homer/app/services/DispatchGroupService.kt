package homer.app.services

import homer.app.exceptions.DispatchGroupAnswersTooLargeException
import homer.app.exceptions.DispatchGroupTooLargeException
import homer.app.exceptions.TooManyOpenQuestionsException
import homer.app.exceptions.TooManyPendingDispatchesException
import homer.app.exceptions.ValidationException
import homer.app.infra.AgentInputResolver
import homer.app.infra.validatePayloadParam
import homer.app.models.DispatchConfig
import homer.app.models.QuestionsConfig
import homer.app.services.QuestionsService.Question
import homer.app.services.persistence.DispatchRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Grouped ask-and-dispatch (ADR-0109): N questions registered as one dispatch, answered
 * independently by the operator, the target started once — with all N answers aggregated in
 * question order — after the last of them.
 *
 * The create path mirrors [DispatchService.create]'s gates; the fire path is
 * [aggregateAnswers], which [DispatchService.fire] consults before it claims the group.
 */
@OptIn(ExperimentalTime::class)
internal class DispatchGroupService(
    private val repository: DispatchRepository,
    private val questionsService: QuestionsService,
    agentsService: AgentsService,
    authorizer: Authorizer,
    agentInputResolver: AgentInputResolver,
    processManager: ProcessManager,
    private val broadcaster: ProcessEventBroadcaster,
    private val logger: Logger,
    private val config: DispatchConfig = DispatchConfig(),
    private val questionsConfig: QuestionsConfig = QuestionsConfig(),
    private val nowSec: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val targetGate = DispatchTargetGate(authorizer, agentsService, agentInputResolver, processManager, logger)

    /** The bound [create] enforces; the MCP tool and `homer-ask.sh` name it in their help. */
    val maxGroupSize: Int get() = config.maxGroupSize

    /** One question of the group being registered: its text and its optional answer options. */
    data class QuestionSpec(
        val text: String,
        val options: List<String> = emptyList(),
    )

    data class CreateResult(
        val dispatchId: Int,
        val questionIds: List<String>,
    )

    /**
     * Registers [questions] as one GROUP dispatch to [agent]. The size bounds are checked first,
     * before any other work and before any row exists, so a refused group leaves nothing behind
     * and a group is never observably partial. Every other gate is [DispatchService.create]'s:
     * live origin, unreserved `answerParam`, question caps, authorized and opted-in target,
     * satisfiable inputs, positive TTL. The group counts against the global PENDING cap and the
     * origin process's open-question cap — the bound on this call's own multiplication. See ADR-0109.
     */
    suspend fun create(
        originProcessId: Int,
        questions: List<QuestionSpec>,
        agent: String,
        answerParam: String,
        params: Map<String, String>,
        ttlSec: Int?,
    ): CreateResult {
        if (questions.isEmpty()) {
            throw ValidationException("A dispatch group needs at least one question", "questions")
        }
        if (questions.size > config.maxGroupSize) {
            throw DispatchGroupTooLargeException(config.maxGroupSize)
        }
        val registration = questionsService.liveRegistration(originProcessId)
        validatePayloadParam(answerParam, "answerParam", params)
        questions.forEach { questionsService.validateQuestionInput(it.text, it.options) }
        val effectiveTtl = ttlSec ?: config.defaultTtlSec
        val owner = targetGate.check(originProcessId, registration.agentName, agent, answerParam, params, effectiveTtl, "Dispatch group")
        val now = nowSec()
        val members = questions.map { targetGate.questionRow(originProcessId, registration.agentName, it.text, it.options, now) }
        val dispatch =
            targetGate.dispatchRow(
                // A sentinel, not a question id: a GROUP row names no single question, and
                // `dispatches.question_id` is uniquely indexed, so it can be neither empty nor
                // shared with a member. The ordered set is the member table. See ADR-0109.
                questionId = GROUP_QUESTION_ID_PREFIX + UUID.randomUUID(),
                agent = agent,
                answerParam = answerParam,
                params = params,
                originProcessId = originProcessId,
                originAgentName = registration.agentName,
                effectiveTtl = effectiveTtl,
                now = now,
                owner = owner,
                kind = DispatchKind.GROUP,
            )
        val dispatchId = insertGroup(members, dispatch)
        // Ids and counts only: the questions' text is workspace-controlled. §12 rule 7.
        logger.info(
            "Process {} registered dispatch group {} of {} question(s) -> agent '{}'",
            originProcessId,
            dispatchId,
            members.size,
            agent,
        )
        members.forEach { broadcaster.emit(ProcessEvent.QuestionAsked(originProcessId, it.id, registration.agentName)) }
        return CreateResult(dispatchId, members.map { it.id })
    }

    private suspend fun insertGroup(
        members: List<Question>,
        dispatch: Dispatch,
    ): Int =
        when (
            val outcome =
                repository.createGroup(members, dispatch, config.maxPending, questionsConfig.maxOpenPerProcess)
        ) {
            is DispatchRepository.GroupCreateOutcome.Created -> {
                outcome.dispatchId
            }

            DispatchRepository.GroupCreateOutcome.PendingCapReached -> {
                throw TooManyPendingDispatchesException(config.maxPending)
            }

            DispatchRepository.GroupCreateOutcome.OpenQuestionCapReached -> {
                throw TooManyOpenQuestionsException(dispatch.originProcessId, questionsConfig.maxOpenPerProcess)
            }
        }

    /**
     * The member answers as a JSON array in question order, or null while any member is OPEN or none exist.
     * Throws [DispatchGroupAnswersTooLargeException] over [MAX_ANSWER_BYTES]; the caller settles it FAILED. See ADR-0109.
     */
    suspend fun aggregateAnswers(dispatchId: Int): String? {
        val members = repository.groupMembers(dispatchId)
        if (members.isEmpty()) return null
        val answers = members.map { it.answer ?: return null }
        val payload = Json.encodeToString(stringListSerializer, answers)
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_ANSWER_BYTES) {
            throw DispatchGroupAnswersTooLargeException(MAX_ANSWER_BYTES)
        }
        return payload
    }

    companion object {
        /**
         * The prefix of a GROUP dispatch row's synthetic `question_id`. It can never collide
         * with a real question id, which is a bare UUID.
         */
        const val GROUP_QUESTION_ID_PREFIX = "group:"

        /**
         * Cap on the aggregated answers in UTF-8 bytes. Each answer is at most `questions.maxTextLength` characters,
         * but multi-byte text, JSON escapes or a raised `dispatch.maxGroupSize` can still reach it. See ADR-0109.
         */
        const val MAX_ANSWER_BYTES = 256 * 1024

        private val stringListSerializer = ListSerializer(String.serializer())
    }
}
