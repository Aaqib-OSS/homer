package homer.app.executors

import homer.app.models.OnKeyCollisionStrategy

/**
 * A granted lock; [displacedProcessId] names the holder the collision displaced, if any. [LockManager.killDisplaced]
 * kills it, not the decision, so a `KILL_OLD_RUN_CURRENT` kill lands only once the new run can run (ADR-0104).
 */
data class LockGrant(
    val displacedProcessId: Int? = null,
)

interface LockManager {
    /**
     * Decides the collision for [lockName] and grants it to [processID], waiting when the strategy
     * says so. [waitGate] reserves the place a `WAIT` run takes in the agent's lock-wait bound just
     * before it is enqueued, and throws when the bound is reached. See ADR-0104.
     */
    suspend fun acquireLock(
        lockName: String,
        onKeyCollisionStrategy: OnKeyCollisionStrategy,
        processID: Int,
        waitGate: LockWaitGate,
    ): LockGrant

    /**
     * Installs [processID] as the key's holder and kills the holder [grant] displaced, if any, naming
     * [processID] as the run taking precedence. A replacement a later `KILL_OLD_RUN_CURRENT`
     * superseded is refused with `AnotherProcessStillActive` and kills nobody. See ADR-0104.
     */
    suspend fun killDisplaced(
        grant: LockGrant,
        processID: Int,
    )

    suspend fun releaseLock(
        lockName: String,
        processID: Int,
    )
}
