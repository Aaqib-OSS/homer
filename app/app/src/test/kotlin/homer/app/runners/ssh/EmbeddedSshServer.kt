package homer.app.runners.ssh

import homer.app.runners.RemoteShell
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.SftpFileSystemAccessor
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpSubsystemProxy
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * In-process SSH+SFTP server for runner tests. Accepts user/password test/test,
 * runs commands via a real OS shell, serves SFTP rooted at a temp dir.
 *
 * No SCP support: `sshd-scp` is not on the classpath (only sshd-core + sshd-sftp
 * per Task 1) and SFTP is all WorkspaceSync needs.
 *
 * `commandFactory` is a small shell-wrapping [CommandFactory] rather than the
 * stock `ProcessShellCommandFactory`: that default splits the raw exec string
 * on whitespace (quote-stripped) and runs it as a bare argv, whereas real
 * `sshd` always hands the whole string to the login shell (`sh -c command`).
 * Runner code (`RemoteCommandBuilder`) relies on that real-world shell
 * semantics (`cd ... && exec sh -c '. "$0" && exec "$@"' file argv`), so the fixture must match it.
 */
class EmbeddedSshServer(
    private val refuseExec: Boolean = false,
    /** Runs each command's shell as the leader of its own session, as sshd does; needs `setsid` on the host. */
    private val shellLeadsSession: Boolean = false,
) : AutoCloseable {
    val host = "127.0.0.1"

    /** Every exec request string the server received, in arrival order. */
    val execRequests: MutableList<String> = CopyOnWriteArrayList()
    lateinit var rootDir: Path
        private set
    private lateinit var server: SshServer

    fun start(): Int {
        rootDir = Files.createTempDirectory("ssh-test-root")
        server =
            SshServer.setUpDefaultServer().apply {
                port = 0
                keyPairProvider = SimpleGeneratorHostKeyProvider(Files.createTempFile("hostkey", ".ser"))
                passwordAuthenticator =
                    PasswordAuthenticator { u, p, _ -> u == "test" && p == "test" }
                subsystemFactories =
                    listOf(
                        SftpSubsystemFactory().apply { fileSystemAccessor = StallingSftpAccessor },
                    )
                commandFactory =
                    CommandFactory { channel, command ->
                        execRequests.add(command)
                        (if (refuseExec) RefusingCommandFactory else ShellCommandFactory(shellLeadsSession)).createCommand(channel, command)
                    }
            }
        server.start()
        return server.port
    }

    fun newClientSession(client: SshClient): ClientSession {
        val session = client.connect("test", host, server.port).verify(5, TimeUnit.SECONDS).session
        session.addPasswordIdentity("test")
        session.auth().verify(5, TimeUnit.SECONDS)
        return session
    }

    override fun close() {
        if (::server.isInitialized) server.stop()
    }
}

/**
 * Refuses every exec request: the channel closes without the command running and without an exit
 * status, the shape a refusing `ForceCommand` or restricted shell leaves the client.
 */
private object RefusingCommandFactory : CommandFactory {
    override fun createCommand(
        channel: ChannelSession,
        command: String,
    ): Command = RefusingCommand
}

private object RefusingCommand : Command {
    override fun setInputStream(input: InputStream) = Unit

    override fun setOutputStream(output: OutputStream) = Unit

    override fun setErrorStream(error: OutputStream) = Unit

    override fun setExitCallback(callback: ExitCallback) = Unit

    override fun start(
        channel: ChannelSession,
        env: Environment,
    ) {
        // Graceful, not immediate: MINA skips the SSH_MSG_CHANNEL_CLOSE packet on an immediate
        // close, so the client would never see the refusal.
        channel.close(false)
    }

    override fun destroy(channel: ChannelSession) = Unit
}

/** See the shell-wrapping rationale in [EmbeddedSshServer]'s class doc. */
private class ShellCommandFactory(
    private val shellLeadsSession: Boolean,
) : CommandFactory {
    override fun createCommand(
        channel: ChannelSession,
        command: String,
    ): Command {
        // MINA runs its raw command under its own `/bin/sh -c`, so the session-leading shell is nested inside it.
        val shellCommand = if (shellLeadsSession) "exec setsid /bin/sh -c " + RemoteShell.quote(command) else command
        val shellArgv =
            if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("cmd.exe", "/C", shellCommand)
            } else {
                listOf("/bin/sh", "-c", shellCommand)
            }
        return ProcessShellFactory(shellCommand, shellArgv).createShell(channel)
    }
}

/**
 * SFTP accessor serving a stalling peer for the transfer-deadline tests
 * (CWE-400): every `stall*` file exists and its reads block forever after the
 * first chunk — the client keeps answering keepalives the whole time, which is
 * exactly the peer shape MINA's keepalive bound cannot catch. A real
 * filesystem cannot produce a read that never completes, so the accessor
 * intercepts the open call instead of relying on file contents.
 */
private object StallingSftpAccessor : SftpFileSystemAccessor by SftpFileSystemAccessor.DEFAULT {
    /**
     * Serves one full chunk, then blocks forever on the next transfer — a
     * short first read would be taken as EOF by the client (it sizes reads
     * from the stat-reported length), which is not the stall shape under
     * test. The block polls [closed] so the deadline firing on the client
     * (session close tears the subsystem down) releases this thread instead
     * of leaking it.
     */
    private class BlockingByteChannel : SeekableByteChannel {
        @Volatile
        private var closed = false

        private var firstChunkServed = false

        private fun blockUntilClosed() {
            while (!closed) {
                Thread.sleep(50)
            }
        }

        override fun read(dst: ByteBuffer): Int {
            if (firstChunkServed) blockUntilClosed()
            firstChunkServed = true
            var served = 0
            while (dst.hasRemaining()) {
                dst.put(0)
                served++
            }
            return if (closed) -1 else served
        }

        override fun write(source: ByteBuffer): Int {
            // Block on the first write already: a small payload is a single
            // write call, and (unlike reads) the client infers nothing from
            // write acks, so there is no chunk to serve for free.
            blockUntilClosed()
            throw java.nio.channels.ClosedChannelException()
        }

        override fun position(): Long = if (firstChunkServed) 1L else 0L

        override fun position(newPosition: Long): SeekableByteChannel = this

        override fun size(): Long = 1L

        override fun truncate(size: Long): SeekableByteChannel = throw IOException("not supported by stalling channel")

        override fun close() {
            closed = true
        }

        override fun isOpen(): Boolean = !closed
    }

    override fun openFile(
        subsystem: SftpSubsystemProxy,
        handle: FileHandle,
        file: Path,
        sftpAccess: String,
        options: Set<OpenOption>,
        vararg attributes: FileAttribute<*>,
    ): SeekableByteChannel =
        if (file.fileName.toString().startsWith("stall")) {
            BlockingByteChannel()
        } else {
            SftpFileSystemAccessor.DEFAULT.openFile(subsystem, handle, file, sftpAccess, options, *attributes)
        }

    /**
     * Stall remove operations on stall-named files. The client-side sftp.remove()
     * throws when the watchdog calls session.close(true), so this thread just needs
     * to keep blocking; it is interrupted when EmbeddedSshServer.close() stops MINA.
     */
    override fun removeFile(
        subsystem: SftpSubsystemProxy,
        path: Path,
        isDirectory: Boolean,
    ) {
        if (path.fileName.toString().startsWith("stall")) {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            throw IOException("stall: interrupted during remove")
        }
        SftpFileSystemAccessor.DEFAULT.removeFile(subsystem, path, isDirectory)
    }
}
