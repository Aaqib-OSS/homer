package homer.app.executors

import homer.app.EnvConstants
import homer.app.SpanAttributes
import homer.app.exceptions.CommandExecutionException
import homer.app.infra.ResultToEnvMapper
import homer.app.infra.SecretRedactor
import homer.app.infra.TelemetryService
import homer.app.infra.TextCompiler
import homer.app.infra.WhenEvaluator
import homer.app.infra.Workspace
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.ExecutionInfo
import homer.app.models.StopReason
import homer.app.models.kind
import homer.app.runners.RunnerSession
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import java.nio.file.Path
import kotlin.io.path.relativeTo
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Step [index]'s artifact names and attempt-0 paths, the default stem's when [agent]'s own template
 * fails to render; that failure is returned for the step's try to rethrow.
 */
internal fun stepArtifacts(
    agent: Agent,
    textCompiler: TextCompiler,
    workspace: Workspace,
    index: Int,
): StepArtifacts {
    val artifactNames =
        ArtifactNameResolver(
            template = agent.artifactNameTemplate,
            textCompiler = textCompiler,
            artifactsDir = workspace.config.artifacts,
            cmdIndex = index,
            envProvider = { workspace.config.env },
            secretEnvNames = workspace.config.secretEnvNames,
            secretRedactor =
                SecretRedactor.from(
                    workspace.config.env,
                    workspace.config.secretEnvNames,
                    minNeedleLength = SecretRedactor.ARTIFACT_STEM_MIN_NEEDLE_LENGTH,
                ),
        )
    // A stem validation failure (its env is attacker-influenced, ADR-0047 rule 2) is rethrown inside
    // the step's try, so the step still gets its row, a closed span and a cleared current command.
    val artifactNameFailure = runCatching { artifactNames.stdOut(0) to artifactNames.stdErr(0) }
    val (stdOut, stdErr) =
        artifactNameFailure.getOrElse {
            // The agent's own template failed to render — fall back to the
            // default stem (never attacker-influenced: only HMR_CMD_INDEX).
            val defaultStem =
                textCompiler.replaceArguments(
                    EnvConstants.DEFAULT_ARTIFACT_NAME_TEMPLATE,
                    mapOf(EnvConstants.CMD_INDEX to index.toString()),
                )
            workspace.config.artifacts.resolve("$defaultStem.out") to
                workspace.config.artifacts.resolve("$defaultStem.err")
        }
    return StepArtifacts(artifactNames, artifactNameFailure, stdOut, stdErr)
}

/** The span one command's execution is recorded under, parented to the run's own span. */
internal fun startCommandSpan(
    telemetryService: TelemetryService,
    parentContext: Context,
    session: RunnerSession,
    index: Int,
    command: Command,
): Span {
    val commandSpan =
        telemetryService.tracer
            .spanBuilder("command_execution_${index + 1}")
            .setParent(parentContext)
            .startSpan()
    commandSpan.setAttribute(SpanAttributes.RUNNER_ALIAS, session.runnerAlias)
    // The step's name next to the span's index: a script step's span is the only place it appears.
    commandSpan.setAttribute("command.label", command.label)
    session.metadata.forEach { (key, value) -> commandSpan.setAttribute(key, value) }
    return commandSpan
}

/** The command's `when` predicate evaluated on [session] against the current env; null when it has none. */
internal suspend fun evaluateWhen(
    workspace: Workspace,
    command: Command,
    session: RunnerSession,
    index: Int,
): WhenEvaluator.Result? {
    val clause = command.whenClause ?: return null
    return WhenEvaluator(
        shell = session.shell,
        artifactsDir = workspace.config.artifacts,
    ).evaluate(clause, workspace.config.env, index)
}

/**
 * The next step's current-command label and start time, or null when this
 * step ends the run or has no successor. Both the success path (via
 * [shouldStopAfter]) and the skip path share this one handover.
 */
internal fun nextBoundary(
    nextCommand: Command?,
    stop: Boolean,
): Pair<String, Long>? {
    if (stop || nextCommand == null) return null
    return nextCommand.label to Clock.System.now().epochSeconds
}

/**
 * True when the outcome ends the run, so the boundary clears the current command: always on a
 * Failure (the catch rethrows whatever `continueIfFail` says), and on a `finishExitCode` match
 * unless `continueIfFail`.
 */
private fun shouldStopAfter(
    outcome: CommandOutcome,
    command: Command,
): Boolean =
    when (outcome) {
        is CommandOutcome.Failure -> true
        is CommandOutcome.Success -> !command.continueIfFail && outcome.isFinishCode
    }

/**
 * Records [run]'s ExecutionInfo for [outcome] and ends its span; returns the next step's start
 * time when one starts now.
 */
@OptIn(ExperimentalTime::class)
internal suspend fun completeCommand(
    run: StepRun,
    outcome: CommandOutcome,
    executedResult: CommandResult?,
    stoppedBy: StopReason?,
    stepRecords: StepRecords,
    processId: Int,
    artifactsDir: Path,
): Long? {
    val (index, command, nextCommand, startTime, commandSpan, stdOut, stdErr, _, onStop) = run
    val endTime = Clock.System.now().epochSeconds
    // A retried attempt may write other files than attempt 0: the result's paths, else the tracked
    // per-attempt ones (the last attempt threw), else attempt 0 (the executor never ran).
    val recordedStdOut =
        executedResult?.stdOut ?: stepRecords.getCommandArtifacts(processId, index)?.stdOut ?: stdOut
    val recordedStdErr =
        executedResult?.stdErr ?: stepRecords.getCommandArtifacts(processId, index)?.stdErr ?: stdErr
    val executionInfo =
        ExecutionInfo(
            label = command.label,
            start = startTime,
            end = endTime,
            resultCode = outcome.exitCode,
            stdOut = recordedStdOut.relativeTo(artifactsDir),
            stdErr = recordedStdErr.relativeTo(artifactsDir),
            errMsg = (outcome as? CommandOutcome.Failure)?.errMsg,
            outputTruncated = (outcome as? CommandOutcome.Failure)?.outputTruncated ?: false,
            // A step a graceful stop interrupted returns no result; its executor left the charge here,
            // so the step still carries what the CLI spent. ADR-0108.
            llmAccounting = executedResult?.llmAccounting ?: run.executionContext.facts.interruptedAccounting,
            kind = command.kind,
            stoppedBy = stoppedBy?.wire,
            onStop = onStop,
        )
    // The current command clears unless the next step starts now; success and skip share
    // nextBoundary, so the two paths cannot drift.
    val next = nextBoundary(nextCommand, stop = shouldStopAfter(outcome, command))
    stepRecords.completeStep(processId, executionInfo, next?.first, next?.second)
    commandSpan.end()
    return next?.second
}

/** The `when`-predicate evaluation failure [whenResult] describes, with its operator-authored text redacted. */
internal fun evalError(
    command: Command,
    whenResult: WhenEvaluator.Result.EvalError,
    redactor: SecretRedactor,
): CommandExecutionException =
    CommandExecutionException(
        message =
            "when-predicate evaluation failed for '${redactor.redact(whenResult.expression)}': " +
                redactor.redact(whenResult.stderrSummary),
        exitCode = whenResult.exitCode,
        command = redactor.redact(command.toString()),
    )

/** The env [result] maps to, recorded on [span] with its values redacted. */
internal fun recordResultEnv(
    command: Command,
    result: CommandResult,
    redactor: SecretRedactor,
    span: Span,
): Map<String, String> {
    val envFromResult =
        ResultToEnvMapper(result).mapToEnv(
            command.resultEnvParam,
        )
    span.setAttribute(
        "output.env",
        redactor.redact(envFromResult.map { "${it.key}=${it.value}" }.joinToString(", ")),
    )
    return envFromResult
}
