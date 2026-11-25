package homer.app.executors.llm

import homer.app.fixtures.TestFixtures
import homer.app.infra.LlmAccountingScan
import homer.app.infra.LlmCostResolver
import homer.app.models.CostSource
import homer.app.models.FALLBACK_REASON_NO_RESULT_EVENT
import homer.app.models.FALLBACK_REASON_SESSION_FIGURE_PENDING
import homer.app.models.LlmAccounting
import homer.app.models.ResolvedCost
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.bufferedWriter
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LlmAttemptPricingTest {
    private val logger = LoggerFactory.getLogger(LlmAttemptPricingTest::class.java)

    private fun resolver(configured: Boolean = false): LlmCostResolver =
        mockk {
            coEvery { resolve(any(), any(), any(), any(), any()) } answers { ResolvedCost(secondArg(), CostSource.CLI) }
            every { this@mockk.configured } returns configured
        }

    @Test
    fun `the harness cost is priced through the resolver within the attempt window`(
        @TempDir dir: Path,
    ) = runTest {
        val out = dir.resolve("out").also { it.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.25}\n") }
        val resolver = resolver()
        val span = TestFixtures.mockSpan()

        val priced = LlmAttemptPricing(FakeHarness(), resolver, logger).price(out, span, true, 10L, 20L)

        assertEquals(0.25, priced.costUsd)
        assertEquals(CostSource.CLI, priced.source)
        assertNull(priced.fallbackReason)
        coVerify { resolver.resolve("s", 0.25, any(), 10L, 20L) }
        verify(exactly = 0) { span.setAttribute("llm.cost.scan_truncated", any<Boolean>()) }
    }

    @Test
    fun `two attempts resuming one opencode session through the same adapter are each charged their own steps`(
        @TempDir dir: Path,
    ) {
        fun step(
            id: String,
            cost: Double,
        ) = """{"type":"step_finish","sessionID":"ses_1","part":{"id":"$id","reason":"stop","cost":$cost}}""" + "\n"
        val first = dir.resolve("first").also { it.writeText(step("p1", 0.25) + step("p2", 0.5)) }
        val resumed = dir.resolve("resumed").also { it.writeText(step("p3", 0.125)) }
        val pricing = LlmAttemptPricing(OpencodeHarness(), resolver(), logger)

        assertEquals(0.75, pricing.scan(first, TestFixtures.mockSpan()).cliCostUsd)
        assertEquals(0.125, pricing.scan(resumed, TestFixtures.mockSpan()).cliCostUsd)
        assertEquals(0.75, pricing.scan(first, TestFixtures.mockSpan()).cliCostUsd)
    }

    @Test
    fun `a cancelled attempt skips the lookup and an attempt without a turn end says so`(
        @TempDir dir: Path,
    ) = runTest {
        val out = dir.resolve("out").also { it.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n") }
        val resolver = resolver(configured = true)

        val priced = LlmAttemptPricing(FakeHarness(), resolver, logger).price(out, TestFixtures.mockSpan(), false, null, null)

        assertEquals("run cancelled before lookup", priced.fallbackReason)
        coVerify(exactly = 0) { resolver.resolve(any(), any(), any(), any(), any()) }

        val reasonless = LlmAttemptPricing(FakeHarness(), resolver(), logger).price(out, TestFixtures.mockSpan(), true, null, null)
        assertEquals(FALLBACK_REASON_NO_RESULT_EVENT, reasonless.fallbackReason)
    }

    @Test
    fun `a harness without its own cost figure is unpriced, and telemetry gets no cost for it`(
        @TempDir dir: Path,
    ) = runTest {
        val out = dir.resolve("out").also { it.writeText("{\"t\":\"end\",\"ok\":true}\n") }

        val priced =
            LlmAttemptPricing(
                FakeHarness(reportsCost = false),
                resolver(),
                logger,
            ).price(out, TestFixtures.mockSpan(), true, null, null)

        assertEquals(CostSource.NONE, priced.source)
        assertNull(priced.fallbackReason)
        assertNull(telemetryCostUsd(LlmAccounting(null, 0.0, CostSource.NONE)))
        assertEquals(0.5, telemetryCostUsd(LlmAccounting(null, 0.5, CostSource.CLI)))
    }

    @Test
    fun `a turn end on a stdout line over the line bound is skipped unread and prices nothing (CWE-400)`(
        @TempDir dir: Path,
    ) {
        val padding = "x".repeat(4 * 1024 * 1024)
        val out = dir.resolve("out").also { it.writeText("{\"t\":\"end\",\"ok\":true,\"cost\":0.25,\"pad\":\"$padding\"}\n") }

        val span = TestFixtures.mockSpan()

        val scan = LlmAttemptPricing(FakeHarness(), resolver(), logger).scan(out, span)

        assertEquals(LlmAccountingScan(sessionId = null, cliCostUsd = null, resultSeen = false, modelCalls = 0, usage = null), scan)
        verify { span.setAttribute("llm.cost.scan_truncated", true) }
    }

    @Test
    fun `a stdout stream past the byte bound is priced from its head only (CWE-770)`(
        @TempDir dir: Path,
    ) {
        val out = dir.resolve("out")
        out.bufferedWriter().use { w ->
            w.write("{\"t\":\"start\",\"sid\":\"s\"}\n")
            val filler = " ".repeat(1023) + "\n"
            repeat(32 * 1024) { w.write(filler) }
            w.write("{\"t\":\"end\",\"ok\":true,\"cost\":0.25}\n")
        }

        val span = TestFixtures.mockSpan()

        val scan = LlmAttemptPricing(FakeHarness(), resolver(), logger).scan(out, span)

        assertEquals("s", scan.sessionId)
        assertNull(scan.cliCostUsd)
        assertFalse(scan.resultSeen)
        verify { span.setAttribute("llm.cost.scan_truncated", true) }
    }

    @Test
    fun `a preview carries the harness figure and says the session figure is pending`(
        @TempDir dir: Path,
    ) = runTest {
        val out = dir.resolve("out").also { it.writeText("{\"t\":\"start\",\"sid\":\"s\"}\n{\"t\":\"end\",\"ok\":true,\"cost\":0.25}\n") }
        val pricing = LlmAttemptPricing(FakeHarness(), resolver(configured = true), logger)

        val preview = pricing.preview(pricing.scan(out, TestFixtures.mockSpan()))

        assertEquals(0.25, preview.costUsd)
        assertEquals(CostSource.CLI, preview.source)
        assertEquals(FALLBACK_REASON_SESSION_FIGURE_PENDING, preview.fallbackReason)
        val unpriced = LlmAttemptPricing(FakeHarness(reportsCost = false), resolver(configured = true), logger)
        assertEquals(CostSource.NONE, unpriced.preview(unpriced.scan(out, TestFixtures.mockSpan())).source)
    }
}
