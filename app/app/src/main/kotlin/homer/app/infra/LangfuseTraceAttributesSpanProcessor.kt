package homer.app.infra

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * Copies [LANGFUSE_TRACE_NAME] and [LANGFUSE_TRACE_TAGS] from a span's parent onto the span as it
 * starts, and nothing else; the parent's values win and it never throws. See ADR-0050.
 */
internal class LangfuseTraceAttributesSpanProcessor : SpanProcessor {
    override fun isStartRequired(): Boolean = true

    override fun isEndRequired(): Boolean = false

    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        try {
            val parent = Span.fromContext(parentContext) as? ReadableSpan ?: return
            parent.getAttribute(LANGFUSE_TRACE_NAME)?.let { span.setAttribute(LANGFUSE_TRACE_NAME, it) }
            parent.getAttribute(LANGFUSE_TRACE_TAGS)?.let { span.setAttribute(LANGFUSE_TRACE_TAGS, it) }
        } catch (_: Exception) {
            // Swallowed on purpose: this runs inside the SDK's span-start path, so a throw here
            // would surface in whichever caller happened to start a span — a run, a request.
        }
    }

    override fun onEnd(span: ReadableSpan) = Unit

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
