package homer.app.executors

import homer.app.SpanAttributes
import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.MaxParallelRunsExceededException
import homer.app.exceptions.UnresolvedLockKeyPlaceholderException
import homer.app.infra.TelemetryService
import homer.app.models.NewRunRecord
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessStatus
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.Logger

class ProcessExecutor(
    private val telemetryService: TelemetryService,
    private val processManager: RunRecords,
    private val questionsService: QuestionExpiry,
    private val runMessages: RunMessageExpiry,
    private val coroutineScope: CoroutineScope,
    private val logger: Logger,
    // Fires completion continuations with the committed terminal status; wired lazily in DI to break
    // a cycle, and a failure is caught in releaseJob, never reaching the status. ADR-0023.
    private val onTerminal: suspend (Int, ProcessStatus) -> Unit = { _, _ -> },
) {
    suspend fun execute(
        process: Process,
        parentContext: Context,
    ): ProcessCreationResult {
        // cooldown goes through obtainProcessId so the check is atomic with the row
        // INSERT; AgentRunStarter's pre-flight check cannot bind on its own. ADR-0051.
        val processId =
            processManager.obtainProcessId(
                NewRunRecord(
                    process.name,
                    process.queryParams,
                    owner = process.owner,
                    cooldown = process.cooldown,
                    cooldownKey = process.cooldownKey,
                    secretInputKeys = process.secretInputKeys,
                    parentProcessId = process.parentProcessId,
                    rootProcessId = process.rootProcessId,
                    tags = process.fromInputTags,
                ),
            )

        logger.info("Process created {} for agent: {}", processId, process.name)
        processManager.assignJob(processId, coroutineScope.launch { runJob(processId, process, parentContext) })

        return ProcessCreationResult(processId)
    }

    /** The launched job: runs [process] under its own span and commits exactly one terminal status. */
    @Suppress("SuspendFunSwallowedCancellation") // the run's boundary: a kill is committed as KILLED, see Status precedence
    private suspend fun runJob(
        processId: Int,
        process: Process,
        parentContext: Context,
    ) {
        val processSpan =
            telemetryService.tracer
                .spanBuilder("agent_execution")
                .setParent(parentContext)
                .startSpan()

        // The trace id outlives the artifacts, so a purged run still links to its Langfuse trace.
        // isSampled: a `telemetry: OFF` run's valid id is never exported, so it would be a dead link.
        processSpan.spanContext
            .takeIf { it.isValid && it.isSampled }
            ?.let { processManager.setTraceId(processId, it.traceId) }
        // Guarded: a kill can land before this first write (a failed callback join kills
        // the sub it just started), and an unguarded write would reset KILLED to CREATED.
        processManager.compareAndSetStatus(processId, setOf(ProcessStatus.CREATED), ProcessStatus.CREATED)
        try {
            processSpan.setAttribute(SpanAttributes.PROCESS_ID, processId.toLong())
            processSpan.setAttribute(SpanAttributes.PROCESS_STATUS, ProcessStatus.CREATED.name)
            processSpan.addEvent("${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}created")
            process.execute(processId, processSpan)
            commitFinished(processId, process, processSpan)
        } catch (e: Exception) {
            withContext(NonCancellable) { commitFailed(processId, process, processSpan, e) }
        } finally {
            withContext(NonCancellable) { releaseJob(processId, processSpan) }
        }
    }

    /** Happy path: CAS to FINISHED; a terminal status that landed first wins and is mirrored in telemetry. */
    private suspend fun commitFinished(
        processId: Int,
        process: Process,
        processSpan: Span,
    ) {
        // A CAS, so a concurrent kill's KILLED is never overwritten ("Status precedence rules",
        // app/CLAUDE.md).
        val transitionedToFinished =
            processManager.compareAndSetStatus(
                processId,
                setOf(ProcessStatus.WORKING, ProcessStatus.CREATED),
                ProcessStatus.FINISHED,
            )
        schedulePurge(processId, process)
        if (transitionedToFinished) {
            processSpan.setAttribute(SpanAttributes.PROCESS_STATUS, ProcessStatus.FINISHED.name)
            processSpan.addEvent("${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}finished")
            processSpan.setStatus(StatusCode.OK)
            logger.info("Process {} completed successfully", processId)
        } else {
            val finalStatus = processManager.getProcessStatus(processId)
            processSpan.setAttribute(SpanAttributes.PROCESS_STATUS, finalStatus.name)
            processSpan.addEvent("${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}${finalStatus.name.lowercase()}")
            processSpan.setStatus(
                StatusCode.ERROR,
                "Process completed but final status is $finalStatus",
            )
            logger.warn(
                "Process {} completed but final status is {} (concurrent transition)",
                processId,
                finalStatus,
            )
        }
    }

    /** Exception path: CAS to FAILED unless KILLED, then telemetry from the committed final status. */
    private suspend fun commitFailed(
        processId: Int,
        process: Process,
        processSpan: Span,
        e: Exception,
    ) {
        // A CAS like the FINISHED write: a concurrent kill's KILLED is never overwritten with FAILED.
        processManager.compareAndSetStatus(
            processId,
            ProcessStatus.entries.toSet() - ProcessStatus.KILLED,
            ProcessStatus.FAILED,
        )

        // Only a typed pre-start cause stores a reason; the guarded write skips a run that recorded
        // a step or whose kill won the CAS above. ADR-0098.
        storeFailureReason(processId, process, e)

        // Get final status for telemetry
        val finalStatus = processManager.getProcessStatus(processId)

        schedulePurge(processId, process)

        if (e is AnotherProcessStillActive || e is MaxParallelRunsExceededException || e is UnresolvedLockKeyPlaceholderException) {
            // Slot/lock collision, or a lock key the run cannot render - process never ran
            processSpan.setAttribute(SpanAttributes.PROCESS_STATUS, ProcessStatus.FAILED.name)
            processSpan.addEvent("${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}failed")
            processSpan.setStatus(
                StatusCode.ERROR,
                "Agent execution cancelled: ${e.message}",
            )
            processSpan.recordException(e)
            logger.error("Process {} cancelled: {}", processId, e.message)
        } else if (finalStatus == ProcessStatus.KILLED) {
            // Process was killed (user or lock manager)
            processSpan.setAttribute(SpanAttributes.PROCESS_STATUS, ProcessStatus.KILLED.name)
            processSpan.addEvent("${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}killed")
            processSpan.setStatus(
                StatusCode.ERROR,
                "Process killed: ${e.message}",
            )
            processSpan.recordException(e)
            logger.error("Process {} killed: {}", processId, e.message, e)
        } else {
            // Normal failure
            processSpan.setAttribute(SpanAttributes.PROCESS_STATUS, ProcessStatus.FAILED.name)
            processSpan.addEvent("${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}failed")
            processSpan.setStatus(
                StatusCode.ERROR,
                "Agent execution failed: ${e.message}",
            )
            processSpan.recordException(e)
            logger.error("Process {} failed: {}", processId, e.message, e)
        }
    }

    /**
     * Stores why the run failed before it ever ran, redacted with the run's own redactor.
     * Runs after the FAILED CAS, so the status is already committed: a failed write is logged
     * and nothing else — it must never change the status, nor stop the release below.
     */
    @Suppress("SuspendFunSwallowedCancellation") // runs under NonCancellable; the status is already committed
    private suspend fun storeFailureReason(
        processId: Int,
        process: Process,
        e: Exception,
    ) {
        val failure = PreStartFailureReason.of(e) ?: return
        try {
            processManager.setFailureReason(processId, process.failureReasonRedactor.redact(failure.reason), failure.holderRunId)
        } catch (writeError: Exception) {
            logger.error("Storing the failure reason for process {} failed: {}", processId, writeError.message, writeError)
        }
    }

    /** Every exit is terminal: expire open questions, fire continuations, end the span, drop the job. */
    @Suppress("SuspendFunSwallowedCancellation") // runs under NonCancellable; a hook failure must not stop the release
    private suspend fun releaseJob(
        processId: Int,
        processSpan: Span,
    ) {
        // Every exit is terminal, so open questions can never be answered: expire them and resume
        // their waiters.
        questionsService.expireForProcess(processId)
        // Messages still queued can never be delivered either. A failure here must not stop the release.
        try {
            runMessages.closeForProcess(processId)
        } catch (e: Exception) {
            logger.error("Closing the inbox of process {} failed: {}", processId, e.message, e)
        }
        // Fired off the committed final status, re-read for the KILLED-wins value; caught so a hook
        // failure never touches that status. ADR-0023.
        try {
            onTerminal(processId, processManager.getProcessStatus(processId))
        } catch (e: Exception) {
            logger.error("Continuation hook for process {} failed: {}", processId, e.message, e)
        }
        processSpan.end()
        processManager.removeJob(processId)
    }

    private suspend fun schedulePurge(
        processId: Int,
        process: Process,
    ) {
        val now = System.currentTimeMillis() / 1000
        processManager.setPurgeAt(
            processId,
            purgeAt = now + process.purgeAfterSeconds,
            recordPurgeAt = now + process.purgeRecordAfterSeconds,
            retainArtifacts = process.retainArtifacts,
        )
    }
}
