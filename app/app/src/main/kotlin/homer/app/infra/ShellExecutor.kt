package homer.app.infra

import homer.app.models.CommandResult
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Interface for shell command execution
 */
interface ShellExecutor {
    /**
     * Executes a shell command. [stdOutFilter] filters stdout lines before they reach [stdOut]; null keeps all
     * (ADR-0063). [interruptGrace] gives a timed-out command SIGINT and that long to exit before SIGKILL; null kills
     * at once (ADR-0108).
     */
    suspend fun cmd(
        command: List<String>,
        stdOut: Path,
        stdErr: Path,
        stdIn: String? = null,
        env: Map<String, String> = emptyMap(),
        timeout: Duration,
        stdOutFilter: OutputLineFilter? = null,
        interruptGrace: Duration? = null,
    ): CommandResult
}
