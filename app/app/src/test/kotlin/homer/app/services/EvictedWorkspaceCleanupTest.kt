package homer.app.services

import homer.app.fixtures.TestFixtures
import homer.app.infra.PathSafety
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvictedWorkspaceCleanupTest {
    /** A delete that fails with an IO error for its first [failures] calls, then deletes for real. */
    private class FlakyDelete(
        private var failures: Int,
    ) {
        var calls = 0

        fun delete(
            root: Path,
            dir: Path,
        ) {
            calls++
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
        shared: suspend (Path, Int) -> Boolean = { _, _ -> false },
    ) = EvictedWorkspaceCleanup(root, TestFixtures.mockLogger(), shared, delete::delete)

    @Test
    fun `a failed delete is held and the next sweep deletes the directory`(
        @TempDir root: Path,
    ) = runTest {
        val artifacts = workspace(root, "run1")
        val delete = FlakyDelete(failures = 1)
        val cleanup = cleanup(root, delete)

        cleanup.delete(1, artifacts)

        assertTrue(Files.exists(root.resolve("run1")))
        assertEquals(1, cleanup.pendingCount)

        cleanup.retryPending()

        assertFalse(Files.exists(root.resolve("run1")), "the retried delete must not leave the directory orphaned")
        assertEquals(0, cleanup.pendingCount)
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

        assertEquals(1, cleanup.pendingCount)
        cleanup.retryPending()
        assertFalse(Files.exists(root.resolve("run1")))
    }

    @Test
    fun `a held delete whose directory another run took up is dropped without deleting it`(
        @TempDir root: Path,
    ) = runTest {
        val artifacts = workspace(root, "shared")
        var sharedNow = false
        val cleanup = cleanup(root, FlakyDelete(failures = 1)) { _, _ -> sharedNow }

        cleanup.delete(1, artifacts)
        sharedNow = true
        cleanup.retryPending()

        assertTrue(Files.exists(root.resolve("shared")))
        assertEquals(0, cleanup.pendingCount)
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
        assertEquals(0, cleanup.pendingCount)
    }

    @Test
    fun `held deletes stop at the bound`(
        @TempDir root: Path,
    ) = runTest {
        val cleanup = cleanup(root, FlakyDelete(failures = Int.MAX_VALUE))

        repeat(EvictedWorkspaceCleanup.MAX_PENDING + 1) { cleanup.delete(it, root.resolve("run$it/artifacts")) }

        assertEquals(EvictedWorkspaceCleanup.MAX_PENDING, cleanup.pendingCount)
    }
}
