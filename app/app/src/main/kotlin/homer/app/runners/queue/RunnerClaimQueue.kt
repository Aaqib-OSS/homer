package homer.app.runners.queue

import homer.app.exceptions.ClaimQueueFullException
import homer.app.exceptions.RunnerClaimTimeoutException
import homer.app.models.RunnerAffinity
import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/**
 * The one queue every run passes through (ADR-0066): a consumer with a free permit takes the oldest request it matches,
 * and one claim TTL covers the whole wait. Bounded by [maxPending] (ADR-0047 rule 4). A new request wakes one parked
 * consumer and is reserved for it (ADR-0096); a [ClaimAffinity] prefers the flow's last instance (ADR-0099).
 */
class RunnerClaimQueue(
    private val maxPending: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val affinityMemory: RunnerAffinityMemory = RunnerAffinityMemory(),
) {
    private val mutex = Mutex()
    private val pending = ArrayDeque<ClaimRequest>()
    private val waiters = mutableListOf<Waiter>()
    private val consumers = CopyOnWriteArrayList<ClaimConsumerInfo>()

    /** Request → the woken waiter it is held for; under [mutex]. Bounded by [pending]. */
    private val reservedBy = HashMap<ClaimRequest, Waiter>()

    /** Consumer → sequence number of its last claim or reservation (0 = never); one entry per registered consumer. */
    private val lastClaim = ConcurrentHashMap<ClaimConsumerInfo, Long>()

    /** Consumer → consecutive requests it took ahead of an older match it also served; one entry per registered consumer. */
    private val jumps = ConcurrentHashMap<ClaimConsumerInfo, Int>()
    private var claimSeq = 0L
    private var parkSeq = 0L
    private val handoffOrder = compareBy<Waiter>({ lastClaim[it.consumer] ?: 0L }, { it.order })

    fun registerConsumer(info: ClaimConsumerInfo) {
        consumers.add(info)
        lastClaim[info] = 0L
        jumps[info] = 0
    }

    /**
     * Removes [info]'s records and wakes every waiter its active affinity preference held back, so their runs fall back
     * at once, not at the `waitSec` deadline (ADR-0099). Deregister (drain) and the heartbeat sweep both leave here.
     */
    suspend fun unregisterConsumer(info: ClaimConsumerInfo) {
        mutex.withLock {
            // The held-back waiters must be picked while the departing consumer is still
            // registered: after the removal the preference blocks nobody and would wake no one.
            val lifted = liftHeldBackWaiters(info)
            removeConsumer(info)
            lifted.forEach {
                waiters.remove(it)
                it.wake.complete(Unit)
            }
        }
    }

    /**
     * Non-suspend unregister for a consumer that stops at process shutdown: the records go, but no
     * run is woken, since nothing will serve it before the queue itself goes away.
     */
    fun detachConsumer(info: ClaimConsumerInfo) {
        removeConsumer(info)
    }

    /** Waiters held back by requests that preferred [info]'s instance, captured before it leaves. */
    private fun liftHeldBackWaiters(info: ClaimConsumerInfo): List<Waiter> {
        val departed = info.affinityInstance ?: return emptyList()
        val now = clock()
        return pending
            .filter { it.isPending }
            .flatMap { request ->
                val affinity = request.affinity ?: return@flatMap emptyList()
                if (affinity.preferredInstance != departed || !affinity.isActive(request.enqueuedAtMs, now)) {
                    return@flatMap emptyList()
                }
                // Not preferenceBlocks(): deregister sets `draining` first, so the departing instance blocks no one there.
                // Waking by identity is safe: a spurious wake only rescans and re-parks.
                waiters.filter { it.consumer.matches(request.filter) && it.consumer.affinityInstance != departed }
            }
    }

    private fun removeConsumer(info: ClaimConsumerInfo) {
        consumers.remove(info)
        lastClaim.remove(info)
        jumps.remove(info)
    }

    fun consumers(): List<ClaimConsumerInfo> = consumers.toList()

    suspend fun pendingCount(): Int = mutex.withLock { pending.count { it.isPending } }

    /** The clock that stamps [PendingClaim.enqueuedAtMs]; ages must be measured against it. */
    fun nowMs(): Long = clock()

    /** Waiting requests, oldest first; the deque is FIFO so its order is enqueue order. */
    suspend fun pendingSnapshot(): List<PendingClaim> =
        mutex.withLock { pending.filter { it.isPending }.map { PendingClaim(it.filter, it.enqueuedAtMs) } }

    /**
     * Enqueue and wait up to [timeout] for a session. Throws [ClaimQueueFullException]
     * at once at the bound and [RunnerClaimTimeoutException] at expiry; cancelling
     * the caller withdraws the request.
     */
    @Suppress("SuspendFunSwallowedCancellation") // cleans up under NonCancellable and rethrows
    suspend fun awaitClaim(
        params: RunnerSessionParams,
        filter: RunnerFilter,
        timeout: Duration,
        affinity: RunnerAffinity? = null,
    ): RunnerSession {
        val request = submit(params, filter, affinity, timeout)
        try {
            val session = withTimeoutOrNull(timeout) { request.deferred.await() }
            if (session != null) return session
            if (request.tryFinish(ClaimRequest.State.EXPIRED)) {
                remove(request)
                val (reason, publicReason) = claimExpiryReasons(consumers.toList(), filter, request.affinity, timeout)
                throw RunnerClaimTimeoutException(timeout, filter, reason, publicReason)
            }
            // Claimed, but the session is still opening: the TTL bounds the claim, not the open, and this second wait
            // only backstops a consumer that never calls complete()/fail() (ADR-0066, amendment of 2026-11-20).
            val late = withTimeoutOrNull(timeout) { request.deferred.await() }
            if (late != null) return late
            // Make a late complete() return false so the consumer closes what it opened; if a value
            // raced in before cancel(), nobody else owns it, so close it here.
            request.deferred.cancel()
            closeIfDelivered(request)
            // This observation names no runner, so both renderings are the same sentence.
            throw RunnerClaimTimeoutException(
                timeout,
                filter,
                "claimed but no session was delivered",
                "claimed but no session was delivered",
            )
        } catch (e: CancellationException) {
            request.tryFinish(ClaimRequest.State.CANCELLED)
            remove(request)
            request.deferred.cancel()
            // Either cancel() beat complete(), and the consumer closes its own session, or prompt cancellation dropped
            // a value complete() already delivered, which nobody else owns.
            closeIfDelivered(request)
            throw e
        }
    }

    /** Closes the session [request] was completed with, if any: called once nobody else owns it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun closeIfDelivered(request: ClaimRequest) {
        if (request.deferred.isCompleted && request.deferred.getCompletionExceptionOrNull() == null) {
            runCatching { request.deferred.getCompleted().close() }
        }
    }

    /**
     * Takes the oldest pending request [consumer] matches, parking until one arrives or until an affinity deadline opens
     * a held-back one (ADR-0099). A call woken for a reserved request hands it on if it leaves unclaimed (ADR-0096).
     */
    suspend fun take(consumer: ClaimConsumerInfo): ClaimRequest {
        // The waiter this call last parked; null once it has claimed. Its reservation survives a
        // normal wake so the next scan can claim it.
        var previous: Waiter? = null
        try {
            while (true) {
                val (waiter, waitMs) =
                    mutex.withLock {
                        // Nothing suspends after the claim: the claim route relies on it.
                        claimMatch(consumer, previous)?.let {
                            previous = null
                            return it
                        }
                        park(consumer, previous) to affinityWaitMs(consumer)
                    }
                previous = waiter
                if (waitMs == null) waiter.wake.await() else withTimeoutOrNull(waitMs) { waiter.wake.await() }
            }
        } finally {
            // Cancelled (or timed out) while parked or before rescanning: drop the waiter and hand
            // its reservation on. NonCancellable: the caller's own cancellation got us here.
            previous?.let { withContext(NonCancellable) { mutex.withLock { abandon(it) } } }
        }
    }

    /**
     * Claims the request [consumer] takes next, under [mutex]: one preferring it goes before older matches, up to
     * [MAX_JUMPS] in a row (ADR-0099). A match that loses its CAS to an expiry or cancellation is skipped for the next.
     */
    private fun claimMatch(
        consumer: ClaimConsumerInfo,
        previous: Waiter?,
    ): ClaimRequest? {
        dropSettled()
        val now = clock()
        val claimable =
            pending.filter {
                reservedBy[it].let { owner -> owner == null || owner === previous } &&
                    consumer.matches(it.filter) &&
                    !preferenceBlocks(consumer, it, now)
            }
        if (claimable.isEmpty()) return null
        val preferred = claimable.filter { prefers(it, consumer, now) }
        val jump = preferred.isNotEmpty() && preferred.first() !== claimable.first() && (jumps[consumer] ?: 0) < MAX_JUMPS
        val ordered = if (jump) preferred + claimable.filterNot { prefers(it, consumer, now) } else claimable
        val match = ordered.firstOrNull { it.tryClaim() } ?: return null
        pending.remove(match)
        reservedBy.remove(match)
        lastClaim.computeIfPresent(consumer) { _, _ -> ++claimSeq }
        jumps.computeIfPresent(consumer) { _, count -> if (jump && match in preferred) count + 1 else 0 }
        // The claim records the instance for the flow's key; a consumer with no identity (a nameless external
        // instance) records nothing, so affinity never names it (ADR-0099).
        match.affinity?.let { affinity ->
            consumer.affinityInstance?.let { affinityMemory.record(affinity.rootProcessId, affinity.group, it) }
        }
        // This call is returning, so its last waiter stops being parked even when no wake removed
        // it (a rescan after the affinity deadline): one left in `waiters` holds a later request.
        previous?.let { abandon(it) }
        return match
    }

    /**
     * Whether [consumer] must pass over [request] because the run prefers another instance: only
     * while the preference is active and only while that instance is registered, not draining and
     * still matching the run's filter — otherwise the run falls back at once. See ADR-0099.
     */
    private fun preferenceBlocks(
        consumer: ClaimConsumerInfo,
        request: ClaimRequest,
        nowMs: Long,
    ): Boolean {
        val affinity = request.affinity ?: return false
        if (!affinity.isActive(request.enqueuedAtMs, nowMs)) return false
        val preferred = affinity.preferredInstance ?: return false
        if (preferred == consumer.affinityInstance) return false
        return consumers.any { it.affinityInstance == preferred && !it.draining && it.matchesFilter(request.filter) }
    }

    /**
     * Whether [request] prefers [consumer] right now, so it goes ahead of older matches. A flow
     * with nothing recorded yet prefers nobody — not even a consumer with no identity of its own.
     */
    private fun prefers(
        request: ClaimRequest,
        consumer: ClaimConsumerInfo,
        nowMs: Long,
    ): Boolean {
        val affinity = request.affinity ?: return false
        val preferred = affinity.preferredInstance ?: return false
        return affinity.isActive(request.enqueuedAtMs, nowMs) && preferred == consumer.affinityInstance
    }

    /**
     * How long [consumer] should wait before rescanning: the nearest preference deadline among the
     * pending requests it matches but is held back from. Null = no deadline, so it parks until a
     * request arrives, as before affinity existed.
     */
    private fun affinityWaitMs(consumer: ClaimConsumerInfo): Long? {
        val now = clock()
        val blocked =
            pending.filter { it.isPending && consumer.matches(it.filter) && preferenceBlocks(consumer, it, now) }
        val deadlines = blocked.mapNotNull { it.affinity?.deadlineMs(it.enqueuedAtMs) }
        return deadlines.minOrNull()?.minus(now)
    }

    /** Parks a new waiter under [mutex]; a reservation [previous] could not claim goes to the next consumer. */
    private fun park(
        consumer: ClaimConsumerInfo,
        previous: Waiter?,
    ): Waiter {
        // Still listed when this call is rescanning after a deadline, absent when a dispatch woke
        // it: removing it here either way keeps one waiter per take() call.
        previous?.let {
            waiters.remove(it)
            releaseReservation(it)
        }
        return Waiter(consumer, ++parkSeq).also { waiters.add(it) }
    }

    private fun abandon(waiter: Waiter) {
        waiters.remove(waiter)
        releaseReservation(waiter)
    }

    private fun releaseReservation(waiter: Waiter) {
        val held = reservedBy.entries.firstOrNull { it.value === waiter }?.key ?: return
        reservedBy.remove(held)
        if (!dispatch(held)) armHeldBackWaiters(held)
    }

    /**
     * Wakes at most one parked waiter for [request] and returns whether it did: the one it prefers, else the least
     * recently claimed (ADR-0096). A waiter a preference holds back is not woken, as it could not claim the request.
     */
    private fun dispatch(request: ClaimRequest): Boolean {
        if (!request.isPending || request in reservedBy) return false
        val now = clock()
        val matching =
            waiters.filter { it.consumer.matches(request.filter) && !preferenceBlocks(it.consumer, request, now) }
        val next =
            matching.filter { prefers(request, it.consumer, now) }.minWithOrNull(handoffOrder)
                ?: matching.minWithOrNull(handoffOrder) ?: return false
        waiters.remove(next)
        reservedBy[request] = next
        // A reservation ranks as a claim, or one consumer's parked waiters take a whole burst.
        // computeIfPresent: never re-add an unregistered consumer's record.
        lastClaim.computeIfPresent(next.consumer) { _, _ -> ++claimSeq }
        next.wake.complete(Unit)
        return true
    }

    /**
     * Wakes every parked waiter [request]'s preference holds back, so each re-parks with the deadline that opens it.
     * Called only when no waiter could be woken, and a woken waiter re-parks rather than claims, so it cannot loop.
     */
    private fun armHeldBackWaiters(request: ClaimRequest) {
        val now = clock()
        waiters
            .filter { it.consumer.matches(request.filter) && preferenceBlocks(it.consumer, request, now) }
            .forEach {
                waiters.remove(it)
                it.wake.complete(Unit)
            }
    }

    private fun dropSettled() {
        pending.removeAll { !it.isPending }
        reservedBy.keys.removeAll { !it.isPending }
    }

    /** Currently-parked `take()` calls with no match yet; test-only visibility into leak fixes. */
    internal suspend fun waiterCount(): Int = mutex.withLock { waiters.size }

    /** Consumers with a claim record; test-only visibility into the record map's bound. */
    internal fun claimRecordCount(): Int = lastClaim.size

    /** Consumers with a jump record; test-only visibility into the record map's bound. */
    internal fun jumpRecordCount(): Int = jumps.size

    private suspend fun submit(
        params: RunnerSessionParams,
        filter: RunnerFilter,
        affinity: RunnerAffinity?,
        timeout: Duration,
    ): ClaimRequest =
        mutex.withLock {
            dropSettled()
            if (pending.size >= maxPending) throw ClaimQueueFullException(maxPending)
            val request = ClaimRequest(params, filter, clock(), claimAffinity(params, affinity, timeout))
            pending.addLast(request)
            if (!dispatch(request)) armHeldBackWaiters(request)
            request
        }

    /**
     * The affinity a request carries (ADR-0099): the flow root from the run's own Homer-stamped params, the instance
     * from memory at submit, and `waitSec` clamped to the claim TTL. Null without a declared affinity or a flow root.
     */
    private fun claimAffinity(
        params: RunnerSessionParams,
        affinity: RunnerAffinity?,
        timeout: Duration,
    ): ClaimAffinity? {
        val root = params.rootProcessId ?: return null
        val declared = affinity ?: return null
        return ClaimAffinity(
            rootProcessId = root,
            group = declared.group,
            preferredInstance = affinityMemory.preferredFor(root, declared.group),
            waitMs = minOf(declared.waitSec * 1000L, timeout.inWholeMilliseconds),
        )
    }

    private suspend fun remove(request: ClaimRequest) {
        withContext(NonCancellable) {
            mutex.withLock {
                pending.remove(request)
                reservedBy.remove(request)
            }
        }
    }

    companion object {
        /**
         * How many requests in a row a consumer may take ahead of an older match it also serves
         * before it must take the oldest one. Bounds how long an agent without `runnerAffinity`
         * can wait behind a preferred peer's requests (ADR-0099).
         */
        const val MAX_JUMPS = 8
    }
}

/** A parked `take()` call: the consumer behind it and the order it parked in. */
private class Waiter(
    val consumer: ClaimConsumerInfo,
    val order: Long,
) {
    val wake = CompletableDeferred<Unit>()
}
