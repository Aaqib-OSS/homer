package homer.app.config

/**
 * Configuration for command execution defaults
 */
data class ExecutionConfig(
    /** Maximum output size to include in tracing (bytes) */
    val maxOutputSizeForTracing: Long = 4096,
    /** Epoch milliseconds: a run's soft deadline is checked against it, and its step timestamps read from it. */
    val clock: () -> Long = System::currentTimeMillis,
)
