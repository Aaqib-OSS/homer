package homer.app.services

import homer.app.exceptions.WorkspacePendingDeleteException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.PathSafety
import homer.app.infra.ProcessJobRegistry
import homer.app.models.NewRunRecord
import homer.app.models.ProcessStatus
import homer.app.services.persistence.DefaultProcessRetentionRecords
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.getWorkspacePurgeableProcesses
import homer.app.services.persistence.pendingWorkspaceDeleteCount
import homer.app.services.persistence.setArtifactsDir
import homer.app.services.persistence.setPurgeAt
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvictedWorkspaceCleanupTest {
    private val logger = TestFixtures.mockLogger()
    private val repository = ProcessRepository(PersistenceTestSupport.tempDatabase(), ProcessJobRegistry())
    private val retention = DefaultProcessRetentionRecords(repository)

    /** A delete that fails with an IO error for its first [failures] calls, then deletes for real. */
    private class FlakyDelete(
        private var failures: Int,
        private val whileDeleting: suspend (Path, Path) -> Unit = { _, _ -> },
    ) {
        var calls = 0

        suspend fun delete(
            root: Path,
            dir: Path,
        ) {
            calls++
            whileDeleting(root, dir)
            if (failures-- > 0) throw IOException("busy")
            PathSafety.deleteContainedRecursively(root, dir)
        }
    }

    private fun workspace(
        root: Path,
        name: String,
    ): Path =
        root.resolve("$name/artifacts").also {
            Files.createDirectories(it)
            Files.writeString(it.resolve("cmd_0.out"), "x")
        }

    private fun cleanup(
        root: Path,
        delete: FlakyDelete,
    ) = EvictedWorkspaceCleanup(
        root,
        logger,
        retention::claimWorkspaceDelete,
        retention::settleWorkspaceDelete,
        retention::heldWorkspaceDeletes,
        retention::pendingWorkspaceDeleteCount,
        delete::delete,
    )

    /** A live run row referencing [artifacts], the shape a shared fixed-path workspace takes. */
    private suspend fun runOwning(artifacts: Path): Int =
        repository.obtainProcessId(NewRunRecord("a"), 100).processId.also {
            repository.setArtifactsDir(it, artifacts.toString())
        }

    @Test
    fun `a failed delete is held and the next sweep deletes the directory`(
        @TempDir root: Path,
    ) = runTest {
        val artifacts = workspace(root, "run1")
        val delete = FlakyDelete(failures = 1)
        val cleanup = cleanup(root, delete)

        cleanup.delete(1, artifacts)

        assertTrue(Files.exists(root.resolve("run1")))
        assertEquals(1, cleanup.pendingCount())

        cleanup.retryPending()

        assertFalse(Files.exists(root.resolve("run1")), "the retried delete must not leave the directory orphaned")
        assertEquals(0, cleanup.pendingCount())
        cleanup.retryPending()
        assertEquals(2, delete.calls, "a delete that succeeded is not retried again")
    }

    @Test
    fun `a delete that keeps failing stays held for the following sweep`(
        @TempDir root: Path,
    ) = runTest {
        val artifacts = workspace(root, "run1")
        val cleanup = cleanup(root, FlakyDelete(failures = 2))

        cleanup.delete(1, artifacts)
        cleanup.retryPending()

        assertEquals(1, cleanup.pendingCount())
        cleanup.retryPending()
        assertFalse(Files.exists(root.resolve("run1")))
        verify(exactly = 1) { logger.warn(match<String> { "retrying on every sweep" in it }, 1, "busy") }
        verify(exactly = 1) { logger.debug(match<String> { "failed again" in it }, 1, "busy") }
    }

    @Test
    fun `a run registering the directory while the delete runs is refused (CWE-367)`(
        @TempDir root: Path,
    ) = runTest {
        val artifacts = workspace(root, "run1")
        val refused = mutableListOf<Throwable>()
        // The delete stands in for the window the sweep's sharing check used to leave open: a run
        // registering the same path while the delete is running.
        val delete =
            FlakyDelete(failures = 0) { _, _ ->
                // The claim is committed before the directory is touched, so another connection sees it
                // and the registering run is refused rather than handed a directory being deleted.
                assertEquals(1, repository.pendingWorkspaceDeleteCount())
                refused +=
                    assertFailsWith<WorkspacePendingDeleteException> {
                        repository.setArtifactsDir(runOwning(artifacts), artifacts.toString())
                    }
            }
        val cleanup = cleanup(root, delete)

        cleanup.delete(7, artifacts)

        assertEquals(1, refused.size)
        assertFalse(Files.exists(root.resolve("run1")), "the evicted run's directory is still deleted")
        assertEquals(0, cleanup.pendingCount())
    }

    @Test
    fun `the delete holds no write transaction while it runs (CWE-367)`(
        @TempDir root: Path,
    ) = runTest {
        val artifacts = workspace(root, "run1")
        val purgeAt = 1_800_000_000L
        val terminal = repository.obtainProcessId(NewRunRecord("b"), 100).processId
        repository.transitionStatus(terminal, ProcessStatus.FINISHED, 1, null)
        repository.setArtifactsDir(terminal, root.resolve("other/artifacts").toString())
        // A write landing mid-delete must commit: the claim's transaction is settled before the
        // filesystem delete, so nothing holds the write lock across it (SQLite rule 3, CWE-367).
        val delete = FlakyDelete(failures = 0) { _, _ -> repository.setPurgeAt(terminal, purgeAt) }
        val cleanup = cleanup(root, delete)

        cleanup.delete(7, artifacts)

        assertEquals(
            listOf(terminal),
            repository.getWorkspacePurgeableProcesses(setOf(ProcessStatus.FINISHED), purgeAt).map { it.id },
        )
        assertFalse(Files.exists(root.resolve("run1")))
        assertEquals(0, cleanup.pendingCount())
    }

    @Test
    fun `a held delete whose directory another run took up is dropped without deleting it`(
        @TempDir root: Path,
    ) = runTest {
        val artifacts = workspace(root, "shared")
        val cleanup = cleanup(root, FlakyDelete(failures = 1))

        cleanup.delete(1, artifacts)
        assertEquals(1, cleanup.pendingCount())

        // Registering the path takes the held claim over and drops it: the run owns the directory now.
        runOwning(artifacts)

        cleanup.retryPending()

        assertTrue(Files.exists(root.resolve("shared")))
        assertEquals(0, cleanup.pendingCount())
    }

    @Test
    fun `a directory another live run references is skipped and never held`(
        @TempDir root: Path,
    ) = runTest {
        val artifacts = workspace(root, "shared")
        runOwning(artifacts)
        val cleanup = cleanup(root, FlakyDelete(failures = 0))

        cleanup.delete(9, artifacts)

        assertTrue(Files.exists(root.resolve("shared")))
        assertEquals(0, cleanup.pendingCount())
        verify(exactly = 1) { logger.info(match<String> { "still referenced by another process" in it }, 9, artifacts.parent) }
    }

    @Test
    fun `a delete refused by the containment check is never held`(
        @TempDir tmp: Path,
    ) = runTest {
        val root = tmp.resolve("root").also { Files.createDirectories(it) }
        val outside = workspace(tmp, "outside")
        val cleanup = cleanup(root, FlakyDelete(failures = 0))

        cleanup.delete(1, outside)

        assertTrue(Files.exists(tmp.resolve("outside")))
        assertEquals(0, cleanup.pendingCount())
    }

    @Test
    fun `held deletes stop at the bound`(
        @TempDir root: Path,
    ) = runTest {
        val cleanup = cleanup(root, FlakyDelete(failures = Int.MAX_VALUE))

        repeat(EvictedWorkspaceCleanup.MAX_PENDING + 1) { cleanup.delete(it, workspace(root, "run$it")) }

        assertEquals(EvictedWorkspaceCleanup.MAX_PENDING, cleanup.pendingCount())
        val shed = EvictedWorkspaceCleanup.MAX_PENDING
        assertTrue(Files.exists(root.resolve("run$shed")), "the delete past the bound is shed, leaving its directory on disk")
        verify(exactly = 1) { logger.error(match<String> { "left on disk" in it }, shed, EvictedWorkspaceCleanup.MAX_PENDING) }
    }

    @Test
    fun `concurrent failed deletes never hold more than the bound`(
        @TempDir root: Path,
    ) = runTest {
        val cleanup = cleanup(root, FlakyDelete(failures = Int.MAX_VALUE))

        withContext(Dispatchers.Default) {
            (0 until EvictedWorkspaceCleanup.MAX_PENDING * 4)
                .map { id -> async { cleanup.delete(id, root.resolve("run$id/artifacts")) } }
                .awaitAll()
        }

        assertEquals(EvictedWorkspaceCleanup.MAX_PENDING, cleanup.pendingCount())
    }
}
