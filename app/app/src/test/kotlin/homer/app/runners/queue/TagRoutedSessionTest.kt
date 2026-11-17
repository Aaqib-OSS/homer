package homer.app.runners.queue

import homer.app.models.RunnerFilter
import homer.app.runners.RunnerSession
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class TagRoutedSessionTest {
    private fun session(env: Map<String, String> = emptyMap()) = mockk<RunnerSession>(relaxed = true).also { every { it.env } returns env }

    @Test
    fun `a tag claim layers the runner's env under the session's own`() {
        val delegate = session(mapOf("HMR_API_URL" to "u"))

        val delivered = delegate.deliveredFor(RunnerFilter.Tags(setOf("linux")), mapOf("TOKEN" to "t"))

        assertEquals(mapOf("TOKEN" to "t", "HMR_API_URL" to "u"), delivered.env)
    }

    // The alias-filtered run's runner was known when its workspace env was composed, so the runner
    // env is already in it; layering it again here would put it above the agent's own env.
    @Test
    fun `an alias claim is delivered unwrapped`() {
        val delegate = session(mapOf("HMR_API_URL" to "u"))

        assertSame(delegate, delegate.deliveredFor(RunnerFilter.Alias("local"), mapOf("TOKEN" to "t")))
    }

    @Test
    fun `a tag claim with no runner env is delivered unwrapped`() {
        val delegate = session()

        assertSame(delegate, delegate.deliveredFor(RunnerFilter.Tags(setOf("linux")), emptyMap()))
    }

    // The wrapper must not become a data class: its generated toString would print the runner env,
    // and a runner env value can be a credential (root CLAUDE.md §12 rule 7).
    @Test
    fun `the delivered session's toString carries no runner env value`() {
        val delivered = session().deliveredFor(RunnerFilter.Tags(setOf("linux")), mapOf("TOKEN" to "s3cr3t"))

        assertFalse(delivered.toString().contains("s3cr3t"))
    }
}
