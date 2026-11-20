package homer.app.services

import homer.app.exceptions.QuestionNotFoundException
import homer.app.exceptions.QuestionNotOpenException
import homer.app.exceptions.QuestionsClosedForProcessException
import homer.app.exceptions.TooManyConcurrentWaitsException
import homer.app.exceptions.TooManyOpenQuestionsException
import homer.app.exceptions.ValidationException
import homer.app.executors.QuestionExpiry
import homer.app.executors.QuestionRegistration
import homer.app.infra.SecretRedactor
import homer.app.infra.matchesTokenConstantTime
import homer.app.models.QuestionsConfig
import homer.app.services.persistence.QuestionsRepository
import homer.app.services.persistence.RecordPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Operator questions asked by running commands and answered over the API, durable via [QuestionsRepository] (ADR-0020).
 * The callback tokens in [registrations], [completions] and [activeWaits] are never persisted, so a restart drops the
 * tokens and expires open questions. See ADR-0019.
 */
@OptIn(ExperimentalTime::class)
class QuestionsService(
    private val repository: QuestionsRepository,
    private val logger: Logger,
    private val broadcaster: ProcessEventBroadcaster,
    private val config: QuestionsConfig = QuestionsConfig(),
) : QuestionExpiry,
    QuestionRegistration {
    // Serializes registration changes against ask's checks, so no question stays OPEN in a closed registration, and guards
    // the activeWaits counts. Never held across a database write, nor taken by `resolveToken`. See ADR-0019.
    private val mutex = Mutex()
    private val registrations = ConcurrentHashMap<Int, ProcessRegistration>()

    // SHA-256 of each registered token → its process id, so a callback or proxied request resolves in
    // one lookup, not a scan. Changed only under [mutex], next to [registrations]. See ADR-0110.
    private val processByTokenDigest = ConcurrentHashMap<String, Int>()
    private val activeWaits = mutableMapOf<Int, Int>()
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Deferreds still held, for tests that pin cleanup on the paths that never return a question. */
    internal val heldCompletions: Int get() = completions.size
    private val secureRandom = SecureRandom()

    /**
     * Mints and stores a callback token for [processId], replacing any previous one. [secretRedactor] receives the token
     * and returns the run's secret screen, its own token included; the registration is never observable without it.
     */
    override suspend fun registerProcess(
        processId: Int,
        agentName: String,
        secretRedactor: (callbackToken: String) -> SecretRedactor,
    ): String {
        val token = generateToken()
        val redactor = secretRedactor(token)
        mutex.withLock {
            registrations
                .put(processId, ProcessRegistration(agentName = agentName, token = token, secretRedactor = redactor))
                ?.let { processByTokenDigest.remove(tokenDigest(it.token)) }
            processByTokenDigest[tokenDigest(token)] = processId
        }
        return token
    }

    /**
     * Resolves a callback token to its process id, or null. The lookup is by the token's SHA-256, so its timing can
     * reveal at most a digest prefix; the final comparison is constant-time. See ADR-0110.
     */
    fun resolveToken(token: String): Int? =
        processByTokenDigest[tokenDigest(token)]?.takeIf { processId ->
            registrations[processId]?.let { matchesTokenConstantTime(it.token, token) } == true
        }

    /**
     * Validates question text + options against the configured limits. Shared by
     * `ask` and the ask-and-dispatch create path so both enforce the same caps.
     */
    fun validateQuestionInput(
        text: String,
        options: List<String>,
    ) {
        if (text.isBlank()) throw ValidationException("Question text must not be blank", "text")
        if (text.length >
            config.maxTextLength
        ) {
            throw ValidationException("Question text exceeds ${config.maxTextLength} characters", "text")
        }
        if (options.size > config.maxOptions) throw ValidationException("At most ${config.maxOptions} options are allowed", "options")
        if (options.any { it.isBlank() }) throw ValidationException("Options must not be blank", "options")
        if (options.any {
                it.length > config.maxTextLength
            }
        ) {
            throw ValidationException("Options exceed ${config.maxTextLength} characters", "options")
        }
        if (options.size != options.distinct().size) throw ValidationException("Options must not contain duplicates", "options")
    }

    suspend fun ask(
        processId: Int,
        text: String,
        options: List<String> = emptyList(),
    ): Question {
        validateQuestionInput(text, options)
        val question: Question
        mutex.withLock {
            val registration = registrations[processId]
            if (registration == null || registration.closed) {
                throw QuestionsClosedForProcessException(processId)
            }
            question =
                Question(
                    id = UUID.randomUUID().toString(),
                    processId = processId,
                    agentName = registration.agentName,
                    text = text,
                    options = options,
                    status = QuestionStatus.OPEN,
                    createdAt = Clock.System.now().epochSeconds,
                )
            completions[question.id] = CompletableDeferred()
        }
        // Outside the mutex a close or purge can land; the re-check below catches it, and NonCancellable
        // makes a committed insert reach it. See ADR-0019.
        var outcome: AskOutcome? = null
        try {
            outcome =
                withContext(NonCancellable) {
                    if (!repository.tryInsert(question, config.maxOpenPerProcess)) return@withContext AskOutcome.CAP_REACHED
                    val registration = mutex.withLock { registrations[processId] }
                    when {
                        registration == null -> repository.delete(question.id)
                        registration.closed -> repository.expireIfOpen(question.id)
                        else -> return@withContext AskOutcome.OPEN
                    }
                    AskOutcome.CLOSED
                }
        } finally {
            // Every outcome but OPEN, a throwing write included, releases the question's waiters.
            if (outcome != AskOutcome.OPEN) completions.remove(question.id)?.complete(Unit)
        }
        when (outcome) {
            AskOutcome.OPEN -> {}

            AskOutcome.CAP_REACHED -> {
                throw TooManyOpenQuestionsException(processId, config.maxOpenPerProcess)
            }

            AskOutcome.CLOSED -> {
                throw QuestionsClosedForProcessException(processId)
            }
        }
        logger.info("Process {} asked question {}", processId, question.id)
        broadcaster.emit(ProcessEvent.QuestionAsked(processId, question.id, question.agentName))
        return question
    }

    /** Answers an OPEN question; a blank answer or one over `maxTextLength` characters is refused before any write. */
    suspend fun answer(
        id: String,
        answer: String,
    ): Question {
        if (answer.isBlank()) {
            throw ValidationException("Answer must not be blank", "answer")
        }
        if (answer.length > config.maxTextLength) {
            throw ValidationException("Answer exceeds ${config.maxTextLength} characters", "answer")
        }
        when (val result = repository.markAnswered(id, answer, Clock.System.now().epochSeconds)) {
            is QuestionsRepository.AnswerResult.NotFound -> {
                throw QuestionNotFoundException(id)
            }

            is QuestionsRepository.AnswerResult.NotOpen -> {
                throw QuestionNotOpenException(id, result.status.name)
            }

            is QuestionsRepository.AnswerResult.Answered -> {
                completions.remove(id)?.complete(Unit)
                logger.info("Question {} (process {}) answered", id, result.question.processId)
                broadcaster.emit(ProcessEvent.QuestionAnswered(result.question.processId, id))
                return result.question
            }
        }
    }

    suspend fun get(id: String): Question? = repository.get(id)

    /**
     * Waits up to [waitSec] seconds (clamped to `0..maxWaitSec`) for the question to leave OPEN and returns its latest
     * snapshot, still OPEN on timeout so a caller can wait again. Null for an unknown id.
     */
    suspend fun awaitAnswer(
        id: String,
        waitSec: Int,
    ): Question? {
        val snapshot = repository.get(id) ?: return null
        if (snapshot.status != QuestionStatus.OPEN) return snapshot
        val clampedSec = waitSec.coerceIn(0, config.maxWaitSec)
        if (clampedSec <= 0) return repository.get(id)

        // Each held wait pins a connection for up to maxWaitSec, so the count per process is capped (ADR-0047 rule 4).
        mutex.withLock {
            val held = activeWaits.getOrDefault(snapshot.processId, 0)
            if (held >= config.maxConcurrentWaitsPerProcess) {
                throw TooManyConcurrentWaitsException(snapshot.processId, config.maxConcurrentWaitsPerProcess)
            }
            activeWaits[snapshot.processId] = held + 1
        }
        try {
            val completion = completions.getOrPut(id) { CompletableDeferred() }
            // Re-read after registering the completion: an answer or expiry since the snapshot completed
            // another deferred or none, so the wait would otherwise sit out its timeout.
            val recheck = repository.get(id)
            if (recheck != null && recheck.status == QuestionStatus.OPEN) {
                withTimeoutOrNull(clampedSec.seconds) { completion.await() }
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    val held = activeWaits.getOrDefault(snapshot.processId, 0)
                    if (held <= 1) {
                        activeWaits.remove(snapshot.processId)
                    } else {
                        activeWaits[snapshot.processId] = held - 1
                    }
                }
            }
        }
        return repository.get(id)
    }

    /** One page of the questions the caller can see: [QuestionsRepository.page]. */
    suspend fun page(
        status: QuestionStatus?,
        processId: Int?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
    ): RecordPage<Question> = repository.page(status, processId, visibleOwner, visibleAgents, offset, limit)

    /** Count of OPEN questions per process id (processes without one are absent). */
    suspend fun openCountByProcess(): Map<Int, Int> = repository.openCountByProcess()

    /**
     * Closes the process's registration and expires its OPEN questions, resuming every waiter; called on every terminal
     * transition and at startup reconciliation. The registration stays until [removeForProcess], so a late call gets 409.
     */
    override suspend fun expireForProcess(processId: Int) {
        val expired: List<Question>
        mutex.withLock {
            // A closed registration answers 409 before any guard runs: drop the needles.
            registrations[processId]?.let { registrations[processId] = it.copy(closed = true, secretRedactor = SecretRedactor.NONE) }
        }
        expired = repository.expireForProcess(processId)
        // Remove as we resume so the map tracks only OPEN questions, not every
        // question ever asked (a held waiter keeps its own deferred reference).
        expired.forEach { completions.remove(it.id)?.complete(Unit) }
        if (expired.isNotEmpty()) {
            logger.info("Expired {} open question(s) for terminal process {}", expired.size, processId)
        }
        expired.forEach { broadcaster.emit(ProcessEvent.QuestionExpired(processId, it.id)) }
    }

    /** The asker's registration, for ask-and-dispatch create-time checks. Null if unknown. */
    fun registrationFor(processId: Int): Registration? =
        registrations[processId]?.let { Registration(it.agentName, it.closed, it.secretRedactor) }

    data class Registration(
        val agentName: String,
        val closed: Boolean,
        /** Screens runtime tag values against the run's secrets; [SecretRedactor.NONE] once closed. */
        val secretRedactor: SecretRedactor = SecretRedactor.NONE,
    )

    /** Drops the token registration and every question of a purged process. */
    suspend fun removeForProcess(processId: Int) {
        mutex.withLock { registrations.remove(processId)?.let { processByTokenDigest.remove(tokenDigest(it.token)) } }
        val removedIds = repository.removeForProcess(processId)
        removedIds.forEach { completions.remove(it)?.complete(Unit) }
    }

    private fun tokenDigest(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private enum class AskOutcome { OPEN, CAP_REACHED, CLOSED }

    private data class ProcessRegistration(
        val agentName: String,
        val token: String,
        val closed: Boolean = false,
        val secretRedactor: SecretRedactor = SecretRedactor.NONE,
    )

    data class Question(
        val id: String,
        val processId: Int,
        val agentName: String,
        val text: String,
        val options: List<String> = emptyList(),
        val status: QuestionStatus,
        val answer: String? = null,
        val createdAt: Long,
        val answeredAt: Long? = null,
        val dispatchBound: Boolean = false,
    )

    enum class QuestionStatus {
        OPEN,
        ANSWERED,
        EXPIRED,
    }

    companion object {
        private const val TOKEN_BYTES = 32
    }
}
