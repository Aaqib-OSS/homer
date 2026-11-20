package homer.app.services

import homer.app.infra.ArtifactRetention
import homer.app.infra.PathSafety
import homer.app.infra.coRunCatching
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.Logger
import java.nio.file.Path

class WorkspacePurgeService(
    private val processManager: ProcessManager,
    private val questionsService: QuestionsService,
    private val workspaceRoot: Path,
    private val maxRetainedArtifactBytes: Long,
    private val logger: Logger,
) {
    private val terminalStatuses =
        setOf(
            ProcessStatus.FINISHED,
            ProcessStatus.FAILED,
            ProcessStatus.KILLED,
            // Stamped at startup reconciliation, so a restart's casualties are swept too. ADR-0048.
            ProcessStatus.UNKNOWN,
        )

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job =
            scope.launch {
                while (isActive) {
                    delay(60_000L)
                    purgeExpired()
                }
            }
    }

    fun stop() {
        job?.cancel()
    }

    /**
     * Retries the held eviction deletes, then runs two sweeps over due rows: `workspace.purgeAfter` for the directory and
     * `workspace.retain.recordFor` for the row, which outlives the directory. See ADR-0048 and ADR-0053.
     */
    suspend fun purgeExpired() {
        val now = System.currentTimeMillis() / 1000
        coRunCatching { processManager.retryEvictedWorkspaceDeletes() }
            .onFailure { e -> logger.error("Failed to retry evicted workspace deletes: {}", e.message, e) }
        purgeWorkspaces(now)
        purgeRecords(now)
    }

    private suspend fun purgeWorkspaces(now: Long) {
        for (process in processManager.getWorkspacePurgeableProcesses(terminalStatuses, now)) {
            val artifactsDir = process.artifactsDir ?: continue
            coRunCatching {
                purgeWorkspace(process, artifactsDir)
            }.onFailure { e ->
                logger.error("Failed to purge workspace of process {}: {}", process.id, e.message, e)
            }
        }
    }

    /** Deletes, prunes or (when shared) leaves [process]'s workspace at [artifactsDir]. */
    private suspend fun purgeWorkspace(
        process: ProcessInfo,
        artifactsDir: Path,
    ) {
        var retainedCount = 0
        if (processManager.isArtifactsDirShared(artifactsDir, process.id)) {
            skipSharedWorkspace(process, artifactsDir)
        } else if (process.retainArtifacts.isNotEmpty()) {
            retainedCount = pruneToRetained(process, artifactsDir)
        } else {
            // artifactsDir is read back from the database, so the delete is containment-checked
            // against the workspace root (CWE-22, ADR-0053).
            artifactsDir.parent?.let { PathSafety.deleteContainedRecursively(workspaceRoot, it) }
            // Cleared only after the delete: `artifacts_dir` is the swept marker, so a failed delete is retried (ADR-0048).
            // Clearing also drops the row from another process's shared-dir check, as removing the row would.
            processManager.clearArtifactsDir(process.id)
        }
        if (retainedCount > 0) {
            logger.info(
                "Purged workspace of process {} (agent: {}), retained {} artifact file(s)",
                process.id,
                process.agentName,
                retainedCount,
            )
        } else {
            logger.info("Purged workspace of process {} (agent: {})", process.id, process.agentName)
        }
    }

    /** Leaves a workspace another process still references, only dropping this row's claim on it. */
    private suspend fun skipSharedWorkspace(
        process: ProcessInfo,
        artifactsDir: Path,
    ) {
        logger.info(
            "Skipping workspace deletion for process {} (agent: {}): {} is still referenced by another process",
            process.id,
            process.agentName,
            artifactsDir.parent,
        )
        if (process.retainArtifacts.isNotEmpty()) {
            // Retention needs a per-run directory: pruning a shared dir would
            // delete files other runs still serve. ADR-0053 documents this.
            logger.warn(
                "retain.artifacts skipped for process {} (agent: {}): workspace is shared",
                process.id,
                process.agentName,
            )
        }
        processManager.clearArtifactsDir(process.id)
    }

    /** Prunes the workspace down to the files `retain.artifacts` keeps; returns how many were kept. */
    private suspend fun pruneToRetained(
        process: ProcessInfo,
        artifactsDir: Path,
    ): Int {
        val result =
            ArtifactRetention.prune(workspaceRoot, artifactsDir, process.retainArtifacts, maxRetainedArtifactBytes)
        if (result.keptFiles == 0) {
            // Every match can have overflowed the cap: record retainedTruncated even though
            // this clears artifactsDir like the nothing-matched case. See ADR-0053.
            processManager.clearArtifactsDir(process.id, retainedTruncated = result.truncated.takeIf { it })
            return 0
        }
        // artifacts_dir stays set — the artifact routes read it to serve the
        // retained files. workspace_purged_at takes over as the swept marker.
        processManager.markWorkspacePurged(process.id, result.truncated)
        return result.keptFiles
    }

    private suspend fun purgeRecords(now: Long) {
        for (process in processManager.getRecordPurgeableProcesses(terminalStatuses, now)) {
            coRunCatching {
                // A retained row owns its pruned directory: delete it before the row, so a failed
                // delete keeps the row for the next sweep (ADR-0048, ADR-0053).
                process.artifactsDir?.let { artifactsDir ->
                    if (processManager.isArtifactsDirShared(artifactsDir, process.id)) {
                        logger.info(
                            "Skipping retained-artifact deletion for process {}: {} is still referenced by another process",
                            process.id,
                            artifactsDir.parent,
                        )
                    } else {
                        artifactsDir.parent?.let { PathSafety.deleteContainedRecursively(workspaceRoot, it) }
                    }
                }
                processManager.removeProcess(process.id)
                questionsService.removeForProcess(process.id)
                logger.info("Purged process record {} (agent: {})", process.id, process.agentName)
            }.onFailure { e ->
                logger.error("Failed to purge process record {}: {}", process.id, e.message, e)
            }
        }
    }
}
