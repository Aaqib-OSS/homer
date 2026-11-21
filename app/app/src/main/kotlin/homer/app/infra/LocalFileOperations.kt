package homer.app.infra

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
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
    private val openRegular: (Path) -> InputStream = DescriptorOpen::openRegular,
    private val beforeOpen: (Path) -> Unit,
) : FileOperations {
    // Test seams: [beforeOpen] runs on the pool thread before every [openRegular]; [executor] is its pool.
    constructor() : this(OPEN_TIMEOUT_MS, OPEN_EXECUTOR, DescriptorOpen::openRegular, {})

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
        var failure: IOException? = null
        val text =
            try {
                submitBounded(path, "read") { openChecked(path).use { readWhole(it, maxBytes) } }
            } catch (e: IOException) {
                failure = e
                null
            }
        if (text == null) logger.debug("refusing a linked, non-regular, oversized or unreadable file in {}", path.parent, failure)
        return text
    }

    private fun openChecked(path: Path): InputStream {
        beforeOpen(path)
        return openRegular(path)
    }

    override fun fileExists(path: Path): Boolean = path.exists()

    override suspend fun streamFile(
        path: Path,
        outputStream: OutputStream,
        bufferSize: Int,
    ) {
        withContext(Dispatchers.IO) {
            submitBounded(path, "open", InputStream::close) { openChecked(path) }.use { input ->
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
     * Runs [task] on the bounded pool and waits at most [openTimeoutMs]; a full pool, a timeout and a task
     * failure all surface as an [IOException]. A result the task produces after the wait gave up is [discard]ed.
     */
    @Suppress("SwallowedException") // unwraps the ExecutionException to the task's own failure
    private fun <T> submitBounded(
        path: Path,
        what: String,
        discard: (T) -> Unit = {},
        task: () -> T,
    ): T {
        // Whichever side flips it first owns the result: the waiting caller, or the task once the caller gave up.
        val claimed = AtomicBoolean(false)
        val future =
            try {
                executor.submit(Callable { task().also { if (!claimed.compareAndSet(false, true)) discard(it) } })
            } catch (_: RejectedExecutionException) {
                throw IOException("Artifact reader pool saturated; refusing to $what $path")
            }
        return try {
            future.get(openTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            abandon(future, claimed, discard)
            throw IOException("Timed out trying to $what $path")
        } catch (_: InterruptedException) {
            abandon(future, claimed, discard)
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while trying to $what $path")
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException ?: IOException("Failed to $what $path", e.cause))
        }
    }

    /** Gives up on [future]: cancels it if it has not produced yet, else [discard]s the result nobody will take. */
    private fun <T> abandon(
        future: Future<T>,
        claimed: AtomicBoolean,
        discard: (T) -> Unit,
    ) {
        if (claimed.compareAndSet(false, true)) future.cancel(true) else discard(future.get())
    }

    internal companion object {
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val MAX_OPEN_THREADS = 8

        /**
         * A pool configured like the production one: a SynchronousQueue under [MAX_OPEN_THREADS] rejects a submit
         * when no thread is free or spawnable, and idle threads are reaped after 30 s.
         */
        fun newOpenPool(): ExecutorService =
            ThreadPoolExecutor(
                0,
                MAX_OPEN_THREADS,
                30L,
                TimeUnit.SECONDS,
                SynchronousQueue(),
            ) { r -> Thread(r, "artifact-open").apply { isDaemon = true } }

        private val OPEN_EXECUTOR: ExecutorService = newOpenPool()
    }
}
