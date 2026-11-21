package homer.app.runners.ssh

import homer.app.runners.TarStreams
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WorkspaceSyncPrivateFileTest {
    private lateinit var server: EmbeddedSshServer
    private lateinit var client: SshClient
    private lateinit var sync: WorkspaceSync

    @BeforeEach
    fun setup() {
        server = EmbeddedSshServer().also { it.start() }
        client = SshClient.setUpDefaultClient().also { it.start() }
        sync = WorkspaceSync(server.newClientSession(client), 30.seconds)
    }

    @AfterEach
    fun teardown() {
        client.stop()
        server.close()
    }

    private fun permissions(mode: String): Int = TarStreams.posixToMode(PosixFilePermissions.fromString(mode))

    private fun mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))

    @Test
    fun `writes the content to an owner-only file in a new owner-only directory`() =
        runBlocking {
            val file = server.rootDir.resolve("p1/.homer/env/a.env")
            // Past one write chunk, so the content goes out in several writes.
            val content = ByteArray(80_000) { (it % 251).toByte() }
            sync.writePrivateFile(file.toString(), content)
            assertContentEquals(content, file.readBytes())
            assertEquals("rw-------", mode(file))
            assertEquals("rwx------", mode(file.parent))
        }

    @Test
    fun `tightens an existing directory that others can open to owner-only`() =
        runBlocking {
            val dir = server.rootDir.resolve("p1/.homer/env").createDirectories()
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
            sync.writePrivateFile(dir.resolve("a.env").toString(), "x".toByteArray())
            assertEquals("rwx------", mode(dir))
        }

    @Test
    fun `refuses to write over an existing file`() =
        runBlocking {
            val dir = server.rootDir.resolve("env").createDirectories()
            val existing = dir.resolve("a.env").also { it.writeText("planted") }
            assertThrows<SftpException> { sync.writePrivateFile(existing.toString(), "s3cr3t-Ab9-env-value".toByteArray()) }
            assertEquals("planted", existing.readText())
        }

    @Test
    fun `refuses a directory that is a symlink and writes nothing through it`() =
        runBlocking {
            val target = server.rootDir.resolve("elsewhere").createDirectories()
            val link = Files.createSymbolicLink(server.rootDir.resolve("env"), target)
            assertThrows<SecurityException> { sync.writePrivateFile(link.resolve("a.env").toString(), "x".toByteArray()) }
            assertFalse(target.resolve("a.env").exists())
        }

    @Test
    fun `a server that leaves the file readable by others fails the write and the file is removed`() =
        runBlocking {
            val sftp = mockk<SftpClient>(relaxed = true)
            val handle = mockk<SftpClient.CloseableHandle>(relaxed = true)
            every { sftp.lstat(any()) } returns SftpClient.Attributes().perms(S_IFDIR or permissions("rwx------"))
            every { sftp.open(any<String>(), *anyVararg<SftpClient.OpenMode>()) } returns handle
            every { sftp.stat(any<SftpClient.Handle>()) } returns SftpClient.Attributes().perms(permissions("rw-r--r--"))
            val ignoring = WorkspaceSync(mockk<ClientSession>(relaxed = true), 30.seconds, openSftp = { sftp })

            assertThrows<SecurityException> { ignoring.writePrivateFile("/w/.homer/env/a.env", "s3cr3t-Ab9-env-value".toByteArray()) }
            verify(exactly = 0) { sftp.write(any<SftpClient.Handle>(), any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) }
            verify { sftp.remove("/w/.homer/env/a.env") }
        }

    @Test
    fun `a write that fails after the create removes the file`() =
        runBlocking {
            val sftp = mockk<SftpClient>(relaxed = true)
            every { sftp.lstat(any()) } returns SftpClient.Attributes().perms(S_IFDIR or permissions("rwx------"))
            every { sftp.open(any<String>(), *anyVararg<SftpClient.OpenMode>()) } returns mockk(relaxed = true)
            every { sftp.stat(any<SftpClient.Handle>()) } returns SftpClient.Attributes().perms(permissions("rw-------"))
            every { sftp.write(any<SftpClient.Handle>(), any<Long>(), any<ByteArray>(), any<Int>(), any<Int>()) } throws
                IOException("quota")
            val failing = WorkspaceSync(mockk<ClientSession>(relaxed = true), 30.seconds, openSftp = { sftp })

            assertThrows<IOException> { failing.writePrivateFile("/w/.homer/env/a.env", "x".toByteArray()) }
            verify { sftp.remove("/w/.homer/env/a.env") }
        }

    @Test
    fun `removeIfExists removes a file and tolerates a missing one`() =
        runBlocking {
            val file = server.rootDir.resolve("gone.env").also { it.writeText("x") }
            sync.removeIfExists(file.toString(), 5.seconds)
            assertFalse(file.exists())
            sync.removeIfExists(file.toString(), 5.seconds)
        }

    @Test
    fun `a removal the peer stalls fails at its deadline, closing the session to unblock it`() =
        runBlocking {
            val unblocked = CountDownLatch(1)
            val sftp = mockk<SftpClient>(relaxed = true)
            every { sftp.remove("/w/.homer/env/a.env") } answers {
                // A blocked SFTP read returns only once its session is closed.
                if (!unblocked.await(10, TimeUnit.SECONDS)) error("the deadline never closed the session")
                throw IOException("session closed")
            }
            val session = mockk<ClientSession>(relaxed = true)
            every { session.close(true) } answers {
                unblocked.countDown()
                mockk(relaxed = true)
            }
            val stalled = WorkspaceSync(session, 30.seconds, openSftp = { sftp })

            val startedAt = System.nanoTime()
            val e = assertThrows<IOException> { stalled.removeIfExists("/w/.homer/env/a.env", 1.seconds) }
            val tookMs = (System.nanoTime() - startedAt) / 1_000_000

            assertContains(e.message.orEmpty(), "timed out after 1s")
            assertTrue(tookMs in 900..9_000, "the removal waited $tookMs ms; its deadline is 1 s")
            verify { session.close(true) }
        }

    @Test
    fun `a directory that turns into a symlink while it is created is refused and nothing is written`() =
        runBlocking {
            val sftp = mockk<SftpClient>(relaxed = true)
            val missing = SftpException(SftpConstants.SSH_FX_NO_SUCH_FILE, "missing")
            val symlink = SftpClient.Attributes().perms(S_IFLNK or permissions("rwxrwxrwx"))
            every { sftp.lstat("/w/.homer/env") } throws missing andThen symlink
            val racing = WorkspaceSync(mockk<ClientSession>(relaxed = true), 30.seconds, openSftp = { sftp })

            assertThrows<SecurityException> { racing.writePrivateFile("/w/.homer/env/a.env", "x".toByteArray()) }
            verify(exactly = 0) { sftp.setStat(any<String>(), any()) }
            verify(exactly = 0) { sftp.open(any<String>(), *anyVararg<SftpClient.OpenMode>()) }
        }

    private companion object {
        /** The directory type bit of an SFTP `permissions` field. */
        const val S_IFDIR = 0x4000

        /** The symbolic-link type bits of an SFTP `permissions` field. */
        const val S_IFLNK = 0xA000
    }
}
