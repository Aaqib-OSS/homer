package homer.runner

import homer.app.runners.external.ClaimResponse
import homer.app.runners.external.OpResult
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.runners.external.RegisterInstanceResponse
import homer.app.runners.external.RunnerOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readBytes

/** Scripted Homer: ops go in through [ops], everything the worker sends comes out through the lists and channels. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeRunnerApi : RunnerApi {
    val ops = Channel<RunnerOp>(Channel.UNLIMITED)
    val results = Channel<OpResult>(Channel.UNLIMITED)
    val output = Collections.synchronizedList(mutableListOf<Triple<String, Long, ByteArray>>())
    val postedTars = Collections.synchronizedList(mutableListOf<Pair<String, ByteArray>>())
    val claims = Channel<ClaimResponse?>(Channel.UNLIMITED)
    val registrations = Collections.synchronizedList(mutableListOf<RegisterInstanceRequest>())
    val deregistrations = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    var tarToServe: ByteArray = byteArrayOf()

    @Volatile
    var registerAnswer: () -> RegisterInstanceResponse = { RegisterInstanceResponse("inst-1", 30, 60) }

    @Volatile
    var nextFailure: (() -> Throwable)? = null

    @Volatile
    var claimFailure: (() -> Throwable)? = null

    @Volatile
    var postTarFailure: (() -> Throwable)? = null

    /** Thrown by the next `postOutput` calls, one per call, before any is recorded. */
    val postOutputFailures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

    /** Held before each `postOutput` is recorded, so a test can keep a chunk post in flight. */
    @Volatile
    var postOutputDelayMs = 0L

    /** When true, `postOutput` never returns: the drain is left with a stream it cannot finish. */
    @Volatile
    var postOutputStalls = false

    var nextPollMs = 100L

    /**
     * When true, a `next()` carrying a completed result records it and waits out [nextPollMs] with no op,
     * emulating a poller that always wins the race for the next op: an inline result post would park.
     */
    @Volatile
    var resultsNeverCarryOps = false

    /** Total `next()` calls received, including ones that threw via [nextFailure]. */
    val nextCallCount = AtomicInteger(0)

    /** Thrown by the next result-carrying `next()` calls, one per call, before the result is recorded. */
    val resultFailures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

    /** Result-carrying `next()` calls received, including ones that threw via [resultFailures]. */
    val resultAttempts = AtomicInteger(0)

    override suspend fun register(request: RegisterInstanceRequest): RegisterInstanceResponse {
        registrations += request
        return registerAnswer()
    }

    /** Thrown by the next `deregister` calls, one per call; [deregisterCalls] counts them too. */
    val deregisterFailures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    val deregisterCalls = AtomicInteger(0)

    /** Suspends `deregister` before it records anything, like a real HTTP call; cancelled calls record nothing. */
    @Volatile
    var deregisterDelayMs = 0L

    override suspend fun deregister(instanceId: String) {
        deregisterCalls.incrementAndGet()
        if (deregisterDelayMs > 0) delay(deregisterDelayMs)
        deregisterFailures.poll()?.let { throw it }
        deregistrations += instanceId
    }

    /** Total `claim()` calls received, including ones that threw via [claimFailure]. */
    val claimCalls = AtomicInteger(0)

    override suspend fun claim(instanceId: String): ClaimResponse? {
        claimCalls.incrementAndGet()
        claimFailure?.let { throw it() }
        // select, not withTimeoutOrNull: a send resuming the receiver as the timeout fires would be
        // cancelled with it and the claim lost.
        return select {
            claims.onReceive { it }
            onTimeout(nextPollMs) { null }
        }
    }

    override suspend fun next(
        sessionId: String,
        completed: OpResult?,
    ): RunnerOp? {
        nextCallCount.incrementAndGet()
        nextFailure?.let { throw it() }
        if (completed != null) {
            resultAttempts.incrementAndGet()
            resultFailures.poll()?.let { throw it }
        }
        completed?.let { results.send(it) }
        if (completed != null && resultsNeverCarryOps) {
            delay(nextPollMs)
            return null
        }
        return select {
            ops.onReceive { it }
            onTimeout(nextPollMs) { null }
        }
    }

    override suspend fun fetchTar(
        sessionId: String,
        opId: String,
        consume: (InputStream) -> Unit,
    ) = consume(ByteArrayInputStream(tarToServe))

    override suspend fun postTar(
        sessionId: String,
        opId: String,
        kind: String,
        file: Path,
    ) {
        postTarFailure?.let { throw it() }
        postedTars += kind to file.readBytes()
    }

    override suspend fun postOutput(
        sessionId: String,
        opId: String,
        stream: String,
        seq: Long,
        bytes: ByteArray,
    ) {
        if (postOutputStalls) awaitCancellation()
        if (postOutputDelayMs > 0) delay(postOutputDelayMs)
        postOutputFailures.poll()?.let { throw it }
        output += Triple(stream, seq, bytes)
    }

    suspend fun result(timeoutMs: Long = 5_000): OpResult =
        withTimeoutOrNull(timeoutMs) { results.receive() } ?: error("no result within $timeoutMs ms")
}
