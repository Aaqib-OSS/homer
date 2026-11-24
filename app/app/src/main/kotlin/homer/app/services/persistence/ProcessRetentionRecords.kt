package homer.app.services.persistence

import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.ProcessManager
import java.nio.file.Path

/**
 * The retention part of [ProcessManager]'s API, delegated to [DefaultProcessRetentionRecords]: purge times,
 * the `WorkspacePurgeService` sweeps' due-row reads and markers, and the shared-workspace checks guarding
 * every directory delete. See ADR-0048 and ADR-0053.
 */
interface ProcessRetentionRecords {
    suspend fun setPurgeAt(
        processId: Int,
        purgeAt: Long,
        recordPurgeAt: Long = purgeAt,
        retainArtifacts: List<String> = emptyList(),
    )

    /** Marks [processId]'s workspace sweep done with its retained files kept: [ProcessRepository.markWorkspacePurged]. */
    suspend fun markWorkspacePurged(
        processId: Int,
        retainedTruncated: Boolean,
    )

    /** Processes whose workspace directory is due for deletion and still recorded. Filtered in SQL. */
    suspend fun getWorkspacePurgeableProcesses(
        statuses: Set<ProcessStatus>,
        nowSec: Long,
    ): List<ProcessInfo>

    /** Processes whose record is due for deletion (`recordPurgeAt`, defaulting to `purgeAt`). */
    suspend fun getRecordPurgeableProcesses(
        statuses: Set<ProcessStatus>,
        nowSec: Long,
    ): List<ProcessInfo>

    /**
     * Clears the recorded artifacts dir once its workspace has been swept.
     * [retainedTruncated] records a prune that truncated to nothing — see
     * [ProcessRepository.clearArtifactsDir].
     */
    suspend fun clearArtifactsDir(
        processId: Int,
        retainedTruncated: Boolean? = null,
    )

    /** True when another process row still references [artifactsDir] (shared fixed-path workspace). */
    suspend fun isArtifactsDirShared(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean

    /**
     * True when a still-live run other than [excludeProcessId] references [artifactsDir]
     * — the guard `Workspace.cleanup` uses. See ADR-0048 (2026-10-06 amendment).
     */
    suspend fun isWorkspaceSharedWithActiveRun(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean

    /**
     * Claims [artifactsDir] for deletion by [evictedProcessId] in one write transaction, so a run registering that path
     * cannot slip between the sharing check and the delete (CWE-367); [attemptToken] marks this attempt's claim.
     * [maxPending] bounds the claim table. See ADR-0048 (2026-11-24 amendment).
     */
    suspend fun claimWorkspaceDelete(
        artifactsDir: Path,
        evictedProcessId: Int,
        maxPending: Int,
        attemptToken: String,
    ): WorkspaceDeleteClaim

    /**
     * Settles [attemptToken]'s claim on [artifactsDir]: [deleted] drops it, else it stays for the next
     * sweep. Only the attempt holding the claim settles it.
     */
    suspend fun settleWorkspaceDelete(
        artifactsDir: Path,
        attemptToken: String,
        deleted: Boolean,
    )

    /** The claims no attempt holds, oldest first, at most [limit]: what the sweep retries. */
    suspend fun heldWorkspaceDeletes(limit: Int): List<HeldWorkspaceDelete>

    /** How many claims the claim table holds, in flight or not. */
    suspend fun pendingWorkspaceDeleteCount(): Int

    /** Clears the in-flight mark on every claim; no delete survives a restart, so this runs once at boot. */
    suspend fun resetInFlightWorkspaceDeletes()
}

/** [ProcessRetentionRecords] straight from [repository]. */
internal class DefaultProcessRetentionRecords(
    private val repository: ProcessRepository,
) : ProcessRetentionRecords {
    override suspend fun setPurgeAt(
        processId: Int,
        purgeAt: Long,
        recordPurgeAt: Long,
        retainArtifacts: List<String>,
    ) {
        repository.setPurgeAt(processId, purgeAt, recordPurgeAt, retainArtifacts)
    }

    override suspend fun markWorkspacePurged(
        processId: Int,
        retainedTruncated: Boolean,
    ) {
        repository.markWorkspacePurged(processId, retainedTruncated)
    }

    override suspend fun getWorkspacePurgeableProcesses(
        statuses: Set<ProcessStatus>,
        nowSec: Long,
    ): List<ProcessInfo> = repository.getWorkspacePurgeableProcesses(statuses, nowSec)

    override suspend fun getRecordPurgeableProcesses(
        statuses: Set<ProcessStatus>,
        nowSec: Long,
    ): List<ProcessInfo> = repository.getRecordPurgeableProcesses(statuses, nowSec)

    override suspend fun clearArtifactsDir(
        processId: Int,
        retainedTruncated: Boolean?,
    ) {
        repository.clearArtifactsDir(processId, retainedTruncated)
    }

    override suspend fun isArtifactsDirShared(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean = repository.isArtifactsDirShared(artifactsDir.toString(), excludeProcessId)

    override suspend fun isWorkspaceSharedWithActiveRun(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean = repository.isWorkspaceSharedWithActiveRun(artifactsDir.toString(), excludeProcessId)

    override suspend fun claimWorkspaceDelete(
        artifactsDir: Path,
        evictedProcessId: Int,
        maxPending: Int,
        attemptToken: String,
    ): WorkspaceDeleteClaim = repository.claimWorkspaceDelete(artifactsDir.toString(), evictedProcessId, maxPending, attemptToken)

    override suspend fun settleWorkspaceDelete(
        artifactsDir: Path,
        attemptToken: String,
        deleted: Boolean,
    ) {
        repository.settleWorkspaceDelete(artifactsDir.toString(), attemptToken, deleted)
    }

    override suspend fun heldWorkspaceDeletes(limit: Int): List<HeldWorkspaceDelete> = repository.heldWorkspaceDeletes(limit)

    override suspend fun pendingWorkspaceDeleteCount(): Int = repository.pendingWorkspaceDeleteCount()

    override suspend fun resetInFlightWorkspaceDeletes() {
        repository.resetInFlightWorkspaceDeletes()
    }
}
