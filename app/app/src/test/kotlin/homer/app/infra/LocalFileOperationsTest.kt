package homer.app.infra

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalFileOperationsTest {
    private val ops = LocalFileOperations()

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fileSize returns correct byte count`() {
        val file = tempDir.resolve("test.txt")
        file.writeText("hello")
        assertEquals(5L, ops.fileSize(file))
    }

    @Test
    fun `readText returns file content`() {
        val file = tempDir.resolve("test.txt")
        file.writeText("hello world")
        assertEquals("hello world", ops.readText(file))
    }

    @Test
    fun `fileExists returns true for existing file, false for missing`() {
        val file = tempDir.resolve("exists.txt")
        file.writeText("x")
        assertTrue(ops.fileExists(file))
        assertFalse(ops.fileExists(tempDir.resolve("missing.txt")))
    }

    @Test
    fun `streamFile writes all bytes to output stream`() =
        runTest {
            val content = "stream me"
            val file = tempDir.resolve("stream.txt")
            file.writeText(content)
            val out = ByteArrayOutputStream()
            ops.streamFile(file, out)
            assertEquals(content, out.toString(Charsets.UTF_8.name()))
        }

    @Test
    fun `streamFile surfaces a failed open as the open's own IOException`() =
        runTest {
            val missing = tempDir.resolve("missing.txt")
            val out = ByteArrayOutputStream()
            assertFailsWith<NoSuchFileException> { ops.streamFile(missing, out) }
            assertEquals(0, out.size())
        }

    @Test
    fun `streamFile refuses to follow a symlink at open time`() =
        runTest {
            val outsideDir =
                java.nio.file.Files
                    .createTempDirectory("lfo-outside")
            val target = outsideDir.resolve("secret.txt")
            target.writeText("host secret")
            val link = tempDir.resolve("link.txt")
            java.nio.file.Files
                .createSymbolicLink(link, target)

            val out = ByteArrayOutputStream()
            assertFailsWith<IOException> { ops.streamFile(link, out) }
            assertEquals(0, out.size())

            outsideDir.toFile().deleteRecursively()
        }

    @Test
    fun `streamFile refuses a hard-linked file at open time`() =
        runTest {
            val original = tempDir.resolve("original.txt")
            original.writeText("secret bytes")
            val linked = tempDir.resolve("linked.txt")
            Files.createLink(linked, original)

            val out = ByteArrayOutputStream()
            assertFailsWith<IOException> { ops.streamFile(linked, out) }
            assertEquals(0, out.size())
        }

    @Test
    fun `streamFile refuses a FIFO instead of blocking on open`() =
        runTest {
            assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
            val fifo = tempDir.resolve("pipe")
            assumeTrue(
                runCatching { ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0 }.getOrDefault(false),
                "mkfifo unavailable",
            )
            assertFailsWith<IOException> { ops.streamFile(fifo, ByteArrayOutputStream()) }
        }

    @Test
    fun `listRegularFiles excludes symlinks`() {
        val real = tempDir.resolve("real.txt")
        real.writeText("x")
        val outsideDir =
            java.nio.file.Files
                .createTempDirectory("lfo-outside")
        val outside = outsideDir.resolve("secret.txt")
        outside.writeText("s")
        java.nio.file.Files
            .createSymbolicLink(tempDir.resolve("link.txt"), outside)

        val names = ops.listRegularFiles(tempDir, Int.MAX_VALUE).map { it.fileName.toString() }

        assertTrue(names.contains("real.txt"), "regular files are listed")
        assertFalse(names.contains("link.txt"), "symlinks are not listed with their target's metadata")

        outsideDir.toFile().deleteRecursively()
    }

    @Test
    fun `fileSize does not follow a symlink to report the target size`() {
        val outsideDir = Files.createTempDirectory("lfo-outside")
        try {
            val target = Files.writeString(outsideDir.resolve("secret.bin"), "0123456789") // 10 bytes
            val link = tempDir.resolve("link")
            Files.createSymbolicLink(link, target)
            // NOFOLLOW: the symlink's own size is not the 10-byte target size.
            assertNotEquals(10L, ops.fileSize(link))
        } finally {
            outsideDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `lastModifiedEpochSecond does not follow a symlink`() {
        val outsideDir = Files.createTempDirectory("lfo-outside")
        try {
            val target = Files.writeString(outsideDir.resolve("t.bin"), "x")
            // backdate the target far in the past
            Files.setLastModifiedTime(target, FileTime.fromMillis(0))
            val link = tempDir.resolve("link2")
            Files.createSymbolicLink(link, target)
            // The link was just created, so its own mtime is recent, not epoch 0.
            assertTrue(ops.lastModifiedEpochSecond(link) > 0L)
        } finally {
            outsideDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `listRegularFiles caps the result at limit`() {
        repeat(5) { tempDir.resolve("f$it.txt").writeText("x") }

        val listed = ops.listRegularFiles(tempDir, 3)

        assertEquals(3, listed.size)
    }

    @Test
    fun `readTextBounded returns null within the open timeout when a FIFO replaces the file after the check`() {
        val file = tempDir.resolve("langgraph.json")
        file.writeText("{}")
        val swapping =
            LocalFileOperations(openTimeoutMs = 300) { path ->
                Files.delete(path)
                val made = ProcessBuilder("mkfifo", path.toString()).start().waitFor() == 0
                check(made) { "mkfifo failed" }
            }
        assumeTrue(runCatching { ProcessBuilder("mkfifo", "--help").start().waitFor() }.isSuccess, "needs mkfifo")

        val started = System.nanoTime()
        val result = swapping.readTextBounded(file, maxBytes = 1024)
        assertFalse(Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the swap must have happened")
        assertTrue(Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the swap must have happened")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(null, result)
        assertTrue(elapsedMs < 5_000, "the blocked open must time out, took ${elapsedMs}ms")
        // Release the pool thread parked in open(2) on the FIFO; its slot must not outlive the test.
        Files.newOutputStream(file).close()
    }

    @Test
    fun `readTextBounded returns null within the timeout when a held-open FIFO has no data, and the pool slot comes back`() {
        val fifo = tempDir.resolve("held.json")
        assumeTrue(runCatching { ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0 }.getOrDefault(false), "needs mkfifo")
        val regular = tempDir.resolve("ok.json").also { it.writeText("{}") }
        // A writer holds the FIFO open and writes nothing, so open succeeds and the read blocks.
        val writer =
            java.nio.channels.FileChannel
                .open(fifo, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)
        try {
            val hook =
                LocalFileOperations(openTimeoutMs = 500) { path ->
                    if (path == regular) {
                        Files.delete(path)
                        Files.move(fifo, path)
                    }
                }
            val started = System.nanoTime()
            val result = hook.readTextBounded(regular, maxBytes = 1024)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(null, result)
            assertTrue(elapsedMs < 5_000, "the blocked read must time out, took ${elapsedMs}ms")
            val normal = tempDir.resolve("normal.json").also { it.writeText("{}") }
            assertEquals("{}", ops.readTextBounded(normal, maxBytes = 1024))
        } finally {
            writer.close()
        }
    }

    @Test
    fun `readTextBounded returns null for a missing path`() {
        assertEquals(null, ops.readTextBounded(tempDir.resolve("missing.json"), maxBytes = 1024))
    }

    @Test
    fun `readTextBounded returns null for an unreadable file instead of throwing`() {
        val file = tempDir.resolve("locked.json").also { it.writeText("{}") }
        val locked = file.toFile().setReadable(false, false)
        assumeTrue(locked && !Files.isReadable(file), "needs a non-root user")
        try {
            assertEquals(null, ops.readTextBounded(file, maxBytes = 1024))
        } finally {
            file.toFile().setReadable(true, false)
        }
    }

    @Test
    fun `readTextBounded is refused when its pool is full, and the slot comes back`() {
        val file = tempDir.resolve("ok.json").also { it.writeText("{}") }
        val release = java.util.concurrent.CountDownLatch(1)
        val parked = java.util.concurrent.CountDownLatch(1)
        val pool =
            java.util.concurrent.ThreadPoolExecutor(
                0,
                1,
                1,
                java.util.concurrent.TimeUnit.SECONDS,
                java.util.concurrent.SynchronousQueue(),
            )
        val holding =
            LocalFileOperations(60_000, pool) { _ ->
                parked.countDown()
                release.await()
            }
        val parkedRead =
            java.util.concurrent.CompletableFuture
                .supplyAsync { holding.readTextBounded(file, maxBytes = 1024) }
        try {
            assertTrue(parked.await(5, java.util.concurrent.TimeUnit.SECONDS), "the one slot must be parked")

            val refused =
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively<String?>(java.time.Duration.ofSeconds(5)) {
                    holding.readTextBounded(file, maxBytes = 1024)
                }

            assertEquals(null, refused)
        } finally {
            release.countDown()
            runCatching { parkedRead.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            pool.shutdownNow()
        }
        assertTrue(parkedRead.isDone, "no read may stay blocked")
        assertEquals("{}", ops.readTextBounded(file, maxBytes = 1024))
    }
}
