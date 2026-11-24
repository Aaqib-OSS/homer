package homer.runner

import homer.app.infra.Dumpability
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The real `prctl` call; this module forks a JVM per test class, so it leaves no other class non-dumpable. */
class RealDumpabilityTest {
    @Test
    fun `on Linux the real call leaves the JVM non-dumpable`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        assertEquals(Dumpability.Result.Hardened, Dumpability.harden())
        assertEquals(0, Dumpability.dumpableState(), "the JVM must be non-dumpable after hardening")
    }

    @Test
    fun `a same-uid reader is denied this JVM's environ once hardened (CWE-526)`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        // Root reads a non-dumpable process's environ whatever its uid, so this proves nothing there.
        assumeTrue(Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int != 0)
        // A dumpable same-uid process is readable, so the denial below is the hardening, not the reader.
        val dumpable = ProcessBuilder("sh", "-c", "sleep 30").start()
        try {
            assertNotEquals("", environReadBy(dumpable.pid()), "the reader must reach a dumpable process's environ")
        } finally {
            dumpable.destroy()
        }

        assertEquals(Dumpability.Result.Hardened, Dumpability.harden())
        assertEquals("", environReadBy(ProcessHandle.current().pid()), "a same-uid read must be refused once hardened")
    }

    /** What a same-uid `cat` child reads from `/proc/<pid>/environ`, empty when the kernel refuses it. */
    private fun environReadBy(pid: Long): String {
        val child = ProcessBuilder("cat", "/proc/$pid/environ").redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val read = child.inputStream.readAllBytes().decodeToString()
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the reader must finish")
        return if (child.exitValue() == 0) read else ""
    }

    @Test
    fun `the real main entry hardens the JVM before it reaches the server (CWE-526)`(
        @TempDir root: Path,
    ) {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        // Root reads a non-dumpable process's environ whatever its uid, so this proves nothing there.
        assumeTrue(Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int != 0)
        // A listener that accepts the registration connection and never answers holds the forked
        // runner there — past the hardening and the token read — while this test inspects it.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { blocker ->
            val handle = ProcessHandle.current()
            val java = handle.info().command().orElseThrow()
            val command =
                listOf(
                    java,
                    "-cp",
                    System.getProperty("java.class.path"),
                    "homer.runner.MainKt",
                    "--url",
                    "http://127.0.0.1:${blocker.localPort}",
                    "--root",
                    root.toString(),
                )
            val launch = ProcessBuilder(command)
            launch.environment()["HOMER_RUNNER_TOKEN"] = "t".repeat(32)
            launch.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            launch.redirectError(ProcessBuilder.Redirect.DISCARD)
            val child = launch.start()
            var denied = false
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
                while (child.isAlive && System.nanoTime() < deadline) {
                    // The denial is the hardening: only a non-dumpable process refuses a same-uid
                    // environ read, so the forked `main` must have hardened before it registered.
                    if (environReadBy(child.pid()).isEmpty()) {
                        denied = true
                        break
                    }
                    Thread.sleep(200)
                }
            } finally {
                child.destroy()
            }
            assertTrue(denied, "the forked runner's environ must be denied before it registers")
        }
    }
}
