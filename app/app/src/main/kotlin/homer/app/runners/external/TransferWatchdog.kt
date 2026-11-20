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
 * Bounds one transfer handler by `transferTimeoutSec`: at the deadline a watchdog calls [release], failing the read or
 * write blocked on the peer's socket, which `withTimeout` alone never interrupts (ADR-0066 decision 7, ADR-0064; CWE-400).
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
