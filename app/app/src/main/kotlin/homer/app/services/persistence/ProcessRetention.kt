package homer.app.services.persistence

import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.io.path.Path

// How a process row and its workspace leave: purge times, both sweeps' due-row reads and markers, the
// shared-workspace guard, the record purge and the capacity eviction. See ADR-0048 and ADR-0053.

suspend fun ProcessRepository.setPurgeAt(
    processId: Int,
    purgeAt: Long,
    recordPurgeAt: Long = purgeAt,
    retainArtifacts: List<String> = emptyList(),
) {
    query {
        Processes.update({ Processes.id eq processId }) {
            it[Processes.purgeAt] = purgeAt
            it[Processes.recordPurgeAt] = recordPurgeAt
            // Null when nothing is declared retained, so a row with an empty list
            // reads back identically to one that predates the column. See ADR-0053.
            it[Processes.retainArtifacts] =
                retainArtifacts.takeIf { it.isNotEmpty() }?.let { encodeRetainArtifacts(it) }
        }
    }
}

/**
 * Terminal rows whose *directory* is due and not yet swept: the sweep nulls `artifacts_dir`, or sets
 * `workspace_purged_at` when it kept retained files ([markWorkspacePurged]). See ADR-0048, ADR-0053.
 */
suspend fun ProcessRepository.getWorkspacePurgeableProcesses(
    statuses: Set<ProcessStatus>,
    nowSec: Long,
): List<ProcessInfo> =
    purgeable(statuses) {
        (Processes.purgeAt lessEq nowSec) and
            Processes.artifactsDir.isNotNull() and
            Processes.workspacePurgedAt.isNull()
    }

/**
 * Terminal rows whose *record* is due (`record_purge_at`, else `purge_at`) and whose workspace sweep is
 * done. That guard is load-bearing: deleting the record first would orphan a directory still failing to
 * delete, or retained files the artifact routes serve. See ADR-0048, ADR-0053.
 */
suspend fun ProcessRepository.getRecordPurgeableProcesses(
    statuses: Set<ProcessStatus>,
    nowSec: Long,
): List<ProcessInfo> =
    purgeable(statuses) {
        (Processes.artifactsDir.isNull() or Processes.workspacePurgedAt.isNotNull()) and
            (
                (Processes.recordPurgeAt.isNotNull() and (Processes.recordPurgeAt lessEq nowSec)) or
                    (Processes.recordPurgeAt.isNull() and (Processes.purgeAt lessEq nowSec))
            )
    }

/**
 * Rows in [statuses] with a purge time set and [due], read through the inputs-free
 * [Processes.recordReadColumns] projection both purge sweeps share.
 */
private suspend fun ProcessRepository.purgeable(
    statuses: Set<ProcessStatus>,
    due: () -> Op<Boolean>,
): List<ProcessInfo> =
    read {
        Processes
            .select(Processes.recordReadColumns)
            .where { (Processes.status inList statuses.map { it.name }) and Processes.purgeAt.isNotNull() and due() }
            .map { it.toProcessInfo(jobRegistry, emptyList(), emptyMap()) }
    }

/**
 * Clears the recorded artifacts dir after its workspace was swept. A non-null [retainedTruncated] records
 * whether a prune overflowed its cap and kept nothing, never silently (ADR-0053); null leaves the column.
 */
suspend fun ProcessRepository.clearArtifactsDir(
    processId: Int,
    retainedTruncated: Boolean? = null,
) {
    query {
        Processes.update({ Processes.id eq processId }) {
            it[artifactsDir] = null
            if (retainedTruncated != null) {
                it[Processes.retainedTruncated] = retainedTruncated
            }
        }
    }
}

/**
 * Marks a row's workspace sweep as done while keeping artifacts_dir set —
 * used when retained files stay in place, so the artifact routes keep
 * resolving them. See ADR-0053.
 */
suspend fun ProcessRepository.markWorkspacePurged(
    processId: Int,
    retainedTruncated: Boolean,
) {
    query {
        Processes.update({ Processes.id eq processId }) {
            it[workspacePurgedAt] = System.currentTimeMillis() / 1000
            it[Processes.retainedTruncated] = retainedTruncated
        }
    }
}

/**
 * True when a row other than [excludeProcessId] references [artifactsDir], a workspace shared via a fixed
 * `workspace.path`. With [activeOnly] only CREATED/WORKING siblings count, so a finished run cannot block
 * the end-of-run cleanup. See ADR-0048 (2026-10-06 amendment).
 */
suspend fun ProcessRepository.isArtifactsDirShared(
    artifactsDir: String,
    excludeProcessId: Int,
    activeOnly: Boolean = false,
): Boolean = read { artifactsDirIsShared(artifactsDir, excludeProcessId, activeOnly) }

// The sharing predicate inside a transaction: the read path above and the delete claim below must agree on
// what "shared" means, and the claim cannot reach the read handle while it holds the write lock.
private fun JdbcTransaction.artifactsDirIsShared(
    artifactsDir: String,
    excludeProcessId: Int,
    activeOnly: Boolean,
): Boolean =
    Processes
        .select(Processes.id)
        .where {
            val shared = (Processes.artifactsDir eq artifactsDir) and (Processes.id neq excludeProcessId)
            if (activeOnly) shared and (Processes.status inList ACTIVE_STATUS_NAMES) else shared
        }.limit(1)
        .any()

/** The cleanup guard's liveness predicate — [isArtifactsDirShared] restricted to live siblings. */
suspend fun ProcessRepository.isWorkspaceSharedWithActiveRun(
    artifactsDir: String,
    excludeProcessId: Int,
): Boolean = isArtifactsDirShared(artifactsDir, excludeProcessId, activeOnly = true)

/** What [ProcessRepository.claimWorkspaceDelete] did with a workspace dir. */
enum class WorkspaceDeleteClaim {
    /** The path is claimed for this attempt: delete the directory, then settle the claim. */
    CLAIMED,

    /** Another process row references the path: no claim is held, and nothing may be deleted. */
    SHARED,

    /** [ProcessRepository.claimWorkspaceDelete]'s bound is reached: no claim is held, nothing is deleted. */
    FULL,
}

/** A claim a failed or interrupted delete left: the artifacts dir to delete again and the run evicted for it. */
data class HeldWorkspaceDelete(
    val artifactsDir: java.nio.file.Path,
    val evictedProcessId: Int,
)

/**
 * Claims [artifactsDir] for deletion by [evictedProcessId] in one write transaction: the shared-dir check and the claim are one
 * step, so a run registering that path cannot slip between them (CWE-367). A claim no attempt holds is taken over by this one.
 * [maxPending] bounds the table, counted inside this transaction (ADR-0051). See ADR-0048 (2026-11-24 amendment).
 */
suspend fun ProcessRepository.claimWorkspaceDelete(
    artifactsDir: String,
    evictedProcessId: Int,
    maxPending: Int,
): WorkspaceDeleteClaim =
    query {
        val held = workspaceDeleteIsClaimed(artifactsDir)
        if (!held && workspaceDeleteCountTx() >= maxPending.toLong()) return@query WorkspaceDeleteClaim.FULL
        if (artifactsDirIsShared(artifactsDir, evictedProcessId, activeOnly = false)) {
            // Another run owns the path now: nothing is left to delete, and a held claim is stale.
            WorkspacePendingDeletes.deleteWhere { WorkspacePendingDeletes.path eq artifactsDir }
            return@query WorkspaceDeleteClaim.SHARED
        }
        if (held) {
            WorkspacePendingDeletes.update({ WorkspacePendingDeletes.path eq artifactsDir }) {
                it[inFlight] = true
                it[WorkspacePendingDeletes.evictedProcessId] = evictedProcessId
            }
        } else {
            WorkspacePendingDeletes.insert {
                it[path] = artifactsDir
                it[WorkspacePendingDeletes.evictedProcessId] = evictedProcessId
                it[inFlight] = true
                it[createdAt] = System.currentTimeMillis() / 1000
            }
        }
        WorkspaceDeleteClaim.CLAIMED
    }

/**
 * Settles the claim on [artifactsDir] after an attempt: [deleted] drops the row (the directory is gone, or
 * the path is refused for good), else the row stays for the next sweep with its attempt no longer in
 * flight. See ADR-0048 (2026-11-24 amendment).
 */
suspend fun ProcessRepository.settleWorkspaceDelete(
    artifactsDir: String,
    deleted: Boolean,
) {
    query {
        if (deleted) {
            WorkspacePendingDeletes.deleteWhere { WorkspacePendingDeletes.path eq artifactsDir }
        } else {
            WorkspacePendingDeletes.update({ WorkspacePendingDeletes.path eq artifactsDir }) {
                it[inFlight] = false
            }
        }
    }
}

/** The claims no attempt holds, oldest first, at most [limit]: what the workspace purge sweep retries. */
suspend fun ProcessRepository.heldWorkspaceDeletes(limit: Int): List<HeldWorkspaceDelete> =
    read {
        WorkspacePendingDeletes
            .select(WorkspacePendingDeletes.path, WorkspacePendingDeletes.evictedProcessId)
            .where { WorkspacePendingDeletes.inFlight eq false }
            // `path` breaks the tie between claims recorded in the same second, so the retry order is stable.
            .orderBy(
                WorkspacePendingDeletes.createdAt to SortOrder.ASC,
                WorkspacePendingDeletes.path to SortOrder.ASC,
            ).limit(limit)
            .map {
                HeldWorkspaceDelete(
                    Path(it[WorkspacePendingDeletes.path]),
                    it[WorkspacePendingDeletes.evictedProcessId],
                )
            }
    }

/** How many claims the table holds, in flight or not. */
suspend fun ProcessRepository.pendingWorkspaceDeleteCount(): Int = read { workspaceDeleteCountTx().toInt() }

/**
 * Clears the in-flight mark on every claim: no delete is running in a process that just started, so a row
 * still marked is one a crash interrupted and the sweep must retry. Called once at boot. See ADR-0048 (2026-11-24 amendment).
 */
suspend fun ProcessRepository.resetInFlightWorkspaceDeletes() {
    query {
        WorkspacePendingDeletes.update({ WorkspacePendingDeletes.inFlight eq true }) {
            it[inFlight] = false
        }
    }
}

// The claim row for one path inside a transaction: `path` is the primary key, so this is one seek.
private fun JdbcTransaction.workspaceDeleteIsClaimed(artifactsDir: String): Boolean =
    WorkspacePendingDeletes
        .select(WorkspacePendingDeletes.path)
        .where { WorkspacePendingDeletes.path eq artifactsDir }
        .limit(1)
        .any()

// The claim table's size inside a transaction; the table is bounded by the claim's own count check.
private fun JdbcTransaction.workspaceDeleteCountTx(): Long = WorkspacePendingDeletes.selectAll().count()

suspend fun ProcessRepository.remove(processId: Int) {
    query { deleteProcessRowsTx(processId) }
}

/**
 * At capacity, deletes the oldest terminal row and returns it, else null, inside
 * [ProcessRepository.obtainProcessId]'s INSERT transaction. Throws [ProcessManagerAtCapacityException]
 * when every row is live.
 */
internal fun evictForCapacityTx(maxProcesses: Int): ProcessRepository.EvictedProcess? {
    val count = Processes.selectAll().count()
    if (count < maxProcesses.toLong()) return null
    // Only the columns read below: this runs under the INSERT's write lock on every
    // start at capacity, and the row's history/executions/runner_state blobs are dead weight.
    val victim =
        Processes
            .select(Processes.id, Processes.agentName, Processes.status, Processes.artifactsDir)
            .where { Processes.status inList TERMINAL_STATUS_NAMES }
            .orderBy(Processes.id to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
            ?: throw ProcessManagerAtCapacityException(maxProcesses)
    val victimId = victim[Processes.id]
    val evicted =
        ProcessRepository.EvictedProcess(
            victimId,
            victim[Processes.agentName],
            ProcessStatus.valueOf(victim[Processes.status]),
            victim[Processes.artifactsDir]?.let { Path(it) },
        )
    deleteProcessRowsTx(victimId)
    return evicted
}

// Co-transactional with the row delete, on record purge and capacity eviction alike, so no tag, input or
// run-message row outlives its run. See ADR-0020 (2026-09-29 amendment), ADR-0060, ADR-0097.
private fun deleteProcessRowsTx(processId: Int) {
    ProcessInputs.deleteWhere { ProcessInputs.processId eq processId }
    ProcessTags.deleteWhere { ProcessTags.processId eq processId }
    RunMessages.deleteWhere { RunMessages.processId eq processId }
    Processes.deleteWhere { Processes.id eq processId }
}
