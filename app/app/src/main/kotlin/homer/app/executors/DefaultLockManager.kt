package homer.app.executors

import homer.app.exceptions.AnotherProcessStillActive
import homer.app.models.OnKeyCollisionStrategy
import homer.app.models.OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT
import homer.app.models.OnKeyCollisionStrategy.SKIP_CURRENT
import homer.app.models.OnKeyCollisionStrategy.WAIT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.Logger

class DefaultLockManager(
    private val processManager: RunKiller,
    private val logger: Logger,
) : LockManager {
    // A holder a promotion adds is released on every end of acquireLock — granted, cancelled or
    // killed — or the key wedges with no holder left to release it (CWE-667, CWE-772, ADR-0104).
    private data class LockEntry(
        val holders: MutableSet<Int> = mutableSetOf(),
        val waitQueue: ArrayDeque<Pair<Int, CompletableDeferred<Unit>>> = ArrayDeque(),
    )

    private val locks = mutableMapOf<String, LockEntry>()

    private val mutex = Mutex()

    @Suppress("SuspendFunSwallowedCancellation") // rethrown after a NonCancellable dequeue
    override suspend fun acquireLock(
        lockName: String,
        onKeyCollisionStrategy: OnKeyCollisionStrategy,
        processID: Int,
        waitGate: LockWaitGate,
    ): LockGrant {
        val deferred = CompletableDeferred<Unit>()
        var waitTicket: LockWaitTicket? = null

        val victim: Int? =
            mutex.withLock {
                val entry = locks[lockName]

                if (entry == null || entry.holders.isEmpty()) {
                    locks.getOrPut(lockName) { LockEntry() }.holders.add(processID)
                    return LockGrant()
                }

                val activeProcessID = entry.holders.first()
                when (onKeyCollisionStrategy) {
                    SKIP_CURRENT -> {
                        throw AnotherProcessStillActive(activeProcessID)
                    }

                    KILL_OLD_RUN_CURRENT -> {
                        // The victim is registered against the key here and returned to be killed
                        // later: the kill must not land before the new run holds a slot (ADR-0104).
                        entry.holders.add(processID)
                        activeProcessID
                    }

                    WAIT -> {
                        // The place in the lock-wait bound is reserved before the run is enqueued,
                        // so a refused surplus run leaves no queue entry behind (ADR-0047 rules 4-5).
                        waitTicket = waitGate.enter()
                        entry.waitQueue.addLast(processID to deferred)
                        null
                    }
                }
            }
        if (victim != null) return LockGrant(victim)

        try {
            deferred.await()
        } catch (e: CancellationException) {
            // NonCancellable: a cancelled withLock throws when the mutex is contended, which
            // would leave this dead waiter queued for releaseLock to hand the lock to.
            withContext(NonCancellable) {
                mutex.withLock { abandonWait(lockName, processID) }
            }
            throw e
        } finally {
            waitTicket?.close()
        }
        return LockGrant()
    }

    override suspend fun killDisplaced(
        grant: LockGrant,
        processID: Int,
    ) {
        val victim = grant.displacedProcessId ?: return
        // killProcess writes to the database, so it runs after the mutex is released.
        processManager.killProcess(victim, "The new process $processID takes precedence")
    }

    override suspend fun releaseLock(
        lockName: String,
        processID: Int,
    ): Unit =
        withContext(NonCancellable) {
            mutex.withLock {
                val entry = locks[lockName]
                if (entry == null) {
                    logger.warn("releaseLock called for unknown lock '{}' by process {}", lockName, processID)
                    return@withLock
                }
                if (!releaseHolder(lockName, entry, processID)) {
                    logger.warn(
                        "releaseLock called by process {} which is not a holder of lock '{}'",
                        processID,
                        lockName,
                    )
                }
            }
        }

    /**
     * Releases what a cancelled wait still holds: its queue entry, and the holdership a promotion
     * may have granted it just before the cancellation landed (CWE-667, CWE-772, ADR-0104).
     */
    private fun abandonWait(
        lockName: String,
        processID: Int,
    ) {
        val entry = locks[lockName] ?: return
        entry.waitQueue.removeAll { it.first == processID }
        releaseHolder(lockName, entry, processID)
    }

    /**
     * Removes [processID] from [entry] and, when it was the last holder, promotes the next waiter
     * or drops the key. Returns whether [processID] held it (ADR-0104).
     */
    private fun releaseHolder(
        lockName: String,
        entry: LockEntry,
        processID: Int,
    ): Boolean {
        if (!entry.holders.remove(processID)) return false
        if (entry.holders.isNotEmpty()) return true

        val next = entry.waitQueue.removeFirstOrNull()
        if (next == null) {
            locks.remove(lockName)
            return true
        }
        entry.holders.add(next.first)
        next.second.complete(Unit)
        return true
    }
}
