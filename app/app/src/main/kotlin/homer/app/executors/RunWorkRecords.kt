package homer.app.executors

import java.nio.file.Path

/**
 * What [Process] records on its run around [AgentExecutor]: the guarded `WORKING` write of "Status
 * precedence rules" (app/CLAUDE.md), the artifacts dir, the shared-workspace probe of the cleanup guard,
 * and the step records it hands to [AgentExecutor]. Implemented by `ProcessManager`. See ADR-0083.
 */
interface RunWorkRecords :
    StepRecords,
    GuardedStatusWrite {
    /**
     * Records this run's artifacts dir; throws `WorkspacePendingDeleteException` while an evicted delete
     * holds the path, so the run fails before its workspace is created (ADR-0048).
     */
    suspend fun setArtifactsDir(
        processId: Int,
        artifactsDir: Path,
    )

    /** True when a still-live run other than [excludeProcessId] references [artifactsDir]. */
    suspend fun isWorkspaceSharedWithActiveRun(
        artifactsDir: Path,
        excludeProcessId: Int,
    ): Boolean
}
