package homer.app.runners.kubernetes

import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Workspace transfer into a pod as one gzip'd tar stream per direction over exec (ADR-0043); the image needs `tar` and
 * `gzip`. As in the ssh WorkspaceSync, upload excludes match names at any depth unless `/`-anchored, a missing remote
 * download is a no-op, and downloads honour a [DownloadMode] (ADR-0055).
 */
class KubernetesWorkspaceSync(
    private val execClient: KubernetesExecClient,
    private val namespace: String,
    private val podName: String,
) {
    fun mkdirs(remoteDir: String) = runToExit(listOf("mkdir", "-p", remoteDir))

    /**
     * Uploads [local] (file or directory contents) to [remote] in the pod, filtered by [excludes]. Each root-relative path
     * in [omitDirEntries] uploads its contents but not its own directory entry, for a pre-created dir (ADR-0054).
     */
    fun upload(
        local: Path,
        remote: String,
        excludes: List<String> = emptyList(),
        omitDirEntries: Set<String> = emptySet(),
    ) {
        val (destDir, entryName) =
            when {
                local.isDirectory() -> remote to null
                local.isRegularFile() -> (remote.substringBeforeLast('/', "").ifEmpty { "." }) to remote.substringAfterLast('/')
                else -> error("upload source does not exist: $local")
            }
        mkdirs(destDir)
        val proc = execClient.start(namespace, podName, listOf("tar", "-xzf", "-", "-C", destDir), stdin = true)
        val stderr = drain(proc.errorStream)
        drain(proc.inputStream) // tar -x emits nothing, but never block on it: stdin is still open
        try {
            TarStreams.pack(proc.outputStream, local, excludes, omitDirEntries, entryName)
        } catch (e: Exception) {
            val exited = proc.waitFor(5, TimeUnit.SECONDS)
            stderr.thread.join(2_000)
            val diag = stderr.output.toString(Charsets.UTF_8).trim()
            val exitPart = if (exited) describeExit(proc.exitValue()) else "still running"
            proc.destroyForcibly()
            throw IllegalStateException(
                "upload to '$destDir' failed while streaming ($exitPart): ${e.message}" +
                    if (diag.isNotEmpty()) "; stderr: $diag" else "",
                e,
            )
        }
        awaitZeroExit(proc, stderr, "upload to '$destDir'")
    }

    fun download(
        remote: String,
        local: Path,
        mode: DownloadMode,
    ) {
        if (!remoteExists(remote)) return
        val isDir = probe(listOf("test", "-d", remote))
        val (dir, name) =
            if (isDir) remote to "." else remote.substringBeforeLast('/').ifEmpty { "/" } to remote.substringAfterLast('/')
        // -h: dereference symlinks so their content (not a link entry) is what gets archived,
        // matching the ssh runner's SFTP behavior (WorkspaceSync also transfers file content).
        val proc = execClient.start(namespace, podName, listOf("tar", "-czhf", "-", "-C", dir, name), stdin = false)
        val stderr = drain(proc.errorStream)
        val materialized =
            try {
                // The pod's tar is attacker-replaceable and this path has no ceiling of its own, so it spends the
                // shared default (ADR-0047 rule 4, ADR-0066).
                TarStreams.extract(
                    proc.inputStream,
                    local,
                    mode,
                    isDir,
                    name,
                    "pod $podName",
                    TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
                )
            } catch (e: Exception) {
                proc.destroyForcibly()
                throw e
            }
        awaitZeroExit(proc, stderr, "download of '$remote'")
        if (materialized != null) TarStreams.prune(local, materialized)
    }

    private fun remoteExists(path: String): Boolean = probe(listOf("test", "-e", path))

    /** Runs argv in the pod and throws with captured stderr on non-zero exit. */
    private fun runToExit(argv: List<String>) {
        val proc = execClient.start(namespace, podName, argv, stdin = false)
        val stderr = drain(proc.errorStream)
        proc.inputStream.use { it.readAllBytes() }
        awaitZeroExit(proc, stderr, argv.joinToString(" "))
    }

    /**
     * Runs a POSIX `test`-style probe: exit 0 is true, exit 1 false, and anything else (another exit, a lost channel, a
     * timeout) throws with stderr, as the ssh WorkspaceSync rethrows all but a recognised "does not exist".
     */
    private fun probe(argv: List<String>): Boolean {
        val proc = execClient.start(namespace, podName, argv, stdin = false)
        val stderr = drain(proc.errorStream)
        proc.inputStream.use { it.readAllBytes() }
        if (!proc.waitFor(EXIT_STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException(
                "${argv.joinToString(" ")}: exec did not report exit status within ${EXIT_STATUS_TIMEOUT_SECONDS}s",
            )
        }
        stderr.thread.join(2_000)
        return when (val exit = proc.exitValue()) {
            0 -> {
                true
            }

            1 -> {
                false
            }

            else -> {
                throw IllegalStateException(
                    "${argv.joinToString(" ")} failed (${describeExit(exit)}): ${stderr.output.toString(Charsets.UTF_8).trim()}",
                )
            }
        }
    }

    private fun awaitZeroExit(
        proc: Process,
        stderr: Drained,
        what: String,
    ) {
        if (!proc.waitFor(EXIT_STATUS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("$what: exec did not report exit status within ${EXIT_STATUS_TIMEOUT_SECONDS}s")
        }
        stderr.thread.join(2_000)
        val exit = proc.exitValue()
        if (exit != 0) {
            throw IllegalStateException("$what failed (${describeExit(exit)}): ${stderr.output.toString(Charsets.UTF_8).trim()}")
        }
    }

    /** Renders an exit code for error messages, naming client-java's "no exit status" values. */
    private fun describeExit(exit: Int): String =
        if (isNoExitStatus(exit)) "no exit status (exec channel failed or ended without one)" else "exit $exit"

    /** Concurrently drains stderr so a full pipe can't stall the transfer. */
    private fun drain(source: InputStream): Drained {
        val sink = ByteArrayOutputStream()
        val thread =
            Thread {
                runCatching { source.use { it.copyTo(sink) } }
            }.apply {
                isDaemon = true
                start()
            }
        return Drained(thread, sink)
    }

    /** A stderr/stdout drain thread paired with the buffer it fills; join before reading it. */
    private class Drained(
        val thread: Thread,
        val output: ByteArrayOutputStream,
    )

    companion object {
        /** Bounded wait for the pod exec channel to report an exit status (websocket can wedge). */
        private const val EXIT_STATUS_TIMEOUT_SECONDS = 60L
    }
}
