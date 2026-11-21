package homer.runner

import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.OpResult
import homer.app.runners.external.RunnerOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Drives one claimed session: a poller keeps a `next` long-poll open for `cancel` and `close`, the
 * dispatcher runs ops in order and posts each result from a background job (ADR-0066). Homer
 * unreachable for [heartbeatTimeout] means it dropped this instance: kill what runs and stop.
 */
class SessionWorker(
    private val api: RunnerApi,
    private val claim: ClaimResponse,
    root: Path,
    private val heartbeatTimeout: Duration,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // Random per session, never the process id alone: ids repeat after a database reset, and a reused
    // path under a surviving --root volume would hand one run another's files. CWE-200.
    val sessionRoot: Path = root.toAbsolutePath().normalize().resolve("${claim.processId}-${UUID.randomUUID()}")

    @Volatile
    var lost: Boolean = false
        private set

    // The session root's lifecycle — create or refuse, transfers, delete — lives in SessionWorkspace.
    private val workspace = SessionWorkspace(api, claim.sessionId, sessionRoot)

    // Bounded: Homer hands out one op at a time, so a flood of ops applies backpressure instead of piling up.
    private val ops = Channel<RunnerOp>(OPS_CAPACITY)
    private val running = ConcurrentHashMap<String, ProcessRunner.Handle>()

    // The grace each running cmd started with, for an Interrupt, which carries only the cmd's id (ADR-0108).
    private val interruptGraces = ConcurrentHashMap<String, Duration>()

    // opIds cancelled before `runCmd` registered the process: `dispatchLoop` does not wait for a `cmd` to start.
    private val cancelledOps = ConcurrentHashMap.newKeySet<String>()

    // Delivery jobs run here, not in dispatchLoop's scope, so a parked result-carrying `next` never holds
    // dispatchLoop back; lost() cancels them, a graceful finish waits for them (ADR-0066).
    private lateinit var workerScope: CoroutineScope
    private val deliveryJobs = ConcurrentHashMap.newKeySet<Job>()

    @Suppress("SuspendFunInFinallySection") // NonCancellable + Dispatchers.IO, which detekt does not read as NonCancellable
    suspend fun run() =
        coroutineScope {
            workerScope = this
            val poller = launch { pollLoop() }
            try {
                dispatchLoop()
            } finally {
                poller.cancel()
                killAll()
                // Every exit reclaims the root, a lost session included (ADR-0047 rule 4); a refused
                // pre-existing root is not ours to delete.
                if (workspace.createdRoot) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { workspace.deleteTree(sessionRoot) }
                            .onFailure {
                                logger.warn(
                                    "Session {} (process {}): failed to delete the session root: {}",
                                    claim.sessionId,
                                    claim.processId,
                                    it.message,
                                )
                            }
                    }
                }
            }
        }

    private suspend fun pollLoop() {
        var lastOkAt = clock()
        while (true) {
            val startedAt = clock()
            try {
                val op = api.next(claim.sessionId, null)
                lastOkAt = clock()
                onPolled(op, startedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ClosedSendChannelException) {
                // `ops` is closed, so the session is ending: the retry path below would keep refreshing
                // lastOkAt while re-failing this send, and the heartbeat deadline would never trip.
                return
            } catch (e: Exception) {
                when (val verdict = pollFailureVerdict(e, claim.sessionId, clock() - lastOkAt, heartbeatTimeout)) {
                    is PollVerdict.Lost -> {
                        return lost(verdict.reason)
                    }

                    is PollVerdict.Retry -> {
                        verdict.warning?.let { logger.warn("Session {}: next failed: {}", claim.sessionId, it) }
                        delay(RETRY_DELAY)
                    }
                }
            }
        }
    }

    /** Queues the [op] a poll started at [startedAt] returned, or paces an empty poll that came back early. */
    private suspend fun onPolled(
        op: RunnerOp?,
        startedAt: Long,
    ) {
        when {
            op != null -> ops.send(op)

            // A 204 well under the long-poll window means Homer is not holding the poll open: pace it, no busy-loop.
            clock() - startedAt < FAST_POLL_THRESHOLD.inWholeMilliseconds -> delay(FAST_POLL_DELAY)
        }
    }

    private fun lost(reason: String) {
        logger.error(
            "Session {} (process {}): {}; killing local processes and dropping the session",
            claim.sessionId,
            claim.processId,
            reason,
        )
        lost = true
        // Killed here, not in run()'s finally: closing `ops` alone strands a `cmd` job awaiting a live process.
        killAll()
        // Likewise cancelled directly: closing `ops` leaves a job parked in `postResult` against a gone session.
        deliveryJobs.forEach { it.cancel() }
        ops.close()
    }

    private suspend fun dispatchLoop() =
        coroutineScope {
            for (op in ops) {
                when (op) {
                    is RunnerOp.Close -> {
                        logger.info("Session {} (process {}): closed by Homer ({})", claim.sessionId, claim.processId, op.reason)
                        killAll()
                        return@coroutineScope
                    }

                    is RunnerOp.Cancel -> {
                        cancelledOps += op.opId
                        running[op.opId]?.kill()
                    }

                    is RunnerOp.Interrupt -> {
                        // Off the dispatcher: the interrupt waits out the grace and would park Cancel and Close.
                        running[op.opId]?.let { handle ->
                            val grace = interruptGraces[op.opId]
                            launch(Dispatchers.IO) { handle.interrupt(grace) }
                        }
                    }

                    is RunnerOp.Cmd -> {
                        launch { deliver(runCmd(op)) }
                    }

                    else -> {
                        deliver(workspace.execute(op))
                    }
                }
            }
        }

    /**
     * Posts [result] on a [workerScope] child tracked in [deliveryJobs], so the caller never waits on a
     * parked `next`. Does nothing once [lost] is set.
     */
    private fun deliver(result: OpResult) {
        if (lost) return
        val job = workerScope.launch { postResult(result)?.let(::handOff) }
        deliveryJobs += job
        job.invokeOnCompletion { deliveryJobs -= job }
    }

    /**
     * Posts [result] to Homer, retrying transient failures; returns the op Homer hands back, or null
     * when there is none, delivery gave up or the session was lost. Leaves [ops] to [handOff].
     */
    private suspend fun postResult(result: OpResult): RunnerOp? {
        var attempt = 0
        while (true) {
            try {
                return api.next(claim.sessionId, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RunnerApiException) {
                logger.warn("Session {}: result for op {} refused: {}", claim.sessionId, result.opId, e.message)
                if (e.isClientError || ++attempt > MAX_RETRIES) return null
                delay(RETRY_DELAY)
            } catch (e: Exception) {
                if (++attempt > MAX_RETRIES) {
                    lost("could not deliver the result of op ${result.opId}: ${e.message}")
                    return null
                }
                delay(RETRY_DELAY)
            }
        }
    }

    /**
     * Hands [op] to the dispatcher without suspending. A closed channel means the session is ending; a
     * full one breaks the one-op-in-flight assumption of `ops`'s bound, so the session is dropped.
     */
    private fun handOff(op: RunnerOp) {
        val result = ops.trySend(op)
        if (result.isFailure && !result.isClosed) {
            logger.warn("Session {}: op queue full handing off {}, dropping the session", claim.sessionId, op.opId)
            lost("op queue full handing off ${op.opId}")
        }
    }

    private suspend fun runCmd(op: RunnerOp.Cmd): OpResult {
        val cwd = Path.of(op.cwd).toAbsolutePath().normalize()
        if (!cwd.startsWith(sessionRoot)) return OpResult(op.opId, ok = false, error = "cwd is outside the session root")
        val handle =
            try {
                ProcessRunner.start(op.argv, cwd, op.env, op.stdin)
            } catch (e: Exception) {
                return OpResult(op.opId, ok = false, error = "failed to start: ${e.message}")
            }
        running[op.opId] = handle
        // The peer-supplied grace is clamped: an unbounded value would pin the reap (ADR-0047 rule 4).
        val grace = clampInterruptGrace(op.interruptGraceSeconds)
        grace?.let { interruptGraces[op.opId] = it }
        // A Cancel that raced ahead sits in cancelledOps; honour it now or the process runs to its timeout.
        if (op.opId in cancelledOps) handle.kill()
        try {
            val exit =
                handle.await(op.timeoutMs.milliseconds, grace) { stream, seq, bytes ->
                    postChunk(op.opId, stream, seq, bytes)
                }
            return cmdResult(claim.sessionId, op.opId, exit)
        } finally {
            running.remove(op.opId)
            cancelledOps.remove(op.opId)
            interruptGraces.remove(op.opId)
        }
    }

    private suspend fun postChunk(
        opId: String,
        stream: String,
        seq: Long,
        bytes: ByteArray,
    ) {
        var attempt = 0
        while (true) {
            try {
                api.postOutput(claim.sessionId, opId, stream, seq, bytes)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRefusal(e)) {
                    // 409 = Homer already has this seq (a retried post), 413 = past the cap, 404 = op gone: nothing to retry.
                    logger.warn("Session {}: output chunk {}#{} refused: {}", claim.sessionId, stream, seq, e.message)
                    return
                }
                // 5xx and 429 are retried: dropping the chunk would desync the stream's seq, and Homer
                // would 409 every later chunk of the command (ADR-0066).
                if (++attempt > MAX_RETRIES) {
                    logger.warn(
                        "Session {}: dropping output chunk {}#{} after {} attempts: {}",
                        claim.sessionId,
                        stream,
                        seq,
                        attempt,
                        e.message,
                    )
                    return
                }
                delay(RETRY_DELAY)
            }
        }
    }

    /** Whether a failed output post was refused for good: a 4xx other than 429. */
    private fun isRefusal(e: Exception): Boolean = e is RunnerApiException && e.isClientError && e.status != 429

    private fun killAll() = running.values.forEach { it.kill() }

    companion object {
        private const val MAX_RETRIES = 5
        private const val OPS_CAPACITY = 16
        private val RETRY_DELAY = 1.seconds
        private val FAST_POLL_THRESHOLD = 1.seconds
        private val FAST_POLL_DELAY = 250.milliseconds
    }
}

/**
 * The `cmd` result for [opId], warning once per stream whose drain [exit] abandoned unsent chunks.
 * Logs ids and counts only, never chunk contents, which may hold secrets (ADR-0047 rule 7).
 */
private fun cmdResult(
    sessionId: String,
    opId: String,
    exit: ProcessRunner.Exit,
): OpResult {
    exit.cutStreams.forEach { stream ->
        logger.warn(
            "Session {}: op {}: {} output cut after {} chunk(s), the unsent tail was dropped",
            sessionId,
            opId,
            stream,
            exit.chunksFor(stream),
        )
    }
    return OpResult(
        opId,
        exitCode = exit.code,
        timedOut = exit.timedOut,
        stdoutChunks = exit.stdoutChunks,
        stderrChunks = exit.stderrChunks,
    )
}

private val logger = LoggerFactory.getLogger(SessionWorker::class.java)

/** What [SessionWorker]'s poller does after a failed `next`. */
internal sealed interface PollVerdict {
    /** The session is gone: kill what runs and stop, for [reason]. */
    data class Lost(
        val reason: String,
    ) : PollVerdict

    /** Retry after the retry delay, logging [warning] first when it is set. */
    data class Retry(
        val warning: String?,
    ) : PollVerdict
}

/**
 * Judges a failed `next` poll of [sessionId], [unreachableMs] after the last success: 404 loses the
 * session, other 4xx, 5xx and connection errors retry until past [heartbeatTimeout] (ADR-0066).
 */
internal fun pollFailureVerdict(
    e: Exception,
    sessionId: String,
    unreachableMs: Long,
    heartbeatTimeout: Duration,
): PollVerdict =
    when {
        e is RunnerApiException && e.status == 404 -> {
            PollVerdict.Lost("Homer no longer knows session $sessionId: ${e.message}")
        }

        e is RunnerApiException && e.status < 500 -> {
            PollVerdict.Retry(e.message)
        }

        // A persistent 5xx is the same unreachable condition as a connection error: same heartbeat deadline.
        unreachableMs > heartbeatTimeout.inWholeMilliseconds -> {
            PollVerdict.Lost("Homer unreachable for ${heartbeatTimeout.inWholeSeconds} s (${e.message})")
        }

        else -> {
            PollVerdict.Retry(if (e is RunnerApiException) e.message else null)
        }
    }

/** Upper bound on a peer-supplied `Cmd.interruptGraceSeconds`, the runner-side half of Homer's `0..600` validation. */
internal const val MAX_INTERRUPT_GRACE_SECONDS = 600L

/**
 * The peer-supplied grace [seconds] clamped to `0..[MAX_INTERRUPT_GRACE_SECONDS]`: a negative value would
 * throw out of the reap's `waitFor`, a huge one would pin it (ADR-0047 rule 4).
 */
internal fun clampInterruptGrace(seconds: Long?): Duration? = seconds?.coerceIn(0, MAX_INTERRUPT_GRACE_SECONDS)?.seconds
