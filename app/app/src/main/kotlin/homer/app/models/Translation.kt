package homer.app.models

/**
 * One harness stdout line read into the canonical [LlmEvent] model: what `LlmHarness.translate`
 * returns and the stream readers consume. Kept in the kernel because adapters and readers
 * share it (ADR-0070, ADR-0083).
 */
sealed interface Translation {
    data class Events(
        val events: List<LlmEvent>,
    ) : Translation

    /** A line Homer does not act on. Counted, never an error. */
    data object Ignored : Translation

    /** A line of a known kind whose shape the adapter could not read — a schema-drift signal. */
    data class Malformed(
        val reason: String,
    ) : Translation
}
