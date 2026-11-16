package homer.app.runners.external

import homer.app.exceptions.HomerException
import homer.app.exceptions.RunnerTransferTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Bounds one transfer handler in time. `transferTimeoutSec` is otherwise enforced
 * only in `ExternalRunnerSession.await`, and a bound living in another component
 * is not a bound (§12 rule 4): without this an instance that stops sending or
 * reading pins a `Dispatchers.IO` thread — the pool every SQLite transaction
 * uses — and its connection for as long as it likes.
 *
 * `withTimeout` alone cannot enforce it: the copy blocks that thread on the peer's
 * socket and never observes cancellation. At the deadline a watchdog calls
 * [release], which cancels the call's byte channel and fails the blocked read or
 * write — the ADR-0064 shape. CWE-400.
 */
@Suppress("SuspendFunSwallowedCancellation") // rethrows the caller's cancellation; maps only the cancelled channel
internal suspend fun <T> ExternalRunnerDomain.withTransferTimeout(
    release: (Throwable) -> Unit,
    block: suspend () -> T,
): T {
    val timedOut = AtomicBoolean(false)
    try {
        return coroutineScope {
            // Dispatchers.Default: the IO pool is exactly what a stalled peer saturates.
            val watchdog =
                launch(Dispatchers.Default) {
                    delay(config.transferTimeoutSec.seconds)
                    timedOut.set(true)
                    release(RunnerTransferTimeoutException(alias, config.transferTimeoutSec))
                }
            try {
                block()
            } finally {
                watchdog.cancel()
            }
        }
    } catch (e: SecurityException) {
        // Only the cancelled channel's failure becomes the timeout: a refusal the transfer made
        // itself (422 containment, 413 size) keeps its own status even past the deadline.
        throw e
    } catch (e: HomerException) {
        throw e
    } catch (e: Throwable) {
        // The caller's own cancellation (disconnect, shutdown) stays a cancellation past the deadline.
        if (e is CancellationException) currentCoroutineContext().ensureActive()
        if (timedOut.get()) throw RunnerTransferTimeoutException(alias, config.transferTimeoutSec)
        throw e
    }
}
