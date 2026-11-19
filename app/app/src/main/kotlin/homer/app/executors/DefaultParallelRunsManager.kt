package homer.app.executors

import homer.app.exceptions.MaxLockWaitersExceededException
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.models.OnMaxParallelRunsStrategy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DefaultParallelRunsManager : ParallelRunsManager {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    // Count of runs currently suspended in acquireSlot's WAIT path, per agent. Only
    // these queued runs hold non-evictable process rows while parked, so this — not
    // the holder count — is what maxQueuedRuns bounds.
    private val waiters = ConcurrentHashMap<String, AtomicInteger>()

    // Count of runs currently suspended waiting for a subject lock, per agent, when they
    // do not share `waiters`. See enterLockWait.
    private val lockWaiters = ConcurrentHashMap<String, AtomicInteger>()

    private fun getSemaphore(
        agentName: String,
        maxParallelRuns: Int,
    ): Semaphore = semaphores.computeIfAbsent(agentName) { Semaphore(maxParallelRuns) }

    override suspend fun acquireSlot(
        agentName: String,
        maxParallelRuns: Int,
        strategy: OnMaxParallelRunsStrategy,
        maxQueuedRuns: Int,
    ): Semaphore {
        val semaphore = getSemaphore(agentName, maxParallelRuns)
        when (strategy) {
            OnMaxParallelRunsStrategy.WAIT -> {
                acquireWaiting(agentName, semaphore, maxQueuedRuns)
            }

            OnMaxParallelRunsStrategy.REJECT -> {
                if (!semaphore.tryAcquire()) {
                    throw MaxParallelRunsExceededException(agentName, maxParallelRuns)
                }
            }
        }
        return semaphore
    }

    private suspend fun acquireWaiting(
        agentName: String,
        semaphore: Semaphore,
        maxQueuedRuns: Int,
    ) {
        if (semaphore.tryAcquire()) return
        // No free permit: this run would queue. Bound the number of concurrent waiters.
        // Capture the counter reference (not a re-lookup) so a concurrent reload that
        // drops the map entry can't misdirect the decrement — mirrors releasing the
        // exact Semaphore object rather than looking it up.
        val waiterCount = waiters.computeIfAbsent(agentName) { AtomicInteger(0) }
        if (waiterCount.incrementAndGet() > maxQueuedRuns) {
            waiterCount.decrementAndGet()
            throw MaxQueuedRunsExceededException(agentName, maxQueuedRuns)
        }
        try {
            semaphore.acquire()
        } finally {
            waiterCount.decrementAndGet()
        }
    }

    override suspend fun releaseSlot(slot: Semaphore): Unit =
        withContext(NonCancellable) {
            slot.release()
        }

    override fun enterLockWait(
        agentName: String,
        maxParallelRuns: Int,
        strategy: OnMaxParallelRunsStrategy,
        maxQueuedRuns: Int,
    ): LockWaitTicket {
        // A lock waiter and a slot waiter pin the same kind of row while parked, so under WAIT they
        // share one counter and cap. Under REJECT there is no slot queue: own counter, capped at maxParallelRuns.
        val shared = strategy == OnMaxParallelRunsStrategy.WAIT
        val count = (if (shared) waiters else lockWaiters).computeIfAbsent(agentName) { AtomicInteger(0) }
        val bound = if (shared) maxQueuedRuns else maxParallelRuns
        if (count.incrementAndGet() > bound) {
            count.decrementAndGet()
            if (shared) throw MaxQueuedRunsExceededException(agentName, bound)
            throw MaxLockWaitersExceededException(agentName, bound)
        }
        val freed = AtomicBoolean(false)
        return LockWaitTicket { if (freed.compareAndSet(false, true)) count.decrementAndGet() }
    }

    override fun onAgentsReloaded(
        updated: Set<String>,
        removed: Set<String>,
    ) {
        (updated + removed).forEach {
            semaphores.remove(it)
            waiters.remove(it)
            lockWaiters.remove(it)
        }
    }
}
