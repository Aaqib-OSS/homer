package homer.app.executors

import homer.app.models.OnMaxParallelRunsStrategy
import kotlinx.coroutines.sync.Semaphore

/**
 * A place reserved in an agent's lock-wait bound. [close] frees it and is idempotent, so a
 * caller can free it on every exit path (grant, cancel) without tracking whether it already did.
 */
fun interface LockWaitTicket {
    fun close()
}

/**
 * Reserves a [LockWaitTicket] at the moment a run is about to suspend on a subject lock, and
 * throws when the agent's lock-wait bound is reached. A run waiting for a lock holds no
 * parallel-run slot (ADR-0104), so this is where the wait stays bounded (ADR-0047 rules 4–5).
 */
fun interface LockWaitGate {
    fun enter(): LockWaitTicket
}

interface ParallelRunsManager {
    suspend fun acquireSlot(
        agentName: String,
        maxParallelRuns: Int,
        strategy: OnMaxParallelRunsStrategy,
        maxQueuedRuns: Int,
    ): Semaphore

    suspend fun releaseSlot(slot: Semaphore)

    /**
     * Reserves a place for a run about to wait on a subject lock, refusing the surplus instead of parking it. Under WAIT,
     * lock waiters share the slot queue's cap of [maxQueuedRuns]; under REJECT the cap is [maxParallelRuns] (ADR-0104).
     */
    fun enterLockWait(
        agentName: String,
        maxParallelRuns: Int,
        strategy: OnMaxParallelRunsStrategy,
        maxQueuedRuns: Int,
    ): LockWaitTicket

    fun onAgentsReloaded(
        updated: Set<String>,
        removed: Set<String>,
    )
}
