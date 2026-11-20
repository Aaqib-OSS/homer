package homer.app.runners.queue

import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/**
 * One run waiting for a session (ADR-0066): PENDING → CLAIMED by a consumer, or → CANCELLED/EXPIRED when the run stops
 * waiting; the CAS settles that race. A consumer that claims it must eventually call [complete] or [fail].
 */
class ClaimRequest internal constructor(
    val params: RunnerSessionParams,
    val filter: RunnerFilter,
    val enqueuedAtMs: Long,
    /** Null for a run whose agent declares no `runnerAffinity`; such a request routes exactly as before. */
    val affinity: ClaimAffinity? = null,
) {
    internal enum class State { PENDING, CLAIMED, CANCELLED, EXPIRED }

    internal val deferred = CompletableDeferred<RunnerSession>()
    private val state = AtomicReference(State.PENDING)

    val isPending: Boolean get() = state.get() == State.PENDING

    internal fun tryClaim(): Boolean = state.compareAndSet(State.PENDING, State.CLAIMED)

    internal fun tryFinish(to: State): Boolean = state.compareAndSet(State.PENDING, to)

    /**
     * Hands the opened session to the waiting run. Returns false when the run
     * stopped waiting first; the caller then owns [session] and must close it.
     */
    fun complete(session: RunnerSession): Boolean = deferred.complete(session)

    /** Fails the waiting run (the consumer could not open a session). */
    fun fail(cause: Throwable) {
        deferred.completeExceptionally(cause)
    }
}

/**
 * A run's affinity in the queue (ADR-0099): the key it records a claim under, from Homer-stamped run state only, the
 * alias-qualified instance it prefers (resolved at submit), and how long that preference orders consumers.
 */
class ClaimAffinity(
    val rootProcessId: Int,
    val group: String,
    /** The recorded instance, or null when the key is unknown — then this request only records. */
    val preferredInstance: String?,
    private val waitMs: Long,
) {
    /** When the preference stops ordering consumers: [enqueuedAtMs] plus the bounded wait. */
    fun deadlineMs(enqueuedAtMs: Long): Long = enqueuedAtMs + waitMs

    /** Whether the preference still orders consumers at [nowMs]; a request without one never orders anything. */
    fun isActive(
        enqueuedAtMs: Long,
        nowMs: Long,
    ): Boolean = preferredInstance != null && nowMs - enqueuedAtMs < waitMs

    /**
     * Whether the preference ordered consumers for [timeoutMs]'s whole length: its wait was
     * clamped to the claim TTL, so it never expired before the claim did — matching runners
     * passed the run over until the very end, and the expiry's cause is the preference.
     */
    fun heldWholeWait(timeoutMs: Long): Boolean = preferredInstance != null && waitMs >= timeoutMs
}
