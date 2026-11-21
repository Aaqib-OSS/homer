package homer.app.infra

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AgentLoadErrorsTest {
    @Test
    fun `a scan within the caps keeps every message and drops none`() {
        val errors = listOf("Agent 'a' is broken", "Agent 'b' is broken")

        val bounded = AgentLoadErrors.of(errors)

        assertEquals(errors, bounded.errors)
        assertEquals(0, bounded.truncated)
    }

    @Test
    fun `no errors is the empty result`() {
        assertEquals(AgentLoadErrors.EMPTY, AgentLoadErrors.of(emptyList()))
    }

    @Test
    fun `a scan past the kept cap reports the dropped messages in truncated`() {
        val errors = (1..501).map { "Agent '$it' is broken" }

        val bounded = AgentLoadErrors.of(errors)

        assertEquals(AgentLoadErrors.MAX_KEPT, bounded.errors.size)
        assertEquals(errors.take(AgentLoadErrors.MAX_KEPT), bounded.errors)
        assertEquals(1, bounded.truncated)

        assertEquals(700, AgentLoadErrors.of((1..1200).map { "e$it" }).truncated)
    }

    @Test
    fun `a message is cut at the character cap`() {
        val long = "x".repeat(AgentLoadErrors.MAX_MESSAGE_CHARS + 500)

        val kept = AgentLoadErrors.of(listOf(long)).errors.single()

        assertEquals(AgentLoadErrors.MAX_MESSAGE_CHARS, kept.length)
    }

    @Test
    fun `a message carrying control characters is kept on one line`() {
        val kept = AgentLoadErrors.of(listOf("line one\nline two\ttabbed\u0000")).errors.single()

        assertEquals("line oneline twotabbed", kept)
    }
}
