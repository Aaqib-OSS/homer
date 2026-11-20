package homer.app.executors.llm

import homer.app.infra.SecretRedactor
import homer.app.infra.TranslatedStream
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer

/**
 * What [LlmHarnessExecutor] hands off once an attempt's stream is read: the step's spans, exported to
 * Langfuse. Implemented by `LlmTelemetryService`. See ADR-0070 and ADR-0083.
 */
interface LlmStepTelemetry {
    /** Builds the step's own spans — e.g. the `llm_cost_settlement` child span of a background settlement (ADR-0089). */
    val tracer: Tracer

    /**
     * Emits [stream]'s spans under [parentSpan], [redactor] masking declared `secret` input values before they egress.
     * [step] names the generation; [resolvedCostUsd], the cost actually charged, is split across the attempt's turn
     * ends, and null keeps the events' own cost (ADR-0061, ADR-0074).
     */
    suspend fun process(
        stream: TranslatedStream,
        prompt: String,
        parentSpan: Span,
        redactor: SecretRedactor = SecretRedactor.NONE,
        step: LlmStepInfo = LlmStepInfo(),
        resolvedCostUsd: Double? = null,
    )
}

/**
 * What the step declared, not what the harness reported: [label] names the generation, telling an agent's LLM steps
 * apart; [configuredModel] is the last rung of the telemetry's model ladder. Both are absent for a caller with no step.
 */
data class LlmStepInfo(
    val label: String? = null,
    val configuredModel: String? = null,
)
