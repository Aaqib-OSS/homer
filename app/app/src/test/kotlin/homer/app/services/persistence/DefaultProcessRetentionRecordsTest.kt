package homer.app.services.persistence

import homer.app.fixtures.PersistenceTestSupport
import homer.app.infra.ProcessJobRegistry
import homer.app.models.NewRunRecord
import homer.app.models.ProcessStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultProcessRetentionRecordsTest {
    private val repository = ProcessRepository(PersistenceTestSupport.tempDatabase(), ProcessJobRegistry())
    private val retention = DefaultProcessRetentionRecords(repository)
    private val terminal = setOf(ProcessStatus.FINISHED)

    private suspend fun finishedRun(artifactsDir: Path): Int {
        val id = repository.obtainProcessId(NewRunRecord("a"), 100).processId
        repository.setArtifactsDir(id, artifactsDir.toString())
        repository.transitionStatus(id, ProcessStatus.FINISHED, 1, null)
        return id
    }

    @Test
    fun `a run stamped due is workspace-purgeable, then record-purgeable once its dir is cleared`() =
        runTest {
            val id = finishedRun(Path.of("/ws/1/artifacts"))
            retention.setPurgeAt(id, purgeAt = 10)

            assertEquals(listOf(id), retention.getWorkspacePurgeableProcesses(terminal, 10).map { it.id })
            assertEquals(emptyList(), retention.getRecordPurgeableProcesses(terminal, 10))

            retention.clearArtifactsDir(id)

            assertEquals(emptyList(), retention.getWorkspacePurgeableProcesses(terminal, 10))
            assertEquals(listOf(id), retention.getRecordPurgeableProcesses(terminal, 10).map { it.id })
        }

    @Test
    fun `a workspace marked purged keeps its dir and leaves the workspace sweep`() =
        runTest {
            val id = finishedRun(Path.of("/ws/1/artifacts"))
            retention.setPurgeAt(id, purgeAt = 10, recordPurgeAt = 20, retainArtifacts = listOf("*.txt"))

            retention.markWorkspacePurged(id, retainedTruncated = true)

            assertEquals(emptyList(), retention.getWorkspacePurgeableProcesses(terminal, 10))
            val info = repository.getProcessInfo(id)
            assertEquals(Path.of("/ws/1/artifacts"), info?.artifactsDir)
            assertEquals(listOf("*.txt"), info?.retainArtifacts)
            assertEquals(true, info?.retainedTruncated)
        }

    @Test
    fun `clearing the dir with no truncation flag leaves the flag unset`() =
        runTest {
            val id = finishedRun(Path.of("/ws/1/artifacts"))

            retention.clearArtifactsDir(id)

            assertNull(repository.getProcessInfo(id)?.artifactsDir)
            assertNull(repository.getProcessInfo(id)?.retainedTruncated)
        }

    @Test
    fun `a delete claim runs from claim to settle to boot reset through the port`() =
        runTest {
            val dir = Path.of("/ws/1/artifacts")

            assertEquals(
                WorkspaceDeleteClaim.CLAIMED,
                retention.claimWorkspaceDelete(dir, evictedProcessId = 7, maxPending = 8, attemptToken = "a"),
            )
            // An attempt is in flight, so the sweep has nothing to retry yet.
            assertEquals(emptyList(), retention.heldWorkspaceDeletes(limit = 8))
            assertEquals(1, retention.pendingWorkspaceDeleteCount())

            retention.resetInFlightWorkspaceDeletes()

            assertEquals(listOf(HeldWorkspaceDelete(dir, 7)), retention.heldWorkspaceDeletes(limit = 8))

            retention.settleWorkspaceDelete(dir, attemptToken = "a", deleted = false)
            assertEquals(1, retention.pendingWorkspaceDeleteCount())

            retention.settleWorkspaceDelete(dir, attemptToken = "a", deleted = true)
            assertEquals(0, retention.pendingWorkspaceDeleteCount())
        }

    @Test
    fun `a claim an attempt already holds is not taken over by a second attempt`() =
        runTest {
            val dir = Path.of("/ws/1/artifacts")
            retention.claimWorkspaceDelete(dir, evictedProcessId = 7, maxPending = 8, attemptToken = "a")

            assertEquals(
                WorkspaceDeleteClaim.IN_FLIGHT,
                retention.claimWorkspaceDelete(dir, evictedProcessId = 8, maxPending = 8, attemptToken = "b"),
            )

            // Only the attempt holding the claim settles it: the skipped attempt's token matches no row.
            retention.settleWorkspaceDelete(dir, attemptToken = "b", deleted = true)
            assertEquals(1, retention.pendingWorkspaceDeleteCount())
            retention.settleWorkspaceDelete(dir, attemptToken = "a", deleted = true)
            assertEquals(0, retention.pendingWorkspaceDeleteCount())
        }

    @Test
    fun `a shared path at the full bound answers FULL and leaves every claim untouched`() =
        runTest {
            val shared = Path.of("/ws/shared/artifacts")
            finishedRun(shared)
            retention.claimWorkspaceDelete(
                Path.of("/ws/other/artifacts"),
                evictedProcessId = 7,
                maxPending = 1,
                attemptToken = "a",
            )

            // The bound check runs before the shared check, so the answer is FULL, not SHARED — and no
            // claim row is dropped by the shared branch's stale-claim delete.
            assertEquals(
                WorkspaceDeleteClaim.FULL,
                retention.claimWorkspaceDelete(shared, evictedProcessId = 8, maxPending = 1, attemptToken = "b"),
            )
            assertEquals(1, retention.pendingWorkspaceDeleteCount())
        }

    @Test
    fun `a stale claim at the full bound is taken over, not shed`() =
        runTest {
            val stale = Path.of("/ws/stale/artifacts")
            retention.claimWorkspaceDelete(stale, evictedProcessId = 7, maxPending = 2, attemptToken = "a")
            // The attempt that held the claim is gone, so the boot reset marks the row held — and the
            // table is then filled to its bound by other paths.
            retention.resetInFlightWorkspaceDeletes()
            retention.claimWorkspaceDelete(
                Path.of("/ws/other/artifacts"),
                evictedProcessId = 8,
                maxPending = 2,
                attemptToken = "b",
            )

            // The takeover replaces the stale row inside the bound instead of shedding the retry.
            assertEquals(
                WorkspaceDeleteClaim.CLAIMED,
                retention.claimWorkspaceDelete(stale, evictedProcessId = 9, maxPending = 2, attemptToken = "c"),
            )
            assertEquals(2, retention.pendingWorkspaceDeleteCount())

            // The takeover holds the row in flight under the new token: a later attempt of the same path
            // skips instead of taking the live claim over, and only that attempt's settle drops the row.
            assertEquals(
                WorkspaceDeleteClaim.IN_FLIGHT,
                retention.claimWorkspaceDelete(stale, evictedProcessId = 10, maxPending = 2, attemptToken = "d"),
            )
            retention.settleWorkspaceDelete(stale, attemptToken = "c", deleted = true)
            assertEquals(1, retention.pendingWorkspaceDeleteCount())
        }

    @Test
    fun `a stale claim on a shared path is dropped by the shared answer`() =
        runTest {
            val shared = Path.of("/ws/shared/artifacts")
            finishedRun(shared)
            retention.claimWorkspaceDelete(shared, evictedProcessId = 7, maxPending = 8, attemptToken = "stale")
            retention.resetInFlightWorkspaceDeletes()

            assertEquals(
                WorkspaceDeleteClaim.SHARED,
                retention.claimWorkspaceDelete(shared, evictedProcessId = 8, maxPending = 8, attemptToken = "b"),
            )
            assertEquals(0, retention.pendingWorkspaceDeleteCount())
        }

    @Test
    fun `the claim reports a shared path and a full bound through the port`() =
        runTest {
            val shared = Path.of("/ws/shared/artifacts")
            finishedRun(shared)

            assertEquals(
                WorkspaceDeleteClaim.SHARED,
                retention.claimWorkspaceDelete(shared, evictedProcessId = 7, maxPending = 8, attemptToken = "a"),
            )
            assertEquals(
                WorkspaceDeleteClaim.CLAIMED,
                retention.claimWorkspaceDelete(
                    Path.of("/ws/other/artifacts"),
                    evictedProcessId = 7,
                    maxPending = 1,
                    attemptToken = "b",
                ),
            )
            assertEquals(
                WorkspaceDeleteClaim.FULL,
                retention.claimWorkspaceDelete(
                    Path.of("/ws/third/artifacts"),
                    evictedProcessId = 7,
                    maxPending = 1,
                    attemptToken = "c",
                ),
            )
        }

    @Test
    fun `a shared dir counts every other row, the active-run check only live ones`() =
        runTest {
            val dir = Path.of("/ws/shared/artifacts")
            val finished = finishedRun(dir)
            val live = repository.obtainProcessId(NewRunRecord("a"), 100).processId
            repository.setArtifactsDir(live, dir.toString())

            assertTrue(retention.isArtifactsDirShared(dir, live))
            assertFalse(retention.isWorkspaceSharedWithActiveRun(dir, live))
            assertTrue(retention.isWorkspaceSharedWithActiveRun(dir, finished))
        }
}
