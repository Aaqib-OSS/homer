package homer.app.infra

import homer.app.exceptions.OutputCapExceededException
import homer.app.models.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.outputStream
import kotlin.time.Duration

class LocalShellExecutor(
    private val workDir: Path,
    private val maxOutputBytes: Long = Long.MAX_VALUE,
    private val pumpJoinMs: Long = PUMP_JOIN_MS,
) : ShellExecutor {
    // ShellExecutor.cmd's override signature (fixed by the interface) and IO hop, as in SshShellExecutor.
    // jscpd:ignore-start
    @Suppress("SuspendFunSwallowedCancellation") // tears down under NonCancellable and rethrows
    override suspend fun cmd(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        stdIn: String?,
        env: Map<String, String>,
        timeout: Duration,
        stdOutFilter: OutputLineFilter?,
        interruptGrace: Duration?,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            // jscpd:ignore-end
            val process = startProcess(command, stdOut, stdErr, env, stdOutFilter)
            val outPump = stdOutFilter?.let { StdoutPump(process.inputStream, stdOut, it) }

            // Stdin is fed on its own job so a child that never reads it cannot block the
            // timeout and cap monitor. See ADR-0041.
            val stdInJob = launchStdinWriter(process, stdIn)

            // Any throw tears the process tree down and propagates: a killed command never succeeds.
            var stopped = Wait.EXITED
            try {
                stopped = awaitCompletion(process, stdOut, stdErr, timeout, outPump)
                when (stopped) {
                    Wait.EXITED -> {}

                    Wait.OUTPUT_CAP -> {
                        throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
                    }

                    Wait.TIMED_OUT -> {
                        throw CancellationException("Timeout reached. $timeout.")
                    }
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { tearDown(process, stdInJob, outPump, stdErr, timeout, stopped, interruptGrace) }
                throw e
            }

            // The process finished on its own: let the stdin writer settle before reporting.
            stdInJob?.join()

            CommandResult(
                exitCode = process.exitValue(),
                stdOut = stdOut,
                stdErr = stdErr,
            )
        }

    /**
     * How the command stopped: [awaitExit], then a re-check of the output cap once it exited on its
     * own, so a command that overshot the cap between polls fails instead of succeeding.
     */
    private suspend fun awaitCompletion(
        process: java.lang.Process,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
        outPump: StdoutPump?,
    ): Wait {
        val wait = awaitExit(process, stdOut, stdErr, timeout)
        if (wait != Wait.EXITED) return wait
        // The pump join is bounded; past it the pump closes the file, so the size check is final. See ADR-0063.
        outPump?.join(pumpJoinMs)
        return if (outputExceedsCap(stdOut, stdErr)) Wait.OUTPUT_CAP else Wait.EXITED
    }

    /**
     * Tears [process] down after a failed or cancelled wait, releases the stdin writer and the pump,
     * and appends the kill marker for [stopped] to [stdErr]. A command stopped by its own timeout
     * with an [interruptGrace] is SIGINTed first and SIGKILLed only if still alive after it.
     */
    private suspend fun tearDown(
        process: java.lang.Process,
        stdInJob: Job?,
        outPump: StdoutPump?,
        stdErr: Path,
        timeout: Duration,
        stopped: Wait,
        interruptGrace: Duration?,
    ) {
        // Only a command stopped by its own timeout gets the grace; any other stop is an immediate
        // SIGKILL. See ADR-0108.
        val grace = interruptGrace?.takeIf { stopped == Wait.TIMED_OUT && process.isAlive }
        if (grace != null) {
            ProcessGroupSupport.ownProcessGroupId(process)?.let { ProcessGroupSupport.interruptGroup(it) }
            process.waitFor(grace.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
        if (process.isAlive) {
            val descendants = process.descendants()
            // The pgid must be read here, at kill time, never at launch. See ADR-0041.
            ProcessGroupSupport.ownProcessGroupId(process)?.let { ProcessGroupSupport.killGroup(it) }
            process.destroyForcibly()
            descendants.forEach { it.destroyForcibly() }
            process.waitFor(KILL_GRACE_SEC, TimeUnit.SECONDS)
        }
        stdInJob?.cancelAndJoin()
        outPump?.join(pumpJoinMs)
        appendKillNotices(
            stdErr,
            KillCause().apply {
                byTimeout = stopped == Wait.TIMED_OUT
                byCap = stopped == Wait.OUTPUT_CAP
            },
            timeout,
            maxOutputBytes,
            graceNote = if (grace != null) " after a $grace SIGINT grace" else "",
        )
    }

    /** Starts [command] in [workDir] with exactly [env]; stdout goes to [stdOut] unless a [stdOutFilter] pumps it. */
    private fun startProcess(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        env: Map<String, String>,
        stdOutFilter: OutputLineFilter?,
    ): java.lang.Process {
        val processBuilder =
            ProcessBuilder()
                // Launched under setsid so the whole group can be reaped on kill. See ADR-0041.
                .command(ProcessGroupSupport.launchArgv(command))
                .directory(workDir.toFile())
                .redirectError(ProcessBuilder.Redirect.to(stdErr.toFile()))
        // With a filter stdout stays a pipe for a pump thread; otherwise it is an OS redirect. See ADR-0063.
        if (stdOutFilter == null) {
            processBuilder.redirectOutput(ProcessBuilder.Redirect.to(stdOut.toFile()))
        }

        // environment() starts as the host's env; clear it so the child sees only the composed env (ADR-0065).
        val childEnv = processBuilder.environment()
        childEnv.clear()
        childEnv.putAll(env)
        return processBuilder.start()
    }

    /** Writes [stdIn] to [process] on its own job; null when there is no stdin to feed. */
    private fun CoroutineScope.launchStdinWriter(
        process: java.lang.Process,
        stdIn: String?,
    ): Job? =
        if (stdIn != null) {
            launch {
                runCatching {
                    process.outputStream.bufferedWriter().use {
                        it.write(stdIn)
                        it.flush()
                    }
                }
            }
        } else {
            null
        }

    /** How [awaitExit] stopped waiting. */
    private enum class Wait { EXITED, OUTPUT_CAP, TIMED_OUT }

    /**
     * Polls [process] until it exits, its output passes the cap, or [timeout] elapses; throws
     * when the calling coroutine is cancelled meanwhile.
     */
    private suspend fun awaitExit(
        process: java.lang.Process,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
    ): Wait {
        val start = (System.currentTimeMillis() / 1000).toInt()
        while ((System.currentTimeMillis() / 1000).toInt() - start < timeout.inWholeSeconds) {
            val finished = process.waitFor(OUTPUT_CAP_POLL_MS, TimeUnit.MILLISECONDS)
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Command cancelled while waiting for completion")
            }
            if (finished) return Wait.EXITED
            if (outputExceedsCap(stdOut, stdErr)) return Wait.OUTPUT_CAP
        }
        return Wait.TIMED_OUT
    }

    private fun outputExceedsCap(
        stdOut: Path,
        stdErr: Path,
    ): Boolean = stdOut.toFile().length() > maxOutputBytes || stdErr.toFile().length() > maxOutputBytes

    /**
     * Drains the child's stdout pipe through [filter] into [target] on a daemon thread. [join] waits
     * at most its bound, then closes the file so nothing later reaches the artifact. See ADR-0063.
     */
    private class StdoutPump(
        private val source: InputStream,
        private val target: Path,
        filter: OutputLineFilter,
    ) {
        @Volatile
        private var file: OutputStream? = null

        private val thread =
            Thread {
                try {
                    val out = target.outputStream()
                    file = out
                    LineFilteringOutputStream(out, filter).use { source.copyTo(it) }
                } catch (e: Exception) {
                    logger.warn("Failed to pump process stdout to {}", target, e)
                    // Close our end so the child gets EPIPE instead of blocking on a full pipe.
                    runCatching { source.close() }
                }
            }.apply {
                isDaemon = true
                name = "homer-stdout-pump"
                start()
            }

        fun join(ms: Long) {
            thread.join(ms)
            if (!thread.isAlive) return
            logger.warn("stdout pump still running {} ms after the command ended; a child may hold stdout open", ms)
            // Close the file first: a blocked pipe read is not woken by close(). See ADR-0063.
            runCatching { file?.close() }
            runCatching { source.close() }
        }
    }

    companion object {
        private const val KILL_GRACE_SEC = 5L
        private const val OUTPUT_CAP_POLL_MS = 250L
        private const val PUMP_JOIN_MS = 5_000L
        private val logger = LoggerFactory.getLogger(LocalShellExecutor::class.java)
    }
}
