package homer.app.runners.ssh

import homer.app.exceptions.OutputCapExceededException
import homer.app.exceptions.SshConnectionLostException
import homer.app.exceptions.SshExecRefusedException
import homer.app.infra.KillCause
import homer.app.infra.LineFilteringOutputStream
import homer.app.infra.OutputLineFilter
import homer.app.infra.ShellExecutor
import homer.app.infra.appendKillNotices
import homer.app.models.CommandResult
import homer.app.runners.exceedsOutputCap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.channel.ClientChannelEvent
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.channel.exception.SshChannelOpenException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.outputStream
import kotlin.time.Duration

/**
 * Runs each command in its own SSH exec channel, argv kept by [RemoteCommandBuilder] and env in a file under [envDir]
 * written over [sync], under KubernetesShellExecutor's timeout, cancellation and output-cap contract. With
 * [reapProcessGroup] the command leads its own remote process group, killed over a control channel first (ADR-0041).
 */
class SshShellExecutor(
    private val session: ClientSession,
    private val remoteWorkdir: String,
    private val sync: WorkspaceSync,
    private val envDir: String,
    private val maxOutputBytes: Long = Long.MAX_VALUE,
    private val reapProcessGroup: Boolean = false,
    private val reapDir: String? = null,
) : ShellExecutor {
    private val reapCounter = AtomicLong(0)

    // ShellExecutor.cmd's override signature (fixed by the interface) and IO hop, as in KubernetesShellExecutor.
    // jscpd:ignore-start
    override suspend fun cmd(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        stdIn: String?,
        env: Map<String, String>,
        timeout: Duration,
        stdOutFilter: OutputLineFilter?,
        interruptGrace: Duration?,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            // jscpd:ignore-end
            // Non-null exactly when the command reaps its process group.
            val pgidFile = if (reapProcessGroup && reapDir != null) "$reapDir/cmd-${reapCounter.incrementAndGet()}.pgid" else null
            val envFile = if (env.isNotEmpty()) writeEnvFile(env) else null
            val remoteCmd = remoteCommand(command, envFile, pgidFile)
            val channel = session.createExecChannel(remoteCmd)
            // use{} closes the channel on every path; the catch below closes it forcibly first, and the
            // graceful close after that is idempotent.
            channel.use {
                val rawOut: OutputStream = stdOut.outputStream()
                val filteredOut: OutputStream = if (stdOutFilter != null) LineFilteringOutputStream(rawOut, stdOutFilter) else rawOut
                filteredOut.use { out ->
                    stdErr.outputStream().use { err ->
                        channel.setOut(out)
                        channel.setErr(err)
                        if (stdIn != null) {
                            channel.setIn(ByteArrayInputStream(stdIn.toByteArray(Charsets.UTF_8)))
                        }
                        openChannel(channel)

                        val kill = KillCause()
                        try {
                            awaitClose(channel, stdOut, stdErr, timeout, kill)
                        } catch (e: Throwable) {
                            withContext(NonCancellable) {
                                // Reap the command's own group before closing the channel: neither the close nor an
                                // SSH signal reaches a group outside the session's own (ADR-0041).
                                reapOnStop(pgidFile, kill, interruptGrace)
                                runCatching { channel.close(true) }
                                appendKillNotices(stdErr, kill, timeout, maxOutputBytes)
                            }
                            throw e
                        }

                        commandResult(channel, stdOut, stdErr)
                    }
                }
            }
        }

    /** Uploads [env] as a new owner-only file under a random name in [envDir], the path returned. */
    private suspend fun writeEnvFile(env: Map<String, String>): String {
        val envFile = "$envDir/${UUID.randomUUID()}.env"
        sync.writePrivateFile(envFile, RemoteCommandBuilder.envFileContent(envFile, env).toByteArray(Charsets.UTF_8))
        return envFile
    }

    /** The remote argv for [command]: the reaping launcher when the command reports its own group. */
    private fun remoteCommand(
        command: List<String>,
        envFile: String?,
        pgidFile: String?,
    ): String =
        if (pgidFile != null) {
            RemoteCommandBuilder.buildReaping(remoteWorkdir, envFile, command, pgidFile)
        } else {
            RemoteCommandBuilder.build(remoteWorkdir, envFile, command)
        }

    /** The channel's exit status as the result; a channel closed without one fails the step ([noExitStatus]). */
    private fun commandResult(
        channel: ChannelExec,
        stdOut: Path,
        stdErr: Path,
    ): CommandResult {
        val exit =
            channel.exitStatus
                ?: throw noExitStatus()
        return CommandResult(exitCode = exit, stdOut = stdOut, stdErr = stdErr)
    }

    /**
     * Opens [channel], bounded by [OPEN_TIMEOUT_MS] for the handshake phase alone, distinct from the
     * command's own `timeout`. Only the server's open rejection is a refusal (W-021); a timeout or a
     * dropped handshake is a lost connection — either answer's own text quotes the command, so neither is reported.
     */
    private fun openChannel(channel: ChannelExec) {
        try {
            channel.open().verify(OPEN_TIMEOUT_MS)
        } catch (_: SshChannelOpenException) {
            throw SshExecRefusedException()
        } catch (_: IOException) {
            throw SshConnectionLostException("SSH channel open failed")
        }
    }

    /**
     * A channel that yields no exit status on a session still open was refused by the server — MINA
     * closes it without a state of its own, leaving the session as the only signal (W-021) — while a
     * session that is gone or closing is a lost connection.
     */
    private fun noExitStatus(): RuntimeException =
        if (session.isOpen && !session.isClosing) {
            SshExecRefusedException()
        } else {
            SshConnectionLostException("SSH channel closed without an exit status")
        }

    /**
     * Interrupts then kills the remote group the command self-reported, when it has one and reaping
     * is on: only a timeout stop carries [interruptGrace] (ADR-0108); a cap kill or a caller
     * cancellation keeps the immediate SIGKILL.
     */
    private fun reapOnStop(
        pgidFile: String?,
        kill: KillCause,
        interruptGrace: Duration?,
    ) {
        if (pgidFile == null) return
        val grace = if (kill.byTimeout) interruptGrace else null
        runCatching { reapRemoteGroup(pgidFile, grace) }
    }

    /**
     * Waits until [channel] closes; throws on cancellation, on [timeout], or when the output passes
     * the cap, setting [kill]'s matching flag first.
     */
    private suspend fun awaitClose(
        channel: ChannelExec,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
        kill: KillCause,
    ) {
        val start = System.currentTimeMillis()
        var closed = false
        while (!closed && System.currentTimeMillis() - start < timeout.inWholeMilliseconds) {
            closed = pollClosed(channel, stdOut, stdErr, timeout, kill)
        }
        if (!closed) {
            kill.byTimeout = true
            throw CancellationException("Timeout reached. $timeout.")
        }
        // MINA has flushed all output by CLOSED, so a command that passed the cap after the last poll fails
        // here too; only a filtered stdout's last partial line lands later, when its stream closes.
        if (exceedsOutputCap(stdOut, stdErr, maxOutputBytes)) {
            kill.byCap = true
            throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
        }
    }

    /** One poll of [awaitClose]: whether [channel] closed, throwing on cancellation or a passed output cap. */
    private suspend fun pollClosed(
        channel: ChannelExec,
        stdOut: Path,
        stdErr: Path,
        timeout: Duration,
        kill: KillCause,
    ): Boolean {
        val events =
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), POLL_INTERVAL_MS)
        val closed = events.contains(ClientChannelEvent.CLOSED)
        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Command cancelled after $timeout")
        }
        if (!closed && exceedsOutputCap(stdOut, stdErr, maxOutputBytes)) {
            kill.byCap = true
            throw OutputCapExceededException("Output cap exceeded: $maxOutputBytes bytes.")
        }
        return closed
    }

    /**
     * Over a fresh control channel, SIGKILLs the group the command recorded in [pgidFile], or with [interruptGrace]
     * SIGINTs it, waits the grace, then SIGKILLs it. Only a plain integer pgid above 1 is signalled, never `-1`;
     * a missing or invalid file signals nothing, and the caller ignores a failed kill (ADR-0041).
     */
    private fun reapRemoteGroup(
        pgidFile: String,
        interruptGrace: Duration?,
    ) {
        val script =
            if (interruptGrace != null) {
                RemoteCommandBuilder.buildReapInterrupt(pgidFile, interruptGrace.inWholeSeconds)
            } else {
                RemoteCommandBuilder.buildReapKill(pgidFile)
            }
        val kill = session.createExecChannel(script)
        kill.use {
            it.open().verify(OPEN_TIMEOUT_MS)
            it.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), REAP_TIMEOUT_MS + (interruptGrace?.inWholeMilliseconds ?: 0L))
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val OPEN_TIMEOUT_MS = 10_000L
        private const val REAP_TIMEOUT_MS = 5_000L
    }
}
