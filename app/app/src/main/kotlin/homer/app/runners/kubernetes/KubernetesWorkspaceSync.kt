package homer.app.runners.kubernetes

import homer.app.infra.readHead
import homer.app.runners.DownloadMode
import homer.app.runners.TarStreams
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.time.Duration

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
            val diag = stderr.text
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

    /**
     * Writes [content] to [path] in the pod as a 0600 file in a 0700 directory, refusing a symlinked directory. `tar`
     * creates the file under `umask 077`, so it never exists with wider bits; the image needs `sh` and `tar`.
     */
    fun writePrivateFile(
        path: String,
        content: ByteArray,
    ) {
        val dir = path.substringBeforeLast('/')
        val proc = execClient.start(namespace, podName, listOf("sh", "-c", PRIVATE_EXTRACT, PRIVATE_EXTRACT_NAME, dir), stdin = true)
        val stderr = drain(proc.errorStream)
        drain(proc.inputStream)
        try {
            TarStreams.packFile(proc.outputStream, path.substringAfterLast('/'), content, MODE_0600)
        } catch (e: Exception) {
            proc.destroyForcibly()
            throw IllegalStateException("private file upload to '$dir' failed while streaming: ${e.javaClass.simpleName}", e)
        }
        awaitZeroExit(proc, stderr, "private file upload to '$dir'")
    }

    /** Removes [path] in the pod if it exists, failing when the exec reports no exit status within [timeout]. */
    fun removeIfExists(
        path: String,
        timeout: Duration,
    ) {
        val argv = listOf("rm", "-f", "--", path)
        val proc = execClient.start(namespace, podName, argv, stdin = false)
        val stderr = drain(proc.errorStream)
        drain(proc.inputStream)
        awaitZeroExit(proc, stderr, argv.joinToString(" "), timeout.inWholeSeconds)
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
        drain(proc.inputStream)
        awaitZeroExit(proc, stderr, argv.joinToString(" "))
    }

    /**
     * Runs a POSIX `test`-style probe: exit 0 is true, exit 1 false, and anything else (another exit, a lost channel, a
     * timeout) throws with stderr, as the ssh WorkspaceSync rethrows all but a recognised "does not exist".
     */
    private fun probe(argv: List<String>): Boolean {
        val proc = execClient.start(namespace, podName, argv, stdin = false)
        val stderr = drain(proc.errorStream)
        drain(proc.inputStream)
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
                    "${argv.joinToString(" ")} failed (${describeExit(exit)}): ${stderr.text}",
                )
            }
        }
    }

    private fun awaitZeroExit(
        proc: Process,
        stderr: Drained,
        what: String,
        timeoutSeconds: Long = EXIT_STATUS_TIMEOUT_SECONDS,
    ) {
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("$what: exec did not report exit status within ${timeoutSeconds}s")
        }
        stderr.thread.join(2_000)
        val exit = proc.exitValue()
        if (exit != 0) {
            throw IllegalStateException("$what failed (${describeExit(exit)}): ${stderr.text}")
        }
    }

    /** Renders an exit code for error messages, naming client-java's "no exit status" values. */
    private fun describeExit(exit: Int): String =
        if (isNoExitStatus(exit)) "no exit status (exec channel failed or ended without one)" else "exit $exit"

    /** Concurrently drains [source] to EOF so a full pipe can't stall the transfer, keeping only its head. */
    private fun drain(source: InputStream): Drained {
        val head = AtomicReference("")
        val thread =
            Thread {
                runCatching {
                    source.use {
                        // The pod writes this stream: keep a capped head, discard the rest (CWE-770, ADR-0047 rule 4).
                        head.set(readHead(it, MAX_RETAINED_OUTPUT_BYTES))
                        it.copyTo(OutputStream.nullOutputStream())
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        return Drained(thread, head)
    }

    /** A stderr/stdout drain thread paired with the head it keeps; join before reading [text]. */
    private class Drained(
        val thread: Thread,
        private val head: AtomicReference<String>,
    ) {
        /** At most [MAX_RETAINED_OUTPUT_BYTES] of the stream, trimmed and marked when cut. */
        val text: String get() = head.get().trim()
    }

    companion object {
        /** Bounded wait for the pod exec channel to report an exit status (websocket can wedge). */
        private const val EXIT_STATUS_TIMEOUT_SECONDS = 60L

        /** Most bytes of one exec stream the backend keeps for a failure message. */
        internal const val MAX_RETAINED_OUTPUT_BYTES = 16 * 1024

        /** `$0` of the private-file upload's shell. */
        internal const val PRIVATE_EXTRACT_NAME = "homer-env"

        private const val MODE_0600 = 0b110_000_000

        // `$1` is the directory: tightened to 0700 and re-checked as a real directory before `tar` writes into it.
        private const val PRIVATE_EXTRACT =
            "mkdir -p \"\$1\" && [ ! -L \"\$1\" ] && [ -d \"\$1\" ] && chmod 700 \"\$1\" && cd \"\$1\" && umask 077 && exec tar -xf -"
    }
}
