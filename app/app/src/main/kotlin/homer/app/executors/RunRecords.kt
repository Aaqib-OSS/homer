package homer.app.executors

import homer.app.models.NewRunRecord
import homer.app.models.ProcessStatus
import kotlinx.coroutines.Job

/**
 * What [ProcessExecutor] records on the run it starts: the row and its live job, the trace id, the
 * status writes of "Status precedence rules" (app/CLAUDE.md) and the purge times. Implemented by
 * `ProcessManager`, which owns the status FSM. See ADR-0083.
 */
interface RunRecords : GuardedStatusWrite {
    // Inserts the run's row (cooldown checked in the same transaction) and returns its id.
    suspend fun obtainProcessId(record: NewRunRecord): Int

    suspend fun assignJob(
        processId: Int,
        job: Job,
    )

    suspend fun removeJob(processId: Int)

    suspend fun setTraceId(
        processId: Int,
        traceId: String,
    )

    suspend fun getProcessStatus(processId: Int): ProcessStatus

    // Why the run failed before it ran, written after the FAILED CAS and only on a row still FAILED
    // with no step recorded, so a mid-run failure or a kill stores nothing. ADR-0098.
    suspend fun setFailureReason(
        processId: Int,
        reason: String,
        holderRunId: Int?,
    ): Boolean

    suspend fun setPurgeAt(
        processId: Int,
        purgeAt: Long,
        recordPurgeAt: Long,
        retainArtifacts: List<String>,
    )
}
