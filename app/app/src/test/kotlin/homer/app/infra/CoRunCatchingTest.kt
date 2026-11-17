package homer.app.infra

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CoRunCatchingTest {
    @Test
    fun `a value comes back as a success`() {
        assertEquals(42, coRunCatching { 42 }.getOrThrow())
    }

    @Test
    fun `an Exception comes back as a failure holding it`() {
        val boom = IllegalStateException("boom")

        val result = coRunCatching { throw boom }

        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun `a CancellationException is rethrown`() {
        val cancelled = CancellationException("stop")

        val thrown = assertFailsWith<CancellationException> { coRunCatching { throw cancelled } }

        assertSame(cancelled, thrown)
    }

    @Test
    fun `a timeout from withTimeout is rethrown`() =
        runTest {
            assertFailsWith<TimeoutCancellationException> {
                coRunCatching { withTimeout(10) { delay(1_000) } }
            }
        }

    @Test
    fun `an Error propagates`() {
        assertFailsWith<OutOfMemoryError> { coRunCatching { throw OutOfMemoryError("oom") } }
    }

    @Test
    fun `a suspend call inside the block works`() =
        runTest {
            val result = coRunCatching { delay(1).let { "done" } }

            assertEquals("done", result.getOrThrow())
        }

    @Test
    fun `a cancelled coroutine ends cancelled rather than as a failure result`() =
        runTest {
            var captured: Result<Unit>? = null
            val job =
                async(start = CoroutineStart.UNDISPATCHED) {
                    captured = coRunCatching { delay(10_000) }
                }

            job.cancel()

            assertFailsWith<CancellationException> { job.await() }
            assertTrue(captured == null, "the cancellation must not be captured as a Result")
        }

    @Test
    fun `whatever an exception says, the failure holds that exact exception`() =
        runTest {
            checkAll(Arb.string(0..32)) { message ->
                val boom = IllegalArgumentException(message)

                assertSame(boom, coRunCatching { throw boom }.exceptionOrNull())
            }
        }
}
