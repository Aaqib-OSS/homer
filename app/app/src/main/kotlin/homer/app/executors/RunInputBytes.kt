package homer.app.executors

/**
 * The stored byte size of a run's resolved inputs, so the per-run bound [ProcessExecutor] enforces and the
 * `process_inputs` row it guards are measured the same way. Implemented by `ProcessManager`. See ADR-0047 rule 4.
 */
interface RunInputBytes {
    /** UTF-8 byte length of [params] exactly as the run's row stores them. */
    fun storedInputBytes(params: Map<String, String>): Long
}
