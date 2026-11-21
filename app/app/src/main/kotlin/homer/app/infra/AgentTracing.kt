package homer.app.infra

import homer.app.models.Agent
import homer.app.models.TagValidation
import homer.app.models.TelemetryExport
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceId
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.Tracer
import org.slf4j.LoggerFactory
import kotlin.random.Random

// Root-span construction and trace tagging shared by every run-start path. See ADR-0050.
private val logger = LoggerFactory.getLogger("homer.app.infra.AgentTracing")

// Starts this run's root span, honouring `telemetry.export`. A muted run gets a valid but
// unsampled context so the SDK's ParentBased sampler drops the whole subtree. See ADR-0050.
internal fun Tracer.startAgentRootSpan(
    agent: Agent,
    spanName: String,
    random: Random = Random.Default,
): Span =
    if (agent.telemetry.export == TelemetryExport.OFF) {
        Span.wrap(
            SpanContext.create(
                // `or 1` keeps the low word non-zero: an all-zero id is the invalid id.
                TraceId.fromLongs(random.nextLong(), random.nextLong() or 1L),
                SpanId.fromLong(random.nextLong() or 1L),
                TraceFlags.getDefault(),
                TraceState.getDefault(),
            ),
        )
    } else {
        spanBuilder(spanName).startSpan()
    }

// Langfuse v4 keeps no trace record and reads a trace's name off each observation, so this must be
// on every span, not only the root. See ADR-0050.
internal val LANGFUSE_TRACE_NAME: AttributeKey<String> = AttributeKey.stringKey("langfuse.trace.name")

// The run's subject tags as Langfuse sees them — on every observation for a v4 filter to find the
// run. See ADR-0050.
internal val LANGFUSE_TRACE_TAGS: AttributeKey<List<String>> = AttributeKey.stringArrayKey("langfuse.trace.tags")

// Puts the trace name and tags on [this] span; called on the root span before
// `processExecutor.execute`, so children inherit both through [LangfuseTraceAttributesSpanProcessor].
internal fun Span.stampLangfuseTrace(
    agent: Agent,
    tags: List<String>,
) {
    val capped = tags.take(TagValidation.MAX_TRACE_TAGS)
    if (capped.size < tags.size && agent.telemetry.export != TelemetryExport.OFF) {
        logger.warn(
            "Agent '{}': {} trace tags over the {}-tag per-span limit; exporting the first {}",
            agent.name,
            tags.size,
            TagValidation.MAX_TRACE_TAGS,
            TagValidation.MAX_TRACE_TAGS,
        )
    }
    setAttribute(LANGFUSE_TRACE_NAME, agent.name)
    setAttribute(LANGFUSE_TRACE_TAGS, capped)
}

/**
 * The `paramName:value` tags of the run's `fromInputs`, for Langfuse and the process tags alike.
 * [secretEnvNames] has no default: replays pass the source run's snapshot (ADR-0052).
 */
internal fun langfuseTraceTags(
    agent: Agent,
    resolvedInputs: Map<String, String>,
    secretEnvNames: Set<String>,
): List<String> {
    val fromInputs = agent.effectiveFromInputTags
    if (fromInputs.isEmpty()) return emptyList()
    val byParamName = agent.effectiveInputs.orEmpty().associateBy { it.paramName }
    return fromInputs.mapNotNull { paramName ->
        val param = byParamName[paramName] ?: return@mapNotNull null
        if (param.envName in secretEnvNames) return@mapNotNull null
        val value = resolvedInputs[param.envName]?.trim()
        when {
            // An unset/empty input just means "no tag for this run", not a defect.
            value.isNullOrEmpty() -> {
                null
            }

            // Out-of-charset or oversized values are dropped, not rewritten: a mangled value
            // hits no exact-match filter and misrepresents the run.
            !TagValidation.VALUE_PATTERN.matches(value) -> {
                val reason =
                    if (value.length > TagValidation.MAX_TAG_VALUE_LENGTH) {
                        "is ${value.length} chars, over the ${TagValidation.MAX_TAG_VALUE_LENGTH}-char tag-value limit"
                    } else {
                        "has characters outside [A-Za-z0-9._-]"
                    }
                logger.warn("Agent '{}': input '{}' {}; not tagging the run", agent.name, paramName, reason)
                null
            }

            else -> {
                "$paramName:$value"
            }
        }
    }
}
