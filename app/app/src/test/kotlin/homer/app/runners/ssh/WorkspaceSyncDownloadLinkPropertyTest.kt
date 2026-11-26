package homer.app.runners.ssh

import homer.app.infra.PathSafety
import homer.app.runners.DownloadMode
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.apache.sshd.client.SshClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * [WorkspaceSync.downloadDir] over generated dangling links planted in the destination (ADR-0047, CWE-59/22): a
 * download either refuses the entry and writes nothing where the link leads, or lands it inside the destination.
 * The example cases live in `WorkspaceSyncTest`.
 */
class WorkspaceSyncDownloadLinkPropertyTest {
    @TempDir
    lateinit var base: Path

    @TempDir
    lateinit var outside: Path

    private lateinit var server: EmbeddedSshServer
    private lateinit var client: SshClient
    private lateinit var sync: WorkspaceSync

    @BeforeEach
    fun setup() {
        server = EmbeddedSshServer().also { it.start() }
        client = SshClient.setUpDefaultClient().also { it.start() }
        sync = WorkspaceSync(server.newClientSession(client), 60.seconds)
    }

    @AfterEach
    fun teardown() {
        client.stop()
        server.close()
    }

    /** Targets that stay dangling: a never-existing leaf, reached relatively or through an absolute path. */
    private val danglingTargets: Arb<String> =
        Arb.bind(
            Arb.list(Arb.element("..", ".", "sub", "missing"), 0..4),
            Arb.element("", "/"),
        ) { parts, absolute ->
            val rel = (parts + "missing-leaf").joinToString("/")
            if (absolute == "/") outside.resolve(NESTING).resolve(rel).toString() else rel
        }

    @Test
    fun `a download through a dangling link never writes outside the destination`() =
        runBlocking<Unit> {
            val remoteRoot = server.rootDir.resolve("art").createDirectories()
            remoteRoot.resolve("entry").writeText("remote-bytes")
            remoteRoot.resolve("sub").createDirectories()
            outside.resolve(NESTING).createDirectories()
            var case = 0
            checkAll(60, danglingTargets) { target ->
                val local = base.resolve(NESTING).resolve("case-${case++}").also { it.createDirectories() }
                val link = Files.createSymbolicLink(local.resolve("entry"), Path.of(target))
                val landing = PathSafety.resolveDanglingLinkTarget(link)

                val result = runCatching { sync.downloadDir(remoteRoot.toString(), local, mode = DownloadMode.OVERWRITE) }

                if (result.isSuccess) {
                    assertTrue(landing.startsWith(local.toRealPath()), "link '$target' was written through to '$landing'")
                } else {
                    assertTrue(Files.notExists(landing, LinkOption.NOFOLLOW_LINKS), "refused link '$target' still wrote '$landing'")
                }
                val written = Files.walk(outside).use { paths -> paths.filter { Files.isRegularFile(it) }.toList() }
                assertTrue(written.isEmpty(), "link '$target' wrote $written under the outside directory")
            }
        }

    private companion object {
        /** As deep as a target has `..` segments, so no generated target reaches past this test's temp directories. */
        private const val NESTING = "w/x/y/z"
    }
}
