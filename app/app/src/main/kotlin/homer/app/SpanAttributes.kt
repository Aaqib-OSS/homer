package homer.app

/**
 * Span attribute keys and span event names Homer writes on its own spans, modelled on
 * [EnvConstants]: one kernel object every layer may import, so a key cannot be mistyped at a
 * call site and silently stop matching a trace query.
 *
 * Plain `const val` strings, not `AttributeKey`s: the values are the exported surface, so they
 * must reach the collector byte-identical to the literals they replaced. See ADR-0050.
 */
object SpanAttributes {
    /** Status of the run, as its `ProcessStatus` name; written by `ProcessExecutor` and `Process`. */
    const val PROCESS_STATUS = "process.status"

    /**
     * Prefix of the `process.status.<status>` span events written next to [PROCESS_STATUS], one per
     * status transition (`process.status.created`, `process.status.finished`, …).
     */
    const val PROCESS_STATUS_EVENT_PREFIX = "process.status."
}
