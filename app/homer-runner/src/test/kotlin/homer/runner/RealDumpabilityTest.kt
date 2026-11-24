package homer.runner

import homer.app.infra.Dumpability
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
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
}
