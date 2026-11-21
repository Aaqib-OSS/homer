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
    private val executor: ExecutorService = OPEN_EXECUTOR,
    private val beforeOpen: (Path) -> Unit,
) : FileOperations {
    // Test seam: [beforeOpen] runs on the pool thread before every readTextBounded open; [executor] is its pool.
    constructor() : this(OPEN_TIMEOUT_MS, OPEN_EXECUTOR, {})

    private val logger = LoggerFactory.getLogger(LocalFileOperations::class.java)

    override fun fileSize(path: Path): Long =
        // NOFOLLOW: report a link's own size, never its target's. See ADR-0047.
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()

    override fun readText(path: Path): String = path.readText()

    override fun readTextBounded(
        path: Path,
        maxBytes: Long,
    ): String? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        // CWE-367/CWE-400: open and read run on the bounded, timed pool, so a FIFO swapped in after the
        // check cannot pin the caller; a hard link renamed in after it still wins (AR-12).
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
            // nlink is not covered by O_NOFOLLOW, so re-check just before the open; a hard
            // link renamed in between still wins (AR-12, CWE-59).
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
            // NOFOLLOW_LINKS excludes an agent-planted symlink; limit stops the lazy walk early. See ADR-0047.
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
     * Opens [path] for reading: refuses a non-regular file, then runs the blocking [open] on the bounded
     * pool, at most [openTimeoutMs] per open ([MAX_OPEN_THREADS] threads; a saturated pool fails fast).
     * [open] must pass `LinkOption.NOFOLLOW_LINKS`. See ADR-0047, AR-12.
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
                executor.submit(Callable { task() })
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

        // A SynchronousQueue under the thread cap rejects a submit when no thread is free. See ADR-0047.
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
