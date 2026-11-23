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
    // CWE-667, CWE-362: a key has one holder and at most one pending replacement, which the
    // deferred kill installs, so two same-key runs never execute at once. See ADR-0104.
    private data class LockEntry(
        var holder: Int? = null,
        var pending: Int? = null,
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
                val entry = locks.getOrPut(lockName) { LockEntry() }
                // The key is held by its holder, or by the replacement a deferred kill has not
                // installed yet: a collision is decided against that one run (ADR-0104).
                val activeProcessID = entry.holder ?: entry.pending

                if (activeProcessID == null) {
                    entry.holder = processID
                    return LockGrant()
                }

                when (onKeyCollisionStrategy) {
                    SKIP_CURRENT -> {
                        throw AnotherProcessStillActive(activeProcessID)
                    }

                    KILL_OLD_RUN_CURRENT -> {
                        // The replacement is registered here and installed and killed later: neither
                        // may land before the new run holds a slot, and a later KILL supersedes it.
                        entry.pending = processID
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
                mutex.withLock {
                    locks[lockName]?.waitQueue?.removeAll { it.first == processID }
                }
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
        // CWE-667: the handover is the moment the replacement becomes the key's holder, and the only
        // one. A later KILL leaves no entry holding this run, which refuses it instead (ADR-0104).
        val installed =
            mutex.withLock {
                val entry = locks.values.firstOrNull { it.pending == processID } ?: return@withLock false
                entry.holder = processID
                entry.pending = null
                true
            }
        if (!installed) throw AnotherProcessStillActive(victim)
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
                val holder = entry.holder
                val pending = entry.pending
                if (holder == processID) {
                    entry.holder = null
                } else if (pending == processID) {
                    // A replacement refused its slot gives the key back, its victim left untouched.
                    entry.pending = null
                } else {
                    logger.warn(
                        "releaseLock called by process {} which is not a holder of lock '{}'",
                        processID,
                        lockName,
                    )
                }

                // A pending handover holds the key for the replacement the deferred kill installs:
                // promoting a waiter now would put two runs on it (CWE-667). See ADR-0104.
                if (entry.holder != null || entry.pending != null) return@withLock

                val next = entry.waitQueue.removeFirstOrNull()
                if (next != null) {
                    entry.holder = next.first
                    next.second.complete(Unit)
                } else {
                    locks.remove(lockName)
                }
            }
        }
}
