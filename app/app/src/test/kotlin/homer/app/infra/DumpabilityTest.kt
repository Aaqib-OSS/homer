package homer.app.infra

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real `prctl` call runs in `:homer-runner`'s `RealDumpabilityTest`, which forks a JVM per class. */
class DumpabilityTest {
    @Test
    fun `a failed prctl is reported, not swallowed`() {
        val result = Dumpability.harden(osName = "Linux", setNonDumpable = { -1 }, readDumpable = { 1 })
        assertEquals(Dumpability.Result.Failed("prctl(PR_SET_DUMPABLE, 0) returned -1"), result)
    }

    @Test
    fun `a prctl that throws surfaces its reason`() {
        val result =
            Dumpability.harden(
                osName = "Linux",
                setNonDumpable = { throw IllegalStateException("prctl is not available on this platform") },
            )
        assertTrue(result is Dumpability.Result.Failed, "got: $result")
        assertTrue("prctl is not available" in result.reason)
    }

    @Test
    fun `a call that does not take effect is a failure`() {
        val result = Dumpability.harden(osName = "Linux", setNonDumpable = { 0 }, readDumpable = { 1 })
        assertEquals(Dumpability.Result.Failed("prctl(PR_SET_DUMPABLE, 0) did not take effect (PR_GET_DUMPABLE=1)"), result)
    }

    @Test
    fun `a throwing readDumpable is a failure, not a crash`() {
        val result =
            Dumpability.harden(
                osName = "Linux",
                setNonDumpable = { 0 },
                readDumpable = { throw IllegalStateException("boom") },
            )
        assertTrue(result is Dumpability.Result.Failed, "got: $result")
    }

    @Test
    fun `a non-Linux host stays skipped even when the injected prctl would fail`() {
        assertTrue(Dumpability.harden(osName = "Mac OS X", setNonDumpable = { -1 }) is Dumpability.Result.Skipped)
    }

    @Test
    fun `a non-Linux host is skipped without calling prctl`() {
        var called = false
        val result =
            Dumpability.harden(
                osName = "Mac OS X",
                setNonDumpable = {
                    called = true
                    0
                },
                readDumpable = {
                    called = true
                    0
                },
            )
        assertTrue(result is Dumpability.Result.Skipped, "got: $result")
        assertFalse(called, "prctl must not be called on a non-Linux host")
    }

    @Test
    fun `reading the flag leaves the JVM as it was, and needs prctl`() {
        if (System.getProperty("os.name").startsWith("Linux")) {
            assertEquals(1, Dumpability.dumpableState(), "this test JVM is never hardened")
        } else {
            assertFailsWith<IllegalStateException> { Dumpability.dumpableState() }
        }
    }
}
