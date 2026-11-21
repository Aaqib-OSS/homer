package homer.runner

import homer.app.infra.Dumpability
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The real `prctl` call; this module forks a JVM per test class, so it leaves no other class non-dumpable. */
class RealDumpabilityTest {
    @Test
    fun `on Linux the real call leaves the JVM non-dumpable`() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        assertEquals(Dumpability.Result.Hardened, Dumpability.harden())
        assertEquals(0, Dumpability.dumpableState(), "the JVM must be non-dumpable after hardening")
    }
}
