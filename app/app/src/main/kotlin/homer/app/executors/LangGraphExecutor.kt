package homer.app.executors

import homer.app.EnvConstants
import homer.app.SpanAttributes
import homer.app.infra.FileOperations
import homer.app.models.AppConfig
import homer.app.models.Command
import homer.app.models.CommandResult
import io.opentelemetry.api.trace.Span

/**
 * Runs `!<langgraph>` as a thin launcher of `python -m homer_langgraph run --module <module>`, with the workspace env
 * (resume params included) and the server-resolved state dir; the runtime owns side effects and checkpoints (ADR-0024).
 */
class LangGraphExecutor(
    private val fileOperations: FileOperations,
    private val appConfig: AppConfig,
) : CommandExecutor<Command.LangGraph> {
    override suspend fun execute(
        command: Command.LangGraph,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val argv =
            listOf(
                command.python,
                "-m",
                "homer_langgraph",
                "run",
                "--module",
                command.module,
                "--input-env",
                command.inputEnv,
            )
        val redactor = context.secretRedactor()
        span.setAttribute(SpanAttributes.INPUT_VALUE, redactor.redact(argv.joinToString(" ")))

        // Overrides any workspace value: the owner-scoped dir must match what LangGraphRunStatusService
        // resolves for this run, or status and resume open an empty store (ADR-0046).
        val env =
            context.workspace.config.env +
                (EnvConstants.LG_STATE_DIR to appConfig.resolveLangGraphStateDir(context.owner))

        val result =
            context.shell.cmd(
                command = argv,
                stdOut = context.stdOut,
                stdErr = context.stdErr,
                stdIn = null,
                env = env,
                timeout = context.timeoutFor(command),
                interruptGrace = context.interruptGrace,
            )

        span.setOutputValue(context, fileOperations, redactor)

        return result
    }
}
