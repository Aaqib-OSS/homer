package homer.app.infra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Default implementation of FileOperations
 * Wraps Kotlin Path extension functions
 */
class LocalFileOperations internal constructor(
    private val openTimeoutMs: Long,
    private val beforeOpen: (Path) -> Unit,
) : FileOperations {
    // Test seam: [beforeOpen] runs on the pool thread before every readTextBounded open.
    constructor() : this(OPEN_TIMEOUT_MS, {})

    private val logger = LoggerFactory.getLogger(LocalFileOperations::class.java)

    override fun fileSize(path: Path): Long =
        // NOFOLLOW: a stat over an agent-writable path must report the link's own
        // size, never the target's — else it is a size/existence oracle for host
        // files a swapped symlink points at. Security follow-up; root §12.
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()

    override fun readText(path: Path): String = path.readText()

    override fun readTextBounded(
        path: Path,
        maxBytes: Long,
    ): String? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        // CWE-367/CWE-400: open and read run on the bounded, timed pool over an interruptible
        // channel, so a FIFO swapped in after the check cannot pin the caller.
        var failure: IOException? = null
        val text =
            try {
                if (PathSafety.isLinkedOrSpecial(path)) null else submitBounded(path, "read") { readCapped(path, maxBytes) }
            } catch (e: IOException) {
                failure = e
                null
            }
        if (text == null) logger.debug("refusing a linked, non-regular, oversized or unreadable file in {}", path.parent, failure)
        return text
    }

    private fun readCapped(
        path: Path,
        maxBytes: Long,
    ): String? {
        beforeOpen(path)
        return FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            readWhole(Channels.newInputStream(it), maxBytes)
        }
    }

    override fun fileExists(path: Path): Boolean = path.exists()

    override suspend fun streamFile(
        path: Path,
        outputStream: OutputStream,
        bufferSize: Int,
    ) {
        withContext(Dispatchers.IO) {
            // Closes the resolve-vs-open window on a hard link: ArtifactService (and
            // callers like it) check isLinkedOrSpecial once at resolve, but nlink isn't
            // covered by O_NOFOLLOW, so re-check it here too, immediately before the
            // open. Residual: a link planted between this check and the open(2) below
            // still wins that race — same-UID, ADR-0047. CWE-59.
            if (PathSafety.isLinkedOrSpecial(path)) {
                throw IOException("Refusing to read non-regular file: $path")
            }
            // The open itself is O_NOFOLLOW — a symlink swapped in after the
            // pre-check fails the open instead of being followed.
            openBounded(path) { path.inputStream(LinkOption.NOFOLLOW_LINKS) }.use { input ->
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    outputStream.flush()
                }
            }
        }
    }

    override fun dirExists(path: Path): Boolean = path.exists() && path.isDirectory()

    override fun listRegularFiles(
        dir: Path,
        limit: Int,
    ): List<Path> =
        Files.walk(dir).use { stream ->
            // NOFOLLOW_LINKS: a symlink is not a regular file, so a symlink an agent
            // drops in its artifacts dir is excluded rather than listed with its
            // target's size/mtime. Files.walk already does not descend dir symlinks.
            // limit short-circuits the lazy walk so an oversized tree is never
            // fully materialized in memory (see ArtifactService's cap).
            stream
                .filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
                .limit(limit.toLong())
                .toList()
        }

    override fun lastModifiedEpochSecond(path: Path): Long =
        Files
            .readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .lastModifiedTime()
            .toInstant()
            .epochSecond

    /**
     * Opens [path] for reading: re-assert a regular file (NOFOLLOW) before the open, then run the
     * blocking `open(2)` on a **bounded** daemon pool capped at [openTimeoutMs] per open. The [open]
     * lambda opens with `LinkOption.NOFOLLOW_LINKS`, so the symlink check is atomic with the open; the
     * pre-check and pool also catch a same-UID swap to a FIFO/device, which `O_NOFOLLOW` misses. A
     * blocked open cannot exhaust threads: the pool ([MAX_OPEN_THREADS], synchronous handoff) bounds
     * leaked threads, and once saturated further opens fail fast ([RejectedExecutionException] → IOException).
     */
    private fun <T : AutoCloseable> openBounded(
        path: Path,
        open: () -> T,
    ): T {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Refusing to read non-regular file: $path")
        }
        return submitBounded(path, "open", open)
    }

    /**
     * Runs [task] on the bounded pool and waits at most [openTimeoutMs]; a full pool, a timeout
     * (the task is then interrupted) and a task failure all surface as an [IOException].
     */
    @Suppress("SwallowedException") // unwraps the ExecutionException to the task's own failure
    private fun <T> submitBounded(
        path: Path,
        what: String,
        task: () -> T,
    ): T {
        val future =
            try {
                OPEN_EXECUTOR.submit(Callable { task() })
            } catch (_: RejectedExecutionException) {
                throw IOException("Artifact reader pool saturated; refusing to $what $path")
            }
        return try {
            future.get(openTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw IOException("Timed out trying to $what artifact (possible non-regular file): $path")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException ?: IOException("Failed to $what $path", e.cause))
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 10_000L
        private const val MAX_OPEN_THREADS = 8

        // Bounded: a SynchronousQueue with max MAX_OPEN_THREADS means a submit is
        // accepted only if a thread is immediately free or one can still be spawned
        // under the cap, else it is rejected. Idle threads are reaped after 30s, so
        // normal load holds none; only opens blocked on a FIFO retain a slot, and
        // they can never exceed the cap.
        private val OPEN_EXECUTOR: ExecutorService =
            ThreadPoolExecutor(
                0,
                MAX_OPEN_THREADS,
                30L,
                TimeUnit.SECONDS,
                SynchronousQueue(),
            ) { r -> Thread(r, "artifact-open").apply { isDaemon = true } }
    }
}
