package homer.app.infra

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * Copies [LANGFUSE_TRACE_NAME] and [LANGFUSE_TRACE_TAGS] from a span's parent onto the span as it
 * starts, so every observation of a run carries the run's trace name and tags (Langfuse v4 has no
 * separate trace record). Registered **before** the `BatchSpanProcessor` and the only processor
 * needing `onStart`. See ADR-0050. Constraints, each a denial path: exactly those two keys are copied
 * (nothing else rides along); the parent's values win (the child's attributes are never read); a parent
 * carrying neither key passes neither, a non-recording-SDK parent is no parent, and it never throws.
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
