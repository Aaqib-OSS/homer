package homer.app.infra

import kotlin.time.Duration

/** Runs the `homer_langgraph state` subprocess for one LangGraph run. */
fun interface GraphStateRunner {
    suspend fun run(
        python: String,
        module: String,
        threadId: String,
        env: Map<String, String>,
        timeout: Duration,
    ): GraphStateResult
}

sealed class GraphStateResult {
    data class Ok(
        val json: String,
    ) : GraphStateResult()

    data class Err(
        val message: String,
    ) : GraphStateResult()
}

/**
 * Host-local implementation: spawns `<python> -m homer_langgraph state --module
 * <module> --thread <threadId>` on the server host, inheriting the server
 * process environment so `python` and `homer_langgraph` resolve, then overlays
 * the agent's declared `env` (e.g. `PYTHONPATH`, `HOMER_LG_STATE_DIR`) so the
 * agent's module and checkpoint store resolve as in a real run. Server-host
 * introspection, not sandboxed agent execution. See ADR-0044.
 */
class ProcessGraphStateRunner : GraphStateRunner {
    override suspend fun run(
        python: String,
        module: String,
        threadId: String,
        env: Map<String, String>,
        timeout: Duration,
    ): GraphStateResult =
        // A cancelled caller (disconnect, shutdown, kill race) must not become a cached per-command failure.
        coRunCatching {
            // CWE-770: the shared helper caps output at write time.
            // See the ADR-0073 amendment.
            outcomeResult(
                runBoundedGraphSubprocess(
                    python = python,
                    args = listOf("state", "--module", module, "--thread", threadId),
                    env = env,
                    timeout = timeout,
                ),
                timeout,
            )
        }.getOrElse { e ->
            GraphStateResult.Err(e.message ?: "failed to launch python")
        }

    private fun outcomeResult(
        outcome: GraphSubprocessOutcome,
        timeout: Duration,
    ): GraphStateResult =
        when (outcome) {
            is GraphSubprocessOutcome.Exited -> exitedResult(outcome)
            GraphSubprocessOutcome.TimedOut -> GraphStateResult.Err("state timed out after $timeout")
            GraphSubprocessOutcome.StdoutExceeds -> GraphStateResult.Err("state output exceeds $MAX_GRAPH_STDOUT_BYTES bytes")
            is GraphSubprocessOutcome.LaunchFailed -> GraphStateResult.Err(outcome.message)
        }

    private fun exitedResult(outcome: GraphSubprocessOutcome.Exited): GraphStateResult {
        if (outcome.exitCode == 0) return GraphStateResult.Ok(outcome.stdout.trim())
        val stderr = outcome.stderr.trim()
        val message = stderr.ifBlank { readHead(outcome.stdout.byteInputStream(), MAX_GRAPH_STDERR_BYTES).trim() }
        return GraphStateResult.Err(message.ifBlank { "state exited ${outcome.exitCode}" })
    }
}
