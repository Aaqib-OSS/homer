package homer.app.fixtures

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How long [eventually] and [pollUntil] poll before they give up: finite, so a wait fails a test. */
private val EVENTUALLY_TIMEOUT = 5.seconds

/** The pause between two polls: short enough to be quick, long enough not to spin (root `CLAUDE.md` §6). */
private val POLL_INTERVAL = 50.milliseconds

/**
 * Retries [block] until it passes, rethrowing its last failure once [timeout] elapses: a test
 * waits on the condition it asserts instead of on a fixed sleep, which is both slower and
 * flakier under load. [block] is an assertion — poll for a state, never for a side effect that
 * each attempt would repeat.
 *
 * The `:app` sibling of the e2e suite's `Awaits` (`homer.e2e.support`): the modules share no
 * test source, so the pattern is ported, not imported.
 */
suspend fun eventually(
    timeout: Duration = EVENTUALLY_TIMEOUT,
    poll: Duration = POLL_INTERVAL,
    block: suspend () -> Unit,
) {
    var lastFailure: Throwable? = null
    try {
        withTimeout(timeout) {
            while (true) {
                try {
                    block()
                    return@withTimeout
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastFailure = e
                }
                delay(poll)
            }
        }
    } catch (e: TimeoutCancellationException) {
        // The block's own failure is the debuggable one; the timeout alone says nothing.
        throw lastFailure ?: e
    }
}

/**
 * Polls [probe] every [pollMs] until it holds, and returns false once [timeoutMs] elapsed: the
 * blocking sibling of [eventually], for a caller that cannot suspend.
 */
fun pollUntil(
    timeoutMs: Long = EVENTUALLY_TIMEOUT.inWholeMilliseconds,
    pollMs: Long = POLL_INTERVAL.inWholeMilliseconds,
    probe: () -> Boolean,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (probe()) return true
        if (System.currentTimeMillis() >= deadline) return false
        Thread.sleep(pollMs)
    }
}

/** Polls (up to [timeoutMs]) for [pid] to no longer be a live process. */
fun awaitProcessExit(
    pid: Long,
    timeoutMs: Long = EVENTUALLY_TIMEOUT.inWholeMilliseconds,
    pollMs: Long = POLL_INTERVAL.inWholeMilliseconds,
): Boolean = pollUntil(timeoutMs, pollMs) { processGone(pid) }

private val PROC: Path = Path.of("/proc")

/**
 * Where `/proc` exists, reads its state: `ProcessHandle.isAlive` counts an unreaped zombie
 * (state `Z`) as alive, and a group-killed orphan may stay one under a CI container's PID 1.
 * Without `/proc` (macOS), asks `ProcessHandle`.
 */
private fun processGone(pid: Long): Boolean {
    if (!PROC.isDirectory()) return ProcessHandle.of(pid).map { !it.isAlive }.orElse(true)
    val stat = runCatching { PROC.resolve("$pid/stat").readText() }.getOrNull() ?: return true
    val state = stat.substringAfterLast(") ").trim().split(" ")[0]
    return state == "Z" || state == "X"
}
