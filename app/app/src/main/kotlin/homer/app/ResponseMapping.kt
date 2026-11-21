package homer.app

import homer.app.models.Agent
import homer.app.models.ContinuationResponse
import homer.app.models.ContinuationSummary
import homer.app.models.DispatchResponse
import homer.app.models.DispatchSummary
import homer.app.models.ExecutionInfo
import homer.app.models.ExecutionInfoResponse
import homer.app.models.LangfuseConfig
import homer.app.models.PodStartupFailureResponse
import homer.app.models.PodTerminationResponse
import homer.app.models.ProcessHistoryEntry
import homer.app.models.ProcessInfo
import homer.app.models.ProcessInfoResponse
import homer.app.models.QuestionResponse
import homer.app.models.RunMessageResponse
import homer.app.models.RunnerState
import homer.app.models.RunnerStateResponse
import homer.app.models.stripUrlSecrets
import homer.app.models.truncateFailureReason
import homer.app.services.Continuation
import homer.app.services.Dispatch
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.RunMessage
import java.net.URI

/**
 * Converts ProcessInfo to its response DTO. [agent] is the current definition (null if gone); secret
 * inputs are redacted per [redactInputParams]. [rootVisible] gates [rootProcessId], so a foreign flow
 * root is not leaked. See ADR-0058.
 */
fun ProcessInfo.toResponse(
    openQuestions: Int = 0,
    agent: Agent?,
    langfuse: LangfuseConfig = LangfuseConfig(),
    rootVisible: Boolean = true,
    currentCommandKind: String? = null,
    currentCommandIndex: Int? = null,
    // Fail closed, unlike `rootVisible`: the alias is admin-only (CWE-200). See ADR-0077.
    aliasVisible: Boolean = false,
    // Generic backend kind for `this.runner.alias`, precomputed by the caller from
    // the config; always emitted so a non-admin row keeps a backend label.
    runnerType: String? = null,
    // Fail closed, like `aliasVisible`: `failureReason` names the run that blocked this one,
    // so its id is appended only when the caller can see that run. See ADR-0058.
    holderVisible: Boolean = false,
): ProcessInfoResponse =
    ProcessInfoResponse(
        id = this.id,
        status = this.status.name,
        agentName = this.agentName,
        // From the current definition, so a group edit shows on past runs; null if gone or ungrouped.
        group = agent?.group,
        history =
            this.history.map { (timestamp, status) ->
                ProcessHistoryEntry(
                    timestamp = timestamp,
                    status = status.name,
                )
            },
        executions = this.executions.map { it.toResponse() },
        currentCommand = this.currentCommand,
        currentCommandStart = this.currentCommandStart,
        currentCommandKind = currentCommandKind,
        currentCommandIndex = currentCommandIndex,
        purgeAt = this.purgeAt,
        inputParams = redactInputParams(this.inputParams, agent, this.secretInputKeys),
        runner = this.runner?.toResponse(type = runnerType ?: "unknown", aliasVisible = aliasVisible),
        costUsd = this.costUsd,
        costSource = this.costSource,
        openQuestions = openQuestions,
        owner = this.owner,
        langfuseTraceUrl = langfuseTraceUrl(this.traceId, langfuse),
        parentProcessId = this.parentProcessId,
        // CWE-200: emitted only when the caller may open the root; a hidden root reads as null.
        rootProcessId = if (rootVisible) this.rootProcessId else null,
        retainedTruncated = this.retainedTruncated,
        tags = screenTags(this.tags, this.inputParams, agent, this.secretInputKeys),
        // CWE-200: the blocking run's id is appended only when the caller may open it; the cap
        // applies to the assembled text.
        failureReason =
            this.failureReason?.let { reason ->
                val holder = this.failureReasonRunId
                if (holderVisible && holder != null) truncateFailureReason("$reason (run #$holder)") else reason
            },
        // The stop record names no other principal: the label it carries is the agent's own
        // step label, already served to every caller who can see the run.
        stop = this.stop,
    )

/** Maps one execution record to its response DTO. */
private fun ExecutionInfo.toResponse(): ExecutionInfoResponse =
    ExecutionInfoResponse(
        label = label,
        start = start,
        end = end,
        resultCode = resultCode,
        stdOut = stdOut?.toString(),
        stdErr = stdErr?.toString(),
        errMsg = errMsg,
        skipped = skipped,
        llmAccounting = llmAccounting,
        kind = kind,
        stoppedBy = stoppedBy,
        onStop = onStop,
        commandIndex = commandIndex,
    )

/**
 * Builds the Langfuse UI URL for [traceId], or null when any piece is missing.
 * Langfuse resolves a trace only under its project, and the project id is not
 * derivable from the OTLP credentials — hence the explicit `langfuse.projectId`.
 */
private fun langfuseTraceUrl(
    traceId: String?,
    langfuse: LangfuseConfig,
): String? {
    if (traceId.isNullOrBlank() || langfuse.serverUrl.isBlank() || langfuse.projectId.isBlank()) return null
    // Operator-supplied `serverUrl` reaches every caller who sees the run: userinfo, query and
    // fragment are dropped, and an unparseable URL yields no link.
    val base = stripUrlSecrets(langfuse.serverUrl.trimEnd('/'))?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (base.scheme == null || base.host == null) return null
    return runCatching {
        URI(
            base.scheme,
            null,
            base.host,
            base.port,
            "${base.path.orEmpty()}/project/${langfuse.projectId}/traces/$traceId",
            null,
            null,
        ).toString()
    }.getOrNull()
}

/**
 * The alias is admin-only (CWE-200, fail closed); non-admins get the generic
 * [type]. See ADR-0077.
 */
fun RunnerState.toResponse(
    type: String,
    aliasVisible: Boolean,
): RunnerStateResponse =
    RunnerStateResponse(
        alias = if (aliasVisible) this.alias else null,
        type = type,
        podName = this.podName,
        podPhase = this.podPhase.name,
        createdAt = this.createdAt,
        readyAt = this.readyAt,
        endedAt = this.endedAt,
        startupFailure =
            this.startupFailure?.let {
                PodStartupFailureResponse(
                    reason = it.reason,
                    message = it.message,
                    events = it.events,
                )
            },
        termination = this.termination?.let { PodTerminationResponse(reason = it.reason, message = it.message) },
        jobName = this.jobName,
    )

/** [redactedParams] must come from `redactStoredParams`; the raw map may hold credentials. */
fun Continuation.toResponse(redactedParams: Map<String, String>): ContinuationResponse =
    ContinuationResponse(
        id = id,
        watchedProcessId = watchedProcessId,
        status = status.name,
        agentName = targetAgentName,
        resumeParam = resumeParam,
        params = redactedParams,
        originProcessId = originProcessId,
        originAgentName = originAgentName,
        expiresAt = expiresAt,
        createdAt = createdAt,
        firedAt = firedAt,
        firedProcessId = firedProcessId,
        error = error,
    )

fun Continuation.toSummary(): ContinuationSummary =
    ContinuationSummary(id = id, agentName = targetAgentName, status = status.name, firedProcessId = firedProcessId, error = error)

/** [redactedParams] must come from `redactStoredParams`; the raw map may hold credentials. */
fun Dispatch.toResponse(redactedParams: Map<String, String>): DispatchResponse =
    DispatchResponse(
        id = id,
        questionId = questionId,
        status = status.name,
        agentName = agentName,
        answerParam = answerParam,
        params = redactedParams,
        originProcessId = originProcessId,
        originAgentName = originAgentName,
        expiresAt = expiresAt,
        createdAt = createdAt,
        dispatchedAt = dispatchedAt,
        dispatchedProcessId = dispatchedProcessId,
        error = error,
        kind = kind.name,
        questionIds = questionIds,
    )

fun Dispatch.toSummary(): DispatchSummary =
    DispatchSummary(id = id, agentName = agentName, status = status.name, dispatchedProcessId = dispatchedProcessId, error = error)

fun QuestionsService.Question.toResponse(): QuestionResponse =
    QuestionResponse(
        id = id,
        processId = processId,
        agentName = agentName,
        text = text,
        options = options,
        status = status.name,
        answer = answer,
        createdAt = createdAt,
        answeredAt = answeredAt,
    )

fun QuestionsService.Question.toResponse(dispatch: DispatchSummary?): QuestionResponse = toResponse().copy(dispatch = dispatch)

fun RunMessage.toResponse(): RunMessageResponse =
    RunMessageResponse(
        id = id,
        processId = processId,
        text = text,
        status = status.name,
        sender = sender,
        createdAt = createdAt,
        deliveredAt = deliveredAt,
    )
