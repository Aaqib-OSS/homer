package homer.runner

import homer.app.infra.ProcessGroupSupport
import homer.app.runners.external.RunnerProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Runs one `cmd` op: the process leads its own group (`setsid` where present, ADR-0041) with exactly the
 * given env, its output reaches [Handle.await]'s sink as ordered chunks, and timeout and `cancel` kill the group.
 */
object ProcessRunner {
    private const val READ_BUFFER = 64 * 1024
    private const val KILL_GRACE_SEC = 5L
    private val PUMP_JOIN = 5.seconds

    /** How often the post-exit drain re-reads what its pumps are doing (ADR-0047 rule 4). */
    private val DRAIN_POLL = 25.milliseconds

    /**
     * One `cmd` op's outcome. The chunk counts include a chunk the sink gave up on, so a lost one shows
     * as a stream Homer is short of; [cutStreams] names streams whose drain was abandoned with chunks unsent.
     */
    class Exit(
        val code: Int,
        val timedOut: Boolean,
        val stdoutChunks: Long,
        val stderrChunks: Long,
        val cutStreams: Set<String>,
    ) {
        /** The chunks [stream] got as far as handing to the sink. */
        fun chunksFor(stream: String): Long = if (stream == RunnerProtocol.STREAM_STDOUT) stdoutChunks else stderrChunks
    }

    class Handle internal constructor(
        private val process: Process,
    ) {
        // Not structured children: a grandchild holding the pipe open would keep await() from returning.
        private val pumps = CoroutineScope(Dispatchers.IO + SupervisorJob())

        internal fun isAlive(): Boolean = process.isAlive

        fun kill() {
            if (!process.isAlive) return
            ProcessGroupSupport.ownProcessGroupId(process)?.let { ProcessGroupSupport.killGroup(it) }
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }

        /**
         * SIGINTs the command's own process group, waits up to [grace] (null: no wait), then [kill]s what
         * is left; with no known group the SIGINT is skipped, never aimed at the runner's group. ADR-0108.
         */
        fun interrupt(grace: Duration?) {
            if (!process.isAlive) return
            ProcessGroupSupport.ownProcessGroupId(process)?.let { ProcessGroupSupport.interruptGroup(it) }
            if (grace != null) process.waitFor(grace.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            kill()
        }

        @Suppress("SuspendFunInFinallySection") // NonCancellable + Dispatchers.IO, which detekt does not read as NonCancellable
        suspend fun await(
            timeout: Duration,
            interruptGrace: Duration? = null,
            sink: suspend (stream: String, seq: Long, bytes: ByteArray) -> Unit,
        ): Exit {
            // The pumps post the tail after the process is gone: what is left of the op's timeout bounds that.
            val startedAt = TimeSource.Monotonic.markNow()
            val out = Pump(pumps, process.inputStream, RunnerProtocol.STREAM_STDOUT, sink)
            val err = Pump(pumps, process.errorStream, RunnerProtocol.STREAM_STDERR, sink)
            var finished = false
            // Only this call's own timeout sets it; an outside cancel stays an immediate SIGKILL (ADR-0108).
            var timedOut = false
            var cut: Set<String> = emptySet()
            try {
                val exited = withTimeoutOrNull(timeout) { process.onExit().await() }
                finished = exited != null
                timedOut = exited == null
            } finally {
                // In a finally: an outside cancel must not skip the kill and leave a process running in a
                // root about to be deleted. IO: reap blocks in waitFor for up to KILL_GRACE_SEC.
                withContext(NonCancellable + Dispatchers.IO) {
                    cut = reap(finished, out, err, timeout - startedAt.elapsedNow(), interruptGrace?.takeIf { timedOut })
                }
            }
            val code = if (process.isAlive) 137 else process.exitValue()
            return Exit(code, timedOut = !finished, out.chunks.get(), err.chunks.get(), cut)
        }

        /**
         * Waits for both pumps to hand over what they hold, for the longer of [PUMP_JOIN] and [remaining]
         * (what is left of the op's timeout), and returns the streams abandoned with chunks unsent (ADR-0066).
         */
        private suspend fun reap(
            finished: Boolean,
            out: Pump,
            err: Pump,
            remaining: Duration,
            interruptGrace: Duration?,
        ): Set<String> {
            if (!finished) {
                // A stop carrying a grace interrupts before it kills; a cancel passes null (ADR-0108).
                if (interruptGrace != null) interrupt(interruptGrace) else kill()
                process.waitFor(KILL_GRACE_SEC, TimeUnit.SECONDS)
            }
            val joined =
                withTimeoutOrNull(PUMP_JOIN) {
                    out.job.join()
                    err.job.join()
                }
            if (joined == null) {
                awaitSettled(out, err, remaining - PUMP_JOIN)
            }
            // Read before the cancel below: a pump still running is one whose tail this result lacks.
            val cut =
                buildSet {
                    if (out.cut) add(RunnerProtocol.STREAM_STDOUT)
                    if (err.cut) add(RunnerProtocol.STREAM_STDERR)
                }
            // Detached pumps would keep invoking the sink for an op that has already returned.
            pumps.cancel()
            return cut
        }

        /** Waits out [budget] for a pump still handing a chunk over; one with nothing left to hand over is shed. */
        private suspend fun awaitSettled(
            out: Pump,
            err: Pump,
            budget: Duration,
        ) {
            withTimeoutOrNull(maxOf(Duration.ZERO, budget)) {
                while (!(out.settled && err.settled)) delay(DRAIN_POLL)
            }
        }

        /** One stream's pump: its job, the chunks it handed the sink, and what its current read is doing. */
        private class Pump(
            scope: CoroutineScope,
            input: InputStream,
            stream: String,
            sink: suspend (stream: String, seq: Long, bytes: ByteArray) -> Unit,
        ) {
            val chunks = AtomicLong()

            /** Whether a read is in flight: one parked past the exit is not waited on (ADR-0066). */
            private val reading = AtomicBoolean()

            val job: Job =
                scope.launch {
                    val buffer = ByteArray(READ_BUFFER)
                    while (true) {
                        val n =
                            try {
                                reading.set(true)
                                runCatching { input.read(buffer) }.getOrElse { -1 }
                            } finally {
                                reading.set(false)
                            }
                        if (n < 0) return@launch
                        if (n == 0) continue
                        val seq = chunks.get()
                        sink(stream, seq, buffer.copyOf(n))
                        // Counted once the sink returns, a given-up post included, so a drop shows as a shortfall.
                        chunks.set(seq + 1)
                    }
                }

            /** Whether the pump has nothing left to hand over: it finished, or it is parked in a read. */
            val settled: Boolean get() = job.isCompleted || reading.get()

            /** Whether the drain gave up on chunks this pump had not handed over. */
            val cut: Boolean get() = !job.isCompleted && !reading.get()
        }
    }

    fun start(
        argv: List<String>,
        cwd: Path,
        env: Map<String, String>,
        stdin: String?,
    ): Handle {
        val builder = ProcessBuilder(ProcessGroupSupport.launchArgv(argv)).directory(cwd.toFile())
        // The JDK pre-fills the child env from this process's env; the run must
        // see only what Homer composed (same rule as LocalShellExecutor).
        builder.environment().apply {
            clear()
            putAll(env)
        }
        val process = builder.start()
        Thread {
            runCatching {
                process.outputStream.bufferedWriter().use { writer ->
                    if (stdin != null) writer.write(stdin)
                }
            }
        }.apply {
            isDaemon = true
            name = "homer-runner-stdin"
            start()
        }
        return Handle(process)
    }
}
