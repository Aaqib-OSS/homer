package homer.app.infra

import org.slf4j.Logger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.PatternSyntaxException

/**
 * Runs author-supplied regexes under a wall-clock bound so ReDoS cannot pin a CPU; a timeout raises
 * [RegexTimeoutException]. The pool and queue are bounded, and past them a submission is shed as
 * [RegexShedException]. See ADR-0047 rules 4-5.
 */
internal object BoundedRegex {
    const val DEFAULT_TIMEOUT_MS = 1000L

    /** Matches evaluated at once; a submission waits for a worker at most [DEFAULT_TIMEOUT_MS]. */
    private const val WORKER_THREADS = 4

    /** Submissions waiting for a worker; past this one is shed, never queued further. */
    private const val QUEUE_CAPACITY = 16

    private val executor =
        ThreadPoolExecutor(
            WORKER_THREADS,
            WORKER_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(QUEUE_CAPACITY),
            { r -> Thread(r, "bounded-regex").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )

    open class RegexTimeoutException(
        timeoutMs: Long,
        message: String = "Regex evaluation exceeded ${timeoutMs}ms (possible catastrophic backtracking)",
    ) : RuntimeException(message)

    /**
     * The match never ran: the pool and its queue were full, so the submission was shed.
     * A [RegexTimeoutException], so a caller failing closed on a timeout fails closed on a shed too.
     */
    class RegexShedException(
        timeoutMs: Long,
    ) : RegexTimeoutException(timeoutMs, "Regex submission shed: all evaluation workers are busy (pool saturated)")

    fun find(
        regex: Regex,
        input: CharSequence,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): MatchResult? = run(timeoutMs) { regex.find(InterruptibleCharSequence(input)) }

    fun matches(
        regex: Regex,
        input: CharSequence,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean = run(timeoutMs) { regex.matches(InterruptibleCharSequence(input)) }

    /**
     * Compiles and finds [pattern] in [input], or `null` on timeout, shed or a non-compiling pattern;
     * [onTimeout] runs on the first two. Only [envName] is logged, as [input] may hold a secret (ADR-0047 rule 7).
     */
    fun findOrOmit(
        pattern: String,
        input: CharSequence,
        envName: String,
        logger: Logger,
        onTimeout: () -> Unit = {},
    ): MatchResult? {
        val regex =
            try {
                Regex(pattern)
            } catch (_: PatternSyntaxException) {
                logger.warn("Regex pattern for '{}' does not compile; omitting value", envName)
                return null
            }
        return try {
            find(regex, input)
        } catch (_: RegexShedException) {
            onTimeout()
            logger.warn("Regex match for '{}' was shed: evaluation pool saturated; omitting value", envName)
            null
        } catch (_: RegexTimeoutException) {
            onTimeout()
            logger.warn("Regex match for '{}' exceeded the time bound; omitting value", envName)
            null
        }
    }

    @Suppress("SwallowedException") // unwraps the ExecutionException to the block's own failure
    private fun <T> run(
        timeoutMs: Long,
        block: () -> T,
    ): T {
        val future =
            try {
                executor.submit(Callable { block() })
            } catch (_: RejectedExecutionException) {
                // Pool and queue are full: shed the caller rather than let the backlog grow.
                throw RegexShedException(timeoutMs)
            }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true) // interrupts the worker; its next charAt throws
            throw RegexTimeoutException(timeoutMs)
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is InterruptedException, is InterruptedRegexException -> throw RegexTimeoutException(timeoutMs)
                null -> throw e
                else -> throw cause
            }
        }
    }
}

/** Thrown by [InterruptibleCharSequence] when the evaluating thread is interrupted mid-match. */
private class InterruptedRegexException : RuntimeException()

/**
 * CharSequence that checks for thread interruption on every character read, so a
 * backtracking `Matcher` (which re-reads characters in its hot loop) aborts promptly
 * when [BoundedRegex] cancels the worker.
 */
private class InterruptibleCharSequence(
    private val inner: CharSequence,
) : CharSequence {
    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        if (Thread.interrupted()) throw InterruptedRegexException()
        return inner[index]
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = InterruptibleCharSequence(inner.subSequence(startIndex, endIndex))

    override fun toString(): String = inner.toString()
}
