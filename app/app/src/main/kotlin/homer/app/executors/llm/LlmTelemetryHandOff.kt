package homer.app.executors.llm

import homer.app.SpanAttributes
import homer.app.infra.SecretRedactor
import homer.app.infra.TranslatedStream
import homer.app.infra.coRunCatching
import homer.app.models.Command
import homer.app.models.LlmAccounting
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import java.nio.file.Path

/** The LLM harness executor's telemetry hand-off: an attempt's spans, inline or after its cost settlement. See ADR-0089. */
internal class LlmTelemetryHandOff<C>(
    private val telemetry: LlmStepTelemetry,
    private val accounting: LlmAttemptAccounting,
    private val output: LlmAttemptOutput<C>,
    private val settlementScope: CoroutineScope,
    private val logger: Logger,
) where C : Command, C : Command.LlmCommand {
    private val settlementHandOffs = Semaphore(MAX_CONCURRENT_SETTLEMENT_HANDOFFS)

    /**
     * Telemetry for an attempt whose shell threw or whose accounting was cancelled: `execute`'s
     * hand-off never runs on those paths, so the step would lose its spans. The read runs inside
     * the timeout, so a stdout flood cannot hold a cancelled run's teardown open (CWE-400).
     */
    @Suppress("SuspendFunSwallowedCancellation") // catches its own timeout; runs under NonCancellable
    suspend fun emitAbortTelemetry(
        attemptOut: Path,
        accounting: LlmAccounting,
        prompt: String,
        command: C,
        span: Span,
        redactor: SecretRedactor,
    ) {
        runCatching {
            withTimeout(ABORT_TELEMETRY_TIMEOUT_MS) {
                telemetry.process(
                    output.readLeniently(attemptOut, span),
                    prompt,
                    span,
                    redactor,
                    LlmStepInfo(label = command.label, configuredModel = command.model),
                    resolvedCostUsd = telemetryCostUsd(accounting),
                )
            }
        }.onFailure {
            logger.warn("telemetry hand-off failed after the attempt aborted ({}); the original exception is rethrown", it.message)
        }
    }

    /**
     * Hands [outcome]'s stream to telemetry, a failure recorded on [span] but a cancellation thrown. The stream is read
     * inline, as attempts may share and truncate the stdout file; a [settlement] is awaited in the background (ADR-0089).
     */
    suspend fun report(
        outcome: RetryOutcome.Returned,
        settlement: Deferred<LlmAccounting>?,
        command: C,
        compiledPrompt: String,
        span: Span,
        redactor: SecretRedactor,
    ) {
        // A kill during the hand-off must stop the step, not let it finish as a success.
        coRunCatching {
            // A success carries its strict read. A failure's is lenient, so a killed CLI's partial last line costs only
            // that line, and reuses the terminal-stop read when there is one, so no stdout is parsed twice.
            val stream = outcome.stream ?: output.readLeniently(outcome.result.stdOut, span)
            val step = LlmStepInfo(label = command.label, configuredModel = command.model)
            handOff(settlement, outcome.result.llmAccounting, stream, compiledPrompt, span, redactor, step)
        }.onFailure { e ->
            span.setAttribute(SpanAttributes.TELEMETRY_ERROR, e.message ?: "Unknown error")
            span.recordException(e)
        }
    }

    @Suppress("SuspendFunSwallowedCancellation") // a cancelled settlement still gets its trace, on the harness figure
    private suspend fun handOff(
        settlement: Deferred<LlmAccounting>?,
        pending: LlmAccounting?,
        stream: TranslatedStream,
        compiledPrompt: String,
        span: Span,
        redactor: SecretRedactor,
        step: LlmStepInfo,
    ) {
        if (settlement == null || !settlementHandOffs.tryAcquire()) { // CWE-770/CWE-400: shed, never queue a job (ADR-0047 rules 4-5).
            if (settlement != null) {
                logger.warn("settlement hand-off shed at the concurrency bound ({})", MAX_CONCURRENT_SETTLEMENT_HANDOFFS)
                span.setAttribute(SpanAttributes.TELEMETRY_LLM_HANDOFF_SHED, true)
            }
            telemetry.process(stream, compiledPrompt, span, redactor, step, resolvedCostUsd = telemetryCostUsd(pending))
            return
        }
        settlementScope
            .launch(start = CoroutineStart.LAZY) {
                val waited = runCatching { withTimeoutOrNull(SETTLEMENT_HANDOFF_TIMEOUT_MS) { settlement.await() } }
                val settled = waited.getOrNull() ?: pending
                val settlementSpanBuilder = telemetry.tracer.spanBuilder("llm_cost_settlement").setParent(Context.current().with(span))
                val settlementSpan = settlementSpanBuilder.startSpan()
                // A wait that timed out, not a settlement that failed: only the former is a shed.
                if (waited.isSuccess && waited.getOrNull() == null) {
                    settlementSpan.setAttribute(SpanAttributes.TELEMETRY_LLM_HANDOFF_SHED, true)
                }
                settled?.let { accounting.annotateSettled(settlementSpan, it) }
                runCatching { telemetry.process(stream, compiledPrompt, span, redactor, step, resolvedCostUsd = telemetryCostUsd(settled)) }
                    .onFailure {
                        logger.warn("telemetry hand-off failed after the cost settled ({})", it.message)
                        settlementSpan.setAttribute(SpanAttributes.TELEMETRY_ERROR, it.message ?: "Unknown error")
                        settlementSpan.recordException(it)
                    }
                settlementSpan.end()
            }.also { it.invokeOnCompletion { settlementHandOffs.release() } } // released on completion, never in a `finally`
            .start()
    }

    private companion object {
        // Bounds the abort-path hand-off, whose per-span ordering delay must not hold a genuine
        // cancellation open. Comfortably above a normal emit (a few spans × 50 ms).
        const val ABORT_TELEMETRY_TIMEOUT_MS = 5_000L

        const val MAX_CONCURRENT_SETTLEMENT_HANDOFFS = 8
        const val SETTLEMENT_HANDOFF_TIMEOUT_MS = 45_000L
    }
}
