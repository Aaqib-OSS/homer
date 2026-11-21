package homer.runner

import homer.app.infra.DanglingLinkException
import homer.app.infra.PathSafety
import homer.app.infra.coRunCatching
import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import homer.app.runners.external.OpResult
import homer.app.runners.external.RunnerOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.outputStream

/**
 * Owns one session's workspace root: creating it (refusing a pre-existing one), the file
 * transfers into and out of it, and its deletion.
 */
internal class SessionWorkspace(
    private val api: RunnerApi,
    private val sessionId: String,
    private val sessionRoot: Path,
) {
    /** Whether this session created [sessionRoot]; a refused pre-existing root is not ours to delete. */
    @Volatile
    var createdRoot: Boolean = false
        private set

    /** [sessionRoot]'s realpath as created; transfers are contained against it, never against a later lookup. */
    @Volatile
    private var realRoot: Path? = null

    suspend fun execute(op: RunnerOp): OpResult =
        coRunCatching {
            withContext(Dispatchers.IO) {
                when (op) {
                    is RunnerOp.CreateWorkspace -> {
                        // An existing root is refused, never reused. Owner-only, with no fallback where the
                        // mode is unsupported: another local user must not read a run's files. CWE-732.
                        sessionRoot.parent.createDirectories()
                        Files.createDirectory(sessionRoot, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                        createdRoot = true
                        realRoot = sessionRoot.toRealPath()
                        listOf("workdir", "artifacts", ".homer").forEach { sessionRoot.resolve(it).createDirectory() }
                        OpResult(op.opId, root = sessionRoot.toString())
                    }

                    is RunnerOp.Upload -> {
                        upload(op)
                    }

                    is RunnerOp.Download -> {
                        download(op)
                    }

                    is RunnerOp.CleanWorkspace -> {
                        deleteTree(sessionRoot)
                        OpResult(op.opId)
                    }

                    is RunnerOp.Cmd, is RunnerOp.Cancel, is RunnerOp.Close, is RunnerOp.Interrupt -> {
                        error("${op::class.simpleName} is handled by the dispatcher")
                    }
                }
            }
        }.getOrElse { e ->
            logger.warn("Session {}: op {} failed: {}", sessionId, op.opId, e.message)
            OpResult(op.opId, ok = false, error = e.message ?: e::class.simpleName)
        }

    private suspend fun upload(op: RunnerOp.Upload): OpResult {
        val target = contained(op.remoteRelative)
        if (op.isDir) target.createDirectories() else target.parent.createDirectories()
        api.fetchTar(sessionId, op.opId) { input ->
            TarStreams.extract(
                input,
                target,
                DownloadMode.OVERWRITE,
                remoteIsDir = op.isDir,
                fileName = target.fileName.toString(),
                sourceLabel = "homer",
                // No per-instance config for this; the shared default is the same
                // ceiling Homer's own extraction spends.
                maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
            )
        }
        return OpResult(op.opId)
    }

    private suspend fun download(op: RunnerOp.Download): OpResult {
        val source = contained(op.remoteRelative)
        return if (!source.exists()) {
            OpResult(op.opId, missing = true)
        } else {
            val tmp = Files.createTempFile(sessionRoot.parent, ".homer-runner-", ".tgz")
            try {
                tmp.outputStream().use { TarStreams.pack(it, source) }
                api.postTar(sessionId, op.opId, if (source.isDirectory()) "dir" else "file", tmp)
            } finally {
                tmp.deleteIfExists()
            }
            OpResult(op.opId)
        }
    }

    /**
     * [remoteRelative] under [sessionRoot] lexically and under the root's realpath as created, so a symlink a run
     * planted, the root itself included, cannot steer a transfer outside it (CWE-59); a dangling link on the path is
     * refused. A later swap is AR-04/AR-05.
     */
    private fun contained(remoteRelative: String): Path {
        val candidate = sessionRoot.resolve(remoteRelative).normalize()
        val root = realRoot ?: throw IllegalArgumentException("path '$remoteRelative' refused: the session root has no resolved path")
        try {
            PathSafety.assertContainedUnder(sessionRoot, root, candidate, failOnDanglingLink = true)
        } catch (e: SecurityException) {
            throw IllegalArgumentException("path '$remoteRelative' refused: ${refusalKind(e)}", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("path '$remoteRelative' could not be checked: ${e::class.simpleName}", e)
        }
        return candidate
    }

    /** A fixed phrase for [e]; its own message carries host paths, which must not reach the server. */
    private fun refusalKind(e: SecurityException): String =
        if (e is DanglingLinkException) "passes through a dangling symlink" else "escapes the session root"

    fun deleteTree(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
    }
}

private val logger = LoggerFactory.getLogger(SessionWorker::class.java)
