package homer.runner

import homer.app.infra.coRunCatching
import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import homer.app.runners.external.OpResult
import homer.app.runners.external.RunnerOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
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

    suspend fun execute(op: RunnerOp): OpResult =
        coRunCatching {
            withContext(Dispatchers.IO) {
                when (op) {
                    is RunnerOp.CreateWorkspace -> {
                        // createDirectory, not createDirectories: an existing root is refused, never reused.
                        // Owner-only, so another local user cannot read a run's files through a shared
                        // --root; no silent fallback where the mode is unsupported, the op fails instead.
                        // CWE-732.
                        sessionRoot.parent.createDirectories()
                        Files.createDirectory(sessionRoot, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
                        createdRoot = true
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
     * [remoteRelative] under [sessionRoot], lexically and by realpath, so a symlink a run planted cannot steer a
     * transfer outside the root (CWE-59); a link swapped in after this check is AR-04 and AR-05.
     */
    private fun contained(remoteRelative: String): Path {
        val candidate = sessionRoot.resolve(remoteRelative).normalize()
        require(candidate.startsWith(sessionRoot) && realPath(candidate).startsWith(sessionRoot.toRealPath())) {
            "path '$remoteRelative' escapes the session root"
        }
        return candidate
    }

    /** [path]'s realpath, taken at its longest existing ancestor for a part that does not exist yet. */
    private fun realPath(path: Path): Path {
        var existing = path
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return path
        return existing.toRealPath().resolve(existing.relativize(path))
    }

    fun deleteTree(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
    }
}

private val logger = LoggerFactory.getLogger(SessionWorker::class.java)
