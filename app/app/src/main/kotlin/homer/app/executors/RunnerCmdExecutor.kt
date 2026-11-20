package homer.app.executors

import homer.app.SpanAttributes
import homer.app.exceptions.CommandExecutionException
import homer.app.infra.FileOperations
import homer.app.infra.PathSafety
import homer.app.infra.SecretRedactor
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.runners.ActiveSessionsRegistry
import homer.app.runners.DownloadMode
import homer.app.runners.Runner
import homer.app.runners.RunnerLookup
import homer.app.runners.RunnerSession
import homer.app.runners.RunnerSessionParams
import homer.app.runners.TarStreams
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * Executor for Command.RunnerCmd — runs one command on another runner
 * alias with check → syncToRemote → execute → syncFromRemote phases,
 * composed from the ADR-0033 session primitives. See ADR-0034.
 */
class RunnerCmdExecutor(
    private val runners: RunnerLookup,
    private val activeSessions: ActiveSessionsRegistry,
    private val fileOperations: FileOperations,
) : CommandExecutor<Command.RunnerCmd> {
    override suspend fun execute(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val runner = runners.forAlias(command.runner)
        // Two redactors, never one merged map: `+` keeps one value per key, so a runner `env` name
        // colliding with a workspace one would drop that side's credential from the needles (CWE-200).
        val redactor =
            SecretRedactor.from(context.workspace.config.env, context.workspace.config.secretEnvNames) +
                SecretRedactor.from(runner.runnerEnv, runner.sysEnvBackedEnvNames)
        span.setAttribute("runner.target", command.runner)
        span.setAttribute(SpanAttributes.INPUT_VALUE, redactor.redact(command.execute))

        val forwardedEnv = forwardedEnv(runner, context)

        logger.debug("Process {}: runnerCmd opening session on '{}'", context.processId, command.runner)
        val session =
            runner.openSession(
                RunnerSessionParams(
                    processId = context.processId,
                    agentName = context.agentName,
                    workspace = context.workspace.config,
                    env = forwardedEnv,
                    parentContext = Context.current(),
                ),
            )
        activeSessions.add(session)
        try {
            return runOnTarget(command, context, span, TargetRun(runner, session, forwardedEnv, redactor))
        } finally {
            withContext(NonCancellable) { releaseSession(command, context, session) }
        }
    }

    /** The open session on the target runner and what the run on it is composed from. */
    private class TargetRun(
        val runner: Runner,
        val session: RunnerSession,
        val forwardedEnv: Map<String, String>,
        val redactor: SecretRedactor,
    )

    /** check → syncToRemote → execute → syncFromRemote on [target]'s session (ADR-0034). */
    private suspend fun runOnTarget(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        span: Span,
        target: TargetRun,
    ): CommandResult {
        val session = target.session
        val overrides = session.createWorkspace()
        // The target's own copy, never merged into the agent env (ADR-0034); the target runner's `env`,
        // then the session's backend facts, layer over the forwarded env (ADR-0065).
        val targetEnv = target.forwardedEnv + target.runner.runnerEnv + session.env + overrides

        // Extras go before the checks, which may source them: ADR-0042 records this exception to
        // ADR-0034's no transfer before checks.
        uploadExtras(command, context, session)

        val failedCheck = runChecks(command, context, session, targetEnv)
        if (failedCheck != null) return failedCheck

        syncToRemote(command, context, session)

        val result =
            session.shell.cmd(
                command = listOf(command.shell, "-s", "-"),
                stdOut = context.stdOut,
                stdErr = context.stdErr,
                stdIn = command.execute,
                env = targetEnv,
                timeout = context.timeoutFor(command),
                interruptGrace = context.interruptGrace,
            )

        span.setOutputValue(context, fileOperations, target.redactor)

        if (succeeded(command, result)) syncFromRemote(command, context, session)
        return result
    }

    /**
     * The workspace env the target receives: a remote target loses the allowlisted host-env keys still carrying
     * Homer's host value, while a key an operator re-set survives. A local target keeps them all (ADR-0065).
     */
    private fun forwardedEnv(
        runner: Runner,
        context: ExecutionContext,
    ): Map<String, String> =
        // CWE-200
        if (runner.isRemote) {
            context.workspace.config.env - context.workspace.config.parentEnvLayerNames
        } else {
            context.workspace.config.env
        }

    /** Uploads the target runner's `extraUploads` (ADR-0042). */
    private suspend fun uploadExtras(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
    ) {
        for (extra in session.extraUploads) {
            logger.debug("Process {}: runnerCmd extra upload '{}' to '{}'", context.processId, extra.remote, command.runner)
            session.upload(TarStreams.resolvedPackSource(Path.of(extra.local)), extra.remote)
        }
    }

    /** Uploads [command]'s `syncToRemote` paths into the target's workdir. */
    private suspend fun syncToRemote(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
    ) {
        for (path in command.syncToRemote) {
            logger.debug("Process {}: runnerCmd upload '{}' to '{}'", context.processId, path, command.runner)
            session.upload(
                containedLocalPath(context.workspace.config.workdir, path, command),
                "workdir/$path",
            )
        }
    }

    /** Cleans the target workspace, unregisters and closes [session]; a failure is logged or dropped (ADR-0034). */
    @Suppress("SuspendFunSwallowedCancellation") // teardown failures never fail the step (ADR-0034); runs under NonCancellable
    private suspend fun releaseSession(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
    ) {
        runCatching { session.cleanWorkspace() }
            .onFailure {
                logger.warn(
                    "Process {}: runnerCmd workspace cleanup failed on '{}': {}",
                    context.processId,
                    command.runner,
                    it.message,
                )
            }
        activeSessions.remove(session)
        runCatching { session.close() }
    }

    /** Whether [result] exited 0 or with [command]'s `finishExitCode`: only then are the results synced back. */
    private fun succeeded(
        command: Command.RunnerCmd,
        result: CommandResult,
    ): Boolean = result.exitCode == 0 || (command.finishExitCode != null && result.exitCode == command.finishExitCode)

    /** Runs [command]'s checks on [session]; the first failing check's result, or null when all pass. */
    private suspend fun runChecks(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
        targetEnv: Map<String, String>,
    ): CommandResult? {
        for (check in command.check) {
            val res =
                session.shell.cmd(
                    command = listOf(command.shell, "-c", check),
                    stdOut = context.stdOut,
                    stdErr = context.stdErr,
                    stdIn = null,
                    env = targetEnv,
                    timeout = CHECK_TIMEOUT,
                )
            if (res.exitCode != 0) {
                logger.error(
                    "Process {}: runnerCmd check failed on '{}': `{}` (exit {})",
                    context.processId,
                    command.runner,
                    check,
                    res.exitCode,
                )
                // Returned, not thrown: AgentExecutor rethrows an executor exception whatever
                // continueIfFail says (ADR-0034).
                return res
            }
        }
        return null
    }

    /** Downloads [command]'s `syncFromRemote` paths; a path missing on the target fails the step. */
    private suspend fun syncFromRemote(
        command: Command.RunnerCmd,
        context: ExecutionContext,
        session: RunnerSession,
    ) {
        for (path in command.syncFromRemote) {
            logger.debug("Process {}: runnerCmd download '{}' from '{}'", context.processId, path, command.runner)
            val local = containedLocalPath(context.workspace.config.workdir, path, command)
            session.download("workdir/$path", local, DownloadMode.OVERWRITE)
            // download() is a silent no-op on missing remote paths —
            // a requested result that never appeared is an error.
            if (!Files.exists(local)) {
                throw CommandExecutionException(
                    message = "runnerCmd syncFromRemote path not found on target: $path",
                    exitCode = -1,
                    command = command.toString(),
                )
            }
        }
    }

    /**
     * Resolves a `sync*` path under [workdir], refusing one whose realpath leaves it: the load-time check is only
     * lexical, and an earlier step can plant a symlink there (CWE-59, ADR-0047 rule 2).
     */
    private fun containedLocalPath(
        workdir: Path,
        path: String,
        command: Command.RunnerCmd,
    ): Path =
        try {
            PathSafety.assertContained(workdir, workdir.resolve(path))
        } catch (_: SecurityException) {
            throw CommandExecutionException(
                message = "runnerCmd sync path '$path' resolves outside the workspace",
                exitCode = -1,
                command = command.toString(),
            )
        }

    companion object {
        private val CHECK_TIMEOUT = 60.seconds
        private val logger = LoggerFactory.getLogger(RunnerCmdExecutor::class.java)
    }
}
