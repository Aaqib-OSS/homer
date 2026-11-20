package homer.app.runners.external

import homer.app.exceptions.OutputCapExceededException
import homer.app.infra.KillCause
import homer.app.infra.LineFilteringOutputStream
import homer.app.infra.OutputLineFilter
import homer.app.infra.ShellExecutor
import homer.app.infra.appendKillNotices
import homer.app.models.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.outputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs each command as one `cmd` op on the claimed instance; ordered chunks reach the usual stdout/stderr files through
 * [OutputSink], stdout through the Claude line filter when set. The instance enforces the timeout, and Homer waits for
 * its result at most `timeoutMs + heartbeatTimeoutSec`, plus the interrupt grace when set (ADR-0066, ADR-0108).
 */
class ExternalShellExecutor(
    private val session: ExternalRunnerSession,
    private val maxOutputBytes: Long,
    private val heartbeatTimeout: Duration,
) : ShellExecutor {
    private sealed interface Outcome {
        data class Done(
            val result: OpResult,
        ) : Outcome

        data object CapExceeded : Outcome

        data object Deadline : Outcome
    }

    // ShellExecutor.cmd's override signature (fixed by the interface), as in SshShellExecutor.
    // jscpd:ignore-start
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("SuspendFunSwallowedCancellation") // cancels the remote cmd under NonCancellable and rethrows
    override suspend fun cmd(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        stdIn: String?,
        env: Map<String, String>,
        timeout: Duration,
        stdOutFilter: OutputLineFilter?,
        interruptGrace: Duration?,
    ): CommandResult {
        // jscpd:ignore-end
        // No Dispatchers.IO hop, for runTest determinism: past two fast file opens only suspensions follow (routes write chunks).
        val sink = openSink(stdOut, stdErr, stdOutFilter)
        val op = cmdOp(command, stdIn, env, timeout, interruptGrace)
        session.registerSink(op.opId, sink)
        val kill = KillCause()
        // With a grace, the timeout is a soft deadline the instance stops the command at gracefully, so the wait
        // for its result grows by the grace.
        val deadline = timeout + (interruptGrace ?: Duration.ZERO) + heartbeatTimeout
        return try {
            val deferred = session.mailbox.submit(op)
            val outcome = awaitOutcome(deferred, sink, op.opId, timeout, interruptGrace, deadline)
            when (outcome) {
                Outcome.CapExceeded -> {
                    kill.byCap = true
                    throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
                }

                Outcome.Deadline -> {
                    throw ExternalRunnerException(
                        "Runner instance '${session.instance.name}' did not report the command result within ${deadline.inWholeSeconds} s",
                    )
                }

                is Outcome.Done -> {
                    doneResult(outcome.result, sink, kill, stdOut, stdErr, timeout)
                }
            }
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                // The instance owns the process; ask it to kill the group. It reports the
                // cmd result afterwards, which nobody awaits any more.
                session.cancelCmd(op.opId)
                session.unregisterSink(op.opId)
                sink.close()
                appendKillNotices(stdErr, kill, timeout, maxOutputBytes)
            }
            throw e
        }.also {
            session.unregisterSink(op.opId)
            sink.close()
        }
    }

    /** The `cmd` op carrying this command to the instance, with the grace the instance stops it after. */
    private fun cmdOp(
        command: List<String>,
        stdIn: String?,
        env: Map<String, String>,
        timeout: Duration,
        interruptGrace: Duration?,
    ): RunnerOp.Cmd =
        RunnerOp.Cmd(
            opId = RunnerProtocol.newId(),
            argv = command,
            env = env,
            stdin = stdIn,
            timeoutMs = timeout.inWholeMilliseconds,
            cwd = session.remoteWorkdir,
            interruptGraceSeconds = interruptGrace?.inWholeSeconds,
        )

    /**
     * Waits for the instance's result, the sink's cap trip or [deadline], whichever settles first. The interrupt sent at
     * [timeout] is cancelled once the result settles; a lost one ends in the instance's own kill (ADR-0108).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitOutcome(
        deferred: Deferred<OpResult>,
        sink: OutputSink,
        opId: String,
        timeout: Duration,
        interruptGrace: Duration?,
        deadline: Duration,
    ): Outcome =
        coroutineScope {
            val softStop =
                interruptGrace?.let {
                    launch {
                        delay(timeout)
                        session.interruptCmd(opId)
                    }
                }
            try {
                select<Outcome> {
                    deferred.onAwait { Outcome.Done(it) }
                    sink.capTripped.onAwait { Outcome.CapExceeded }
                    onTimeout(deadline.inWholeMilliseconds) { Outcome.Deadline }
                }
            } finally {
                softStop?.cancel()
            }
        }

    /** The sink for the command's output; closes stdout again when opening stderr fails. */
    private fun openSink(
        stdOut: Path,
        stdErr: Path,
        stdOutFilter: OutputLineFilter?,
    ): OutputSink {
        val rawOut: OutputStream = stdOut.outputStream()
        val out: OutputStream = if (stdOutFilter != null) LineFilteringOutputStream(rawOut, stdOutFilter) else rawOut
        // stdErr.outputStream() can throw once rawOut is already open; nothing would ever
        // close it then, because the sink that owns both was never constructed.
        return try {
            OutputSink(out, stdErr.outputStream(), maxOutputBytes)
        } catch (e: Throwable) {
            runCatching { rawOut.close() }
            throw e
        }
    }

    /**
     * The command's result from the instance's [result]; throws, setting [kill]'s matching flag, on a non-clean exit,
     * missing chunk counts or output Homer never received. Suspends at most one [INCOMPLETE_OUTPUT_WAIT] for a last chunk.
     */
    private suspend fun doneResult(
        result: OpResult,
        sink: OutputSink,
        kill: KillCause,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
    ): CommandResult {
        // select may pick Done over a cap trip it raced, and a sink that dropped bytes must not yield a clean result.
        capError(sink, kill)
        if (result.timedOut) {
            kill.byTimeout = true
            throw CancellationException("Timeout reached. $timeout.")
        }
        val exitCode =
            result.exitCode?.takeIf { result.ok }
                ?: throw ExternalRunnerException(
                    "Command failed on runner instance '${session.instance.name}': " +
                        (result.error?.let { session.sanitizedError(it) } ?: "no exit code reported"),
                )
        requireCompleteOutput(result, sink, kill)
        // A chunk that landed during that wait can have crossed the cap: its seq closes the gap while its bytes are
        // dropped, so the cap is checked again.
        capError(sink, kill)
        return CommandResult(exitCode = exitCode, stdOut = stdOut, stdErr = stdErr)
    }

    /** Fails the step over bytes the sink dropped once its cap has tripped; a no-op before that. */
    private fun capError(
        sink: OutputSink,
        kill: KillCause,
    ) {
        if (!sink.capTripped.isCompleted) return
        kill.byCap = true
        throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
    }

    /**
     * Fails the step when the instance reports no chunk counts, or more chunks for a stream than Homer holds after one
     * bounded wait: the last chunk rides another connection than the result (ADR-0066). A cap that trips meanwhile is the
     * cap error ([capError]).
     */
    private suspend fun requireCompleteOutput(
        result: OpResult,
        sink: OutputSink,
        kill: KillCause,
    ) {
        val reported = reportedCounts(result)
        requirePossibleCounts(reported)
        if (missingChunks(reported, sink).isEmpty()) {
            // A cap trip can land between the caller's check and here with no wait in
            // between; a return over it would pass truncated output off as clean.
            capError(sink, kill)
            return
        }
        delay(INCOMPLETE_OUTPUT_WAIT)
        capError(sink, kill)
        val missing = missingChunks(reported, sink)
        if (missing.isEmpty()) return
        throw ExternalRunnerException(
            missing.joinToString("; ") {
                "output incomplete on $it: the instance reported ${reported.getValue(it)} chunk(s), Homer received ${sink.received(it)}"
            },
        )
    }

    /** The counts the instance reported, per stream; a `cmd` result without both is refused. */
    private fun reportedCounts(result: OpResult): Map<String, Long> {
        val stdout = result.stdoutChunks
        val stderr = result.stderrChunks
        if (stdout == null || stderr == null) {
            throw ExternalRunnerException(
                "runner instance '${session.instance.name}' does not report output chunk counts " +
                    "(homer-runner before 1.37.1 omits them); upgrade it",
            )
        }
        return mapOf(RunnerProtocol.STREAM_STDOUT to stdout, RunnerProtocol.STREAM_STDERR to stderr)
    }

    /**
     * Refuses a negative count, the only impossible one, before any wait is spent on it: a zero-byte chunk still advances
     * the sequence, so a large positive count is left to the completeness check.
     */
    private fun requirePossibleCounts(counts: Map<String, Long>) {
        counts.forEach { (stream, count) ->
            if (count < 0) {
                throw ExternalRunnerException("output incomplete on $stream: the instance reported $count chunk(s)")
            }
        }
    }

    /** The streams the instance claims more chunks for than the sink has received. */
    private fun missingChunks(
        counts: Map<String, Long>,
        sink: OutputSink,
    ): List<String> = counts.filter { (stream, count) -> count > sink.received(stream) }.keys.toList()

    companion object {
        /**
         * How long one `cmd` waits for chunks its instance reported but Homer has not received,
         * once, before failing the step as output-incomplete. Seconds, and fixed: the wait is
         * the instance's last post still being in flight, not a retry budget (ADR-0047 rule 5, CWE-400).
         */
        internal val INCOMPLETE_OUTPUT_WAIT = 2.seconds
    }
}
