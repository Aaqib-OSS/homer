package homer.app.config

/**
 * Configuration for command execution defaults
 */
data class ExecutionConfig(
    /** Maximum output size to include in tracing (bytes) */
    val maxOutputSizeForTracing: Long = 4096,
    /** Epoch milliseconds: a run's soft deadline is checked against it, and its step timestamps read from it. */
    val clock: () -> Long = System::currentTimeMillis,
) {
    /** [clock] in whole epoch seconds, the unit of step timestamps and run deadlines. */
    fun nowEpochSec(): Long = clock() / 1000
}
