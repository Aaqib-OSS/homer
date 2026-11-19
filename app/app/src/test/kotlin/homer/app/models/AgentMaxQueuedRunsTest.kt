package homer.app.models

import com.charleskorn.kaml.Yaml
import homer.app.fixtures.AgentFixtures
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AgentMaxQueuedRunsTest {
    private fun agentWith(maxParallelRuns: Int) =
        Agent(name = "p", commands = AgentFixtures.simpleAgent().commands, maxParallelRuns = maxParallelRuns)

    private fun parse(extra: String): Agent =
        Yaml.default.decodeFromString(
            Agent.serializer(),
            "name: demo\ncommands:\n  - !<script>\n    content: echo hi\n$extra",
        )

    @Test
    fun `maxQueuedRuns defaults to 10 times the default maxParallelRuns`() {
        assertEquals(100, parse("").maxQueuedRuns)
    }

    @Test
    fun `maxQueuedRuns default follows a declared maxParallelRuns`() {
        assertEquals(30, parse("maxParallelRuns: 3\n").maxQueuedRuns)
    }

    @Test
    fun `an explicit maxQueuedRuns wins over the default`() {
        assertEquals(2, parse("maxParallelRuns: 3\nmaxQueuedRuns: 2\n").maxQueuedRuns)
    }

    @Test
    fun `a huge maxParallelRuns saturates the default instead of overflowing`() {
        assertEquals(Int.MAX_VALUE, parse("maxParallelRuns: ${Int.MAX_VALUE}\n").maxQueuedRuns)
        assertEquals(Int.MAX_VALUE, agentWith(300_000_000).maxQueuedRuns)
    }

    @Test
    fun `null maxQueuedRuns is rejected`() {
        assertThrows<SerializationException> { parse("maxQueuedRuns: null\n") }
    }

    @Test
    fun `a maxParallelRuns below one is rejected before the queue default is derived`() {
        listOf(0, -1).forEach { n ->
            val ex = assertThrows<IllegalArgumentException> { parse("maxParallelRuns: $n\n") }
            assertEquals("Agent 'demo': maxParallelRuns must be >= 1, got $n", ex.message)
        }
    }

    @Test
    fun `a negative maxQueuedRuns is rejected`() {
        val ex = assertThrows<IllegalArgumentException> { parse("maxQueuedRuns: -1\n") }
        assertEquals("Agent 'demo': maxQueuedRuns must be >= 0, got -1", ex.message)
    }

    @Test
    fun `for any maxParallelRuns the default is the saturated product with ten, and below one it is refused`() =
        runTest {
            checkAll(Arb.int()) { n ->
                if (n >= 1) {
                    val expected = minOf(n.toLong() * 10, Int.MAX_VALUE.toLong()).toInt()
                    assertEquals(expected, agentWith(n).maxQueuedRuns)
                } else {
                    assertThrows<IllegalArgumentException> { agentWith(n) }
                }
            }
        }
}
