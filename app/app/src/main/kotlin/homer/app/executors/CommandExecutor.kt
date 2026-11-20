package homer.app.executors

import homer.app.models.Command
import homer.app.models.CommandResult
import io.opentelemetry.api.trace.Span

/**
 * Interface for command executors
 * Each command type has its own executor implementation
 */
interface CommandExecutor<T : Command> {
    /** Runs [command] in [context]'s workspace and env, tracing on [span]; returns its result and exit code. */
    suspend fun execute(
        command: T,
        context: ExecutionContext,
        span: Span,
    ): CommandResult
}
