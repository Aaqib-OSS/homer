package homer.app.infra

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Grace given to a killed process group to die before the call returns. */
private const val GRAPH_KILL_GRACE_SEC = 5L

/** How long a reader may stay blocked on a pipe after the subprocess is gone. */
private const val GRAPH_READER_JOIN_MS = 5_000L

/** Attempts at reading the child's own pgid before `setsid(2)` has run. */
private const val GRAPH_PGID_CAPTURE_ATTEMPTS = 20

/** Delay between those attempts. */
private const val GRAPH_PGID_CAPTURE_RETRY_MS = 10L

/** Result of one bounded `homer_langgraph` introspection subprocess. */
internal sealed interface GraphSubprocessOutcome {
    /** The subprocess exited on its own; [stdout] was read within the cap. */
    data class Exited(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) : GraphSubprocessOutcome

    /** The subprocess outlived the timeout and was killed with its process group. */
    data object TimedOut : GraphSubprocessOutcome

    /** Stdout passed [MAX_GRAPH_STDOUT_BYTES]; the subprocess was killed at once. */
    data object StdoutExceeds : GraphSubprocessOutcome

    data class LaunchFailed(
        val message: String,
    ) : GraphSubprocessOutcome
}

/**
 * Runs `<python> -m homer_langgraph [args]` with [graphSubprocessEnv], holding at most the two stream caps
 * in memory. Stdout past its cap kills the process group; stderr past its cap is discarded. CWE-770, ADR-0073.
 */
@Suppress("SuspendFunSwallowedCancellation") // reaps the child (NonCancellable) and rethrows
internal suspend fun runBoundedGraphSubprocess(
    python: String,
    args: List<String>,
    env: Map<String, String>,
    timeout: Duration,
    serverEnv: Map<String, String> = System.getenv(),
    startProcess: (ProcessBuilder) -> java.lang.Process = { it.start() },
): GraphSubprocessOutcome =
    withContext(Dispatchers.IO) {
        val builder = graphProcessBuilder(python, args, graphSubprocessEnv(serverEnv, env))
        val process =
            try {
                startProcess(builder)
            } catch (e: Exception) {
                return@withContext GraphSubprocessOutcome.LaunchFailed(e.message ?: "failed to launch python")
            }

        // Own scope: a reader blocked on a pipe a detached grandchild holds open must not be awaited forever.
        val readers = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Capture the group id while the leader lives, non-cancellably, or a cancelled caller leaks it (CWE-772).
        var groupId: Long? = null
        try {
            groupId = withContext(NonCancellable) { captureOwnProcessGroupId(process) }
            // CWE-770: bounded readers, at most limit+1 bytes each.
            val stdoutJob = readers.readGraphStdout(process, groupId)
            val stderrJob = readers.readGraphStderr(process)
            awaitGraphOutcome(process, groupId, stdoutJob, stderrJob, timeout)
        } catch (e: Throwable) {
            // Any unwind after start() must reap the child and its group (CWE-772).
            runCatching { reapGraphProcess(process, groupId) }
            throw e
        } finally {
            readers.cancel()
            closeGraphStreams(process)
        }
    }

/** Server env names a `homer_langgraph` subprocess keeps, matched exactly; the rest are dropped (ADR-0027). */
internal val GRAPH_SERVER_ENV_ALLOWLIST: Set<String> =
    setOf(
        "PATH",
        "HOME",
        "LANG",
        "LC_ALL",
        "LC_CTYPE",
        "LC_NUMERIC",
        "LC_TIME",
        "LC_COLLATE",
        "LC_MONETARY",
        "LC_MESSAGES",
        "LC_PAPER",
        "LC_NAME",
        "LC_ADDRESS",
        "LC_TELEPHONE",
        "LC_MEASUREMENT",
        "LC_IDENTIFICATION",
        "TMPDIR",
        "VIRTUAL_ENV",
        "PYTHONHOME",
    )

/** The allowlisted entries of [serverEnv] overlaid by the agent's [agentEnv]; `PYTHONWARNINGS` defaults to `ignore`. */
internal fun graphSubprocessEnv(
    serverEnv: Map<String, String>,
    agentEnv: Map<String, String>,
): Map<String, String> =
    buildMap {
        putAll(serverEnv.filterKeys { it in GRAPH_SERVER_ENV_ALLOWLIST })
        putAll(agentEnv)
        // Keep import-time deprecation warnings (e.g. LangChain's) out of the error
        // we surface to the console; warnings are non-fatal here.
        putIfAbsent("PYTHONWARNINGS", "ignore")
    }

/** `<python> -m homer_langgraph [args]` with exactly [childEnv]: the JDK's copy of the server env is cleared first. */
private fun graphProcessBuilder(
    python: String,
    args: List<String>,
    childEnv: Map<String, String>,
): ProcessBuilder {
    val builder =
        ProcessBuilder(ProcessGroupSupport.launchArgv(listOf(python, "-m", "homer_langgraph") + args))
    builder.environment().clear()
    builder.environment().putAll(childEnv)
    return builder
}

/** Reads [process]'s stdout up to [MAX_GRAPH_STDOUT_BYTES]; null past the cap, where the group is killed at once. */
private fun CoroutineScope.readGraphStdout(
    process: java.lang.Process,
    groupId: Long?,
): Deferred<String?> =
    async {
        val whole =
            try {
                readWhole(process.inputStream, MAX_GRAPH_STDOUT_BYTES)
            } catch (_: Exception) {
                // The pipe was closed under us (a reader that outlived the join bound):
                // we cannot prove the output fits the cap.
                null
            }
        // Past the cap: kill the group at once rather than draining to EOF.
        if (whole == null) reapGraphProcess(process, groupId)
        whole
    }

/** Reads the head of [process]'s stderr, up to [MAX_GRAPH_STDERR_BYTES], and discards the rest. */
private fun CoroutineScope.readGraphStderr(process: java.lang.Process): Deferred<String> =
    async {
        val head =
            try {
                readHead(process.errorStream, MAX_GRAPH_STDERR_BYTES)
            } catch (_: Exception) {
                ""
            }
        // Drain-and-discard the remainder so a flooding child never wedges
        // on a full stderr pipe; nothing past the head is retained.
        runCatching { process.errorStream.copyTo(OutputStream.nullOutputStream()) }
        head
    }

/** Waits up to [timeout] for [process], reaps its group either way, and reads the outcome off the readers. */
private suspend fun awaitGraphOutcome(
    process: java.lang.Process,
    groupId: Long?,
    stdoutJob: Deferred<String?>,
    stderrJob: Deferred<String>,
    timeout: Duration,
): GraphSubprocessOutcome {
    if (!process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)) {
        reapGraphProcess(process, groupId)
        joinGraphReaders(listOf(stdoutJob, stderrJob), process)
        return GraphSubprocessOutcome.TimedOut
    }

    // The leader exited on its own; reap the group all the same — a grandchild that
    // outlives it is reachable only through the pgid captured while the leader lived.
    reapGraphProcess(process, groupId)
    joinGraphReaders(listOf(stdoutJob, stderrJob), process)
    val stdout = stdoutJob.getCompletedOrNull() ?: return GraphSubprocessOutcome.StdoutExceeds
    return GraphSubprocessOutcome.Exited(process.exitValue(), stdout, stderrJob.getCompletedOrNull() ?: "")
}

/** Closes the process streams on every exit path, as readWhole/readHead leave them to the caller (CWE-772). */
private fun closeGraphStreams(process: java.lang.Process) {
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    // A child reading stdin must see EOF, not block forever.
    runCatching { process.outputStream.close() }
}

/** Reads [process]'s own process-group id, retrying until the child has run `setsid(2)`; needs the leader alive. */
private suspend fun captureOwnProcessGroupId(process: java.lang.Process): Long? {
    if (ProcessGroupSupport.setsidPath == null) return null
    repeat(GRAPH_PGID_CAPTURE_ATTEMPTS) {
        ProcessGroupSupport.ownProcessGroupId(process)?.let { return it }
        if (!process.isAlive) return null
        delay(GRAPH_PGID_CAPTURE_RETRY_MS)
    }
    return null
}

/**
 * Kills [process] and, where `setsid` made it a group leader, its whole group; [capturedGroupId] covers
 * a leader that has since exited. See ADR-0041.
 */
private suspend fun reapGraphProcess(
    process: java.lang.Process,
    capturedGroupId: Long?,
) {
    withContext(NonCancellable) {
        val groupId = ProcessGroupSupport.ownProcessGroupId(process) ?: capturedGroupId
        if (!process.isAlive && groupId == null) return@withContext
        // Snapshot descendants BEFORE the group kill: afterwards they are gone.
        val descendants = if (process.isAlive) process.descendants() else null
        groupId?.let { ProcessGroupSupport.killGroup(it) }
        process.destroyForcibly()
        descendants?.forEach { it.destroyForcibly() }
        process.waitFor(GRAPH_KILL_GRACE_SEC, TimeUnit.SECONDS)
    }
}

/** Waits at most [GRAPH_READER_JOIN_MS] for [jobs], then closes the streams: a blocking read ignores `withTimeout`. */
private suspend fun joinGraphReaders(
    jobs: List<Job>,
    process: java.lang.Process,
) {
    val joined = withTimeoutOrNull(GRAPH_READER_JOIN_MS) { jobs.forEach { it.join() } }
    if (joined != null) return
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> Deferred<T>.getCompletedOrNull(): T? = if (isCompleted) runCatching { getCompleted() }.getOrNull() else null
