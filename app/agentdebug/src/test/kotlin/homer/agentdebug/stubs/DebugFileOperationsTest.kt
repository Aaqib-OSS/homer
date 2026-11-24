package homer.agentdebug.stubs

import homer.app.infra.ArtifactTooLargeException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DebugFileOperationsTest {
    private val ops = DebugFileOperations()

    @Test
    fun `listRegularFiles walks the tree for files only, up to the limit`(
        @TempDir dir: Path,
    ) {
        dir.resolve("a.txt").writeText("a")
        dir
            .resolve("sub")
            .createDirectories()
            .resolve("b.txt")
            .writeText("b")

        assertEquals(setOf(dir.resolve("a.txt"), dir.resolve("sub/b.txt")), ops.listRegularFiles(dir, limit = 10).toSet())
        assertEquals(1, ops.listRegularFiles(dir, limit = 1).size)
    }

    @Test
    fun `listRegularFiles of a missing path or a file is empty`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("f.txt").also { it.writeText("x") }

        assertEquals(emptyList(), ops.listRegularFiles(dir.resolve("missing"), limit = 10))
        assertEquals(emptyList(), ops.listRegularFiles(file, limit = 10))
    }

    @Test
    fun `readTextBounded reads a small file and refuses one over the bound`(
        @TempDir dir: Path,
    ) {
        val file = dir.resolve("f.txt").also { it.writeText("abcd") }

        assertEquals("abcd", ops.readTextBounded(file, maxBytes = 4))
        assertNull(ops.readTextBounded(file, maxBytes = 3))
    }

    @Test
    fun `streamFile copies through the local operations, so a file over the cap is refused`(
        @TempDir dir: Path,
    ) = runTest {
        val file = dir.resolve("f.txt").also { it.writeText("abcd") }
        val out = ByteArrayOutputStream()

        ops.streamFile(file, maxBytes = 4, respond = { write -> write(out) })

        assertEquals("abcd", out.toString(Charsets.UTF_8.name()))

        var opened = false
        assertFailsWith<ArtifactTooLargeException> {
            ops.streamFile(
                file,
                maxBytes = 3,
                respond = { write ->
                    opened = true
                    write(out)
                },
            )
        }
        assertFalse(opened, "the caller's stream must not open for a file over the cap")
    }
}
