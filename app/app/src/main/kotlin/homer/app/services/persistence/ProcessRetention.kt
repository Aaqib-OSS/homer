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
import org.jetbrains.exposed.v1.jdbc.deleteWhere
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
): Boolean =
    read {
        Processes
            .select(Processes.id)
            .where {
                val shared = (Processes.artifactsDir eq artifactsDir) and (Processes.id neq excludeProcessId)
                if (activeOnly) shared and (Processes.status inList ACTIVE_STATUS_NAMES) else shared
            }.limit(1)
            .any()
    }

/** The cleanup guard's liveness predicate — [isArtifactsDirShared] restricted to live siblings. */
suspend fun ProcessRepository.isWorkspaceSharedWithActiveRun(
    artifactsDir: String,
    excludeProcessId: Int,
): Boolean = isArtifactsDirShared(artifactsDir, excludeProcessId, activeOnly = true)

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
