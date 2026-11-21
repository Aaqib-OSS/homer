package homer.app.runners.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.slf4j.Logger
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * [WorkspaceSync]'s deadline on blocking SFTP round trips: opens a client per operation and bounds every
 * call, the client's close included, by [transferTimeout] (CWE-400). A watchdog closes [session], since
 * `withTimeout` cannot interrupt a blocked JVM read (ADR-0064).
 */
internal class SftpTransferDeadline(
    private val session: ClientSession,
    private val transferTimeout: Duration,
    private val openSftp: (ClientSession) -> SftpClient,
    private val logger: Logger,
) {
    /**
     * Runs [block] on a fresh client opened under the deadline, released under it afterwards; [timeout] defaults to
     * the transfer timeout.
     */
    @Suppress("SuspendFunInFinallySection") // release() is NonCancellable inside
    suspend fun <T> withSftp(
        timeout: Duration = transferTimeout,
        block: suspend (SftpClient) -> T,
    ): T {
        val sftp = withTransferTimeout(timeout) { openSftp(session) }
        try {
            return block(sftp)
        } finally {
            release(sftp, timeout)
        }
    }

    /**
     * Closes [sftp] under the transfer deadline, non-cancellably so the deadline holds for a cancelled caller
     * (CWE-400). A failed or timed-out release is WARN-logged, never thrown over the block's outcome (ADR-0064).
     */
    @Suppress("SuspendFunSwallowedCancellation") // NonCancellable inside: a failure here is the close, not a cancel
    private suspend fun release(
        sftp: SftpClient,
        timeout: Duration,
    ) {
        try {
            withContext(NonCancellable) { withTransferTimeout(timeout) { sftp.close() } }
        } catch (e: Exception) {
            // CWE-400: shed the session, whose state is unknown, so no later withSftp strands another channel on
            // it while the run holds its lock and slot (ADR-0047 rule 5, ADR-0064).
            logger.warn("Failed to close the SFTP client; closing the session so nothing reuses it", e)
            runCatching { session.close(true) }
        }
    }

    // withTimeout cannot interrupt a blocked JVM read; watchdog closes
    // the session instead. Invariants and rejected alternatives: See ADR-0064 (SFTP blocking-I/O timeout via a watchdog coroutine).
    @Suppress("SuspendFunSwallowedCancellation") // rethrows the caller's cancellation; maps only the watchdog close
    suspend fun <T> withTransferTimeout(
        timeout: Duration = transferTimeout,
        block: suspend () -> T,
    ): T {
        val timedOut = AtomicBoolean(false)
        try {
            return coroutineScope {
                // Dispatchers.Default: the caller's dispatcher may be busy inside the blocking copy (ADR-0064).
                val watchdog =
                    launch(Dispatchers.Default) {
                        delay(timeout)
                        timedOut.set(true)
                        logger.warn("SFTP transfer exceeded the {} deadline; closing the session to unblock it", timeout)
                        runCatching { session.close(true) }
                    }
                try {
                    block()
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: Throwable) {
            // The caller's own cancellation (a killed run) stays a cancellation past the deadline.
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            if (timedOut.get()) throw IOException("SFTP transfer timed out after $timeout (peer stalled?)", e)
            throw e
        }
    }
}
