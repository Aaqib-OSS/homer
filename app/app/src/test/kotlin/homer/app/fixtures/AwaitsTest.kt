package homer.app.fixtures

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AwaitsTest {
    @Test
    fun `eventually returns as soon as the block passes`() =
        runTest {
            var calls = 0

            eventually(timeout = 5.seconds, poll = 10.milliseconds) { calls++ }

            assertEquals(1, calls, "a block that passes must not be retried")
        }

    @Test
    fun `eventually retries until a later pass`() =
        runTest {
            var calls = 0

            eventually(timeout = 5.seconds, poll = 10.milliseconds) {
                calls++
                if (calls < 3) throw AssertionError("not yet")
            }

            assertEquals(3, calls)
        }

    @Test
    fun `eventually rethrows the last failure once the timeout elapses`() =
        runTest {
            var calls = 0

            val failure =
                assertFailsWith<AssertionError> {
                    eventually(timeout = 100.milliseconds, poll = 10.milliseconds) {
                        calls++
                        throw AssertionError("the condition never held")
                    }
                }

            assertEquals("the condition never held", failure.message)
            assertTrue(calls > 1, "the block is retried until the timeout, not judged on one attempt")
        }

    @Test
    fun `eventually lets a cancelled block's cancellation through`() =
        runTest {
            val failure =
                assertFailsWith<CancellationException> {
                    eventually(timeout = 5.seconds, poll = 10.milliseconds) {
                        throw CancellationException("the caller was cancelled")
                    }
                }

            assertEquals("the caller was cancelled", failure.message)
        }

    @Test
    fun `pollUntil returns as soon as the probe holds`() {
        var calls = 0

        val held =
            pollUntil(timeoutMs = 5_000, pollMs = 10) {
                calls++
                calls >= 3
            }

        assertTrue(held)
        assertEquals(3, calls)
    }

    @Test
    fun `pollUntil gives up at the timeout and polls no longer`() {
        val started = System.currentTimeMillis()

        val held = pollUntil(timeoutMs = 200, pollMs = 20) { false }

        val elapsed = System.currentTimeMillis() - started
        assertFalse(held, "a probe that never holds must end the wait")
        assertTrue(elapsed >= 200, "the wait covers the whole timeout; it lasted ${elapsed}ms")
        assertTrue(elapsed < 2_000, "the wait is bounded by the timeout; it lasted ${elapsed}ms")
    }

    @Test
    fun `awaitProcessExit reports an exited child as gone`() =
        runTest {
            val child = ProcessBuilder("sh", "-c", "exit 0").start()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child should exit on its own")

            assertTrue(awaitProcessExit(child.pid()), "the reaped child ${child.pid()} is gone")
        }

    @Test
    fun `awaitProcessExit reports a pid that never existed as gone`() =
        runTest {
            val absent = Int.MAX_VALUE.toLong()

            assertTrue(awaitProcessExit(absent), "an absent /proc entry means the process is gone")
        }

    @Test
    fun `awaitProcessExit does not report a live process as gone`() =
        runTest {
            val child = ProcessBuilder("sleep", "30").start()
            try {
                assertFalse(
                    awaitProcessExit(child.pid(), timeoutMs = 200),
                    "the live child ${child.pid()} is not gone",
                )
            } finally {
                child.destroyForcibly()
            }
        }
}
