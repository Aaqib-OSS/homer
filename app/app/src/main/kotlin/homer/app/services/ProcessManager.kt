package homer.app.services

import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.executors.CommandArtifactPaths
import homer.app.executors.RunInputBytes
import homer.app.executors.RunKiller
import homer.app.executors.RunRecords
import homer.app.executors.RunWorkRecords
import homer.app.infra.ProcessJobRegistry
import homer.app.models.ExecutionInfo
import homer.app.models.LlmAccounting
import homer.app.models.NewRunRecord
import homer.app.models.PathAsStringSerializer
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.runners.kubernetes.ActiveRunIds
import homer.app.services.persistence.DefaultProcessRecordReads
import homer.app.services.persistence.DefaultProcessRetentionRecords
import homer.app.services.persistence.DefaultProcessRunRecords
import homer.app.services.persistence.HookRecording
import homer.app.services.persistence.ProcessRecordReads
import homer.app.services.persistence.ProcessRepository
import homer.app.services.persistence.ProcessRetentionRecords
import homer.app.services.persistence.ProcessRunRecords
import homer.app.services.persistence.RecordedHooks
import homer.app.services.persistence.SettlementSwap
import homer.app.services.persistence.completeStep
import homer.app.services.persistence.encodedParamsBytes
import homer.app.services.persistence.remove
import homer.app.services.persistence.setCurrentCommand
import homer.app.services.persistence.settleAccounting
import kotlinx.coroutines.Job
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Process lifecycle FSM (`CREATED → WORKING → FINISHED/FAILED/KILLED`, plus `UNKNOWN` after a restart) over SQLite via
 * [ProcessRepository], with no in-memory cache; only the live [Job] in [ProcessJobRegistry] is runtime-only. Owns the
 * transitions and their events; record reads, retention and run annotations are the repository's, by delegation. See ADR-0020.
 */
class ProcessManager(
    private val repository: ProcessRepository,
    private val jobRegistry: ProcessJobRegistry,
    private val logger: Logger,
    private val broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster(),
    private val maxProcesses: Int = DEFAULT_MAX_PROCESSES,
    // Configured `workspaceRootPath`: eviction's recursive delete is asserted to stay inside it,
    // so a poisoned persisted path cannot steer it outside the workspace tree (CWE-22).
    workspaceRoot: Path = Path.of("."),
    // Capacity eviction skips the WorkspacePurgeService path, so per-process
    // state held elsewhere (question registrations) must be released here.
    private val onProcessEvicted: suspend (Int) -> Unit = {},
    // The hooks a transition records and the pending cap, resolved per transition from the current catalog
    // with no database read, so it is safe inside the write transaction; null records none. See ADR-0107.
    private val hookRecording: () -> HookRecording? = { null },
    // Fires the hook rows a transition recorded and logs the entries the pending cap shed. Called after
    // the write commits, never inside it: a fire starts a run, whose row write must not nest (ADR-0107).
    private val onHooksRecorded: suspend (RecordedHooks) -> Unit = {},
) : ProcessRecordReads by DefaultProcessRecordReads(repository),
    ProcessRetentionRecords by DefaultProcessRetentionRecords(repository),
    ProcessRunRecords by DefaultProcessRunRecords(repository),
    RunRecords,
    RunWorkRecords,
    RunInputBytes,
    RunKiller,
    ActiveRunIds {
    @OptIn(ExperimentalTime::class)
    val startTime: String =
        Clock.System
            .now()
            .epochSeconds
            .toString()

    // In memory only: the paths an executor wrote, by (processId, cmdIndex). Cleared only when the row goes, never at a
    // terminal status, so a finished command's log tail and LLM-event stream still resolve.
    private val commandArtifacts = ConcurrentHashMap<Int, ConcurrentHashMap<Int, CommandArtifactPaths>>()

    // Settled accountings whose step is not recorded yet, keyed by the pending accounting the step will carry.
    // No mutex, so a settlement can buffer unapplied, as ADR-0089 accepts. Cleared with the process.
    private val unappliedSettlements = ConcurrentHashMap<Int, MutableMap<LlmAccounting, LlmAccounting>>()

    private val evictedWorkspaceCleanup =
        EvictedWorkspaceCleanup(
            workspaceRoot,
            logger,
            ::claimWorkspaceDelete,
            ::settleWorkspaceDelete,
            ::heldWorkspaceDeletes,
            ::pendingWorkspaceDeleteCount,
        )

    override suspend fun setCommandArtifacts(
        processId: Int,
        cmdIndex: Int,
        paths: CommandArtifactPaths,
    ) {
        commandArtifacts.computeIfAbsent(processId) { ConcurrentHashMap() }[cmdIndex] = paths
    }

    override suspend fun getCommandArtifacts(
        processId: Int,
        cmdIndex: Int,
    ): CommandArtifactPaths? = commandArtifacts[processId]?.get(cmdIndex)

    /**
     * The tracked entry with the highest cmdIndex for [processId], which is the running command since commands run in
     * index order (a debug run's single `commandIndices` entry included). Null when nothing is tracked.
     */
    fun latestCommandArtifacts(processId: Int): Pair<Int, CommandArtifactPaths>? =
        commandArtifacts[processId]?.maxByOrNull { it.key }?.toPair()

    @OptIn(ExperimentalTime::class)
    override suspend fun killProcess(
        processId: Int,
        reason: String,
    ) {
        val job = jobRegistry.get(processId)
        val killed = repository.killTransition(processId, Clock.System.now().epochSeconds, hookRecording())
        val killedAgent = killed.agentName
        if (killedAgent != null) {
            logger.info("Killing process: {}, reason: {}", processId, reason)
            job?.cancel(CancellationException(reason.ifEmpty { "Killed !" }))
            broadcaster.emit(ProcessEvent.StatusChanged(processId, ProcessStatus.KILLED, killedAgent))
            broadcaster.emit(ProcessEvent.CurrentCommandChanged(processId, null, null))
        }
        onHooksRecorded(killed.hooks)
    }

    override fun storedInputBytes(params: Map<String, String>): Long = encodedParamsBytes(params)

    override suspend fun obtainProcessId(record: NewRunRecord): Int {
        val result =
            try {
                repository.obtainProcessId(record, maxProcesses, hookRecording = hookRecording())
            } catch (e: ProcessManagerAtCapacityException) {
                logger.warn(
                    "ProcessManager at capacity ({}) and no terminal processes to evict; refusing new process",
                    maxProcesses,
                )
                throw e
            }
        result.evicted?.let { evicted ->
            logger.info(
                "ProcessManager capacity reached; evicted terminal process {} (agent: {}, status: {})",
                evicted.id,
                evicted.agentName,
                evicted.status,
            )
            evicted.artifactsDir?.let { evictedWorkspaceCleanup.delete(evicted.id, it) }
            // Eviction deletes the row in the repository, bypassing removeProcess, so its
            // in-memory entries are dropped here or leak for the uptime (ADR-0047 rule 4).
            commandArtifacts.remove(evicted.id)
            unappliedSettlements.remove(evicted.id)
            onProcessEvicted(evicted.id)
        }
        // After the INSERT's commit: a CREATED hook fires here, and the run it starts writes
        // its own row in its own transaction. See ADR-0107.
        onHooksRecorded(result.hooks)
        return result.processId
    }

    /** Retries the evicted workspaces whose delete failed; the workspace purge sweep calls it every tick. */
    suspend fun retryEvictedWorkspaceDeletes() = evictedWorkspaceCleanup.retryPending()

    suspend fun setProcessStatus(
        processId: Int,
        status: ProcessStatus,
    ) {
        transitionStatus(processId, status, guard = null)
    }

    override suspend fun compareAndSetStatus(
        processId: Int,
        expected: Set<ProcessStatus>,
        new: ProcessStatus,
    ): Boolean =
        // Guards terminal transitions: a concurrent killProcess() can land KILLED before the
        // FINISHED write, and returning false here keeps KILLED (ADR-0020).
        transitionStatus(processId, new, guard = expected)

    @OptIn(ExperimentalTime::class)
    private suspend fun transitionStatus(
        processId: Int,
        newStatus: ProcessStatus,
        guard: Set<ProcessStatus>?,
    ): Boolean {
        val result =
            repository.transitionStatus(
                processId,
                newStatus,
                Clock.System.now().epochSeconds,
                guard,
                hookRecording(),
            )
        if (result.updated) {
            result.agentName?.let { broadcaster.emit(ProcessEvent.StatusChanged(processId, newStatus, it)) }
        }
        // After the status write's commit, and only for a write that applied: a guard-rejected
        // CAS records no hook rows, so there is nothing to fire. See ADR-0107.
        onHooksRecorded(result.hooks)
        return result.updated
    }

    override suspend fun assignJob(
        processId: Int,
        job: Job,
    ) {
        jobRegistry.put(processId, job)
    }

    override suspend fun removeJob(processId: Int) {
        jobRegistry.remove(processId)
    }

    /** Whether this instance runs [processId] right now: an in-memory check, no database read. */
    fun hasLiveJob(processId: Int): Boolean = jobRegistry.get(processId)?.isActive == true

    suspend fun removeProcess(processId: Int) {
        repository.remove(processId)
        jobRegistry.remove(processId)
        commandArtifacts.remove(processId)
        unappliedSettlements.remove(processId)
    }

    /**
     * Appends [executionInfo] and sets [nextLabel] as the current command (null clears it) in one write transaction, emitting
     * at most one [ProcessEvent.CurrentCommandChanged]. A gone row writes nothing; a terminal row records the execution but keeps
     * the command cleared, with no event, so a boundary after a kill cannot resurrect a running command. See ADR-0020.
     */
    override suspend fun completeStep(
        processId: Int,
        executionInfo: ExecutionInfo,
        nextLabel: String?,
        nextStart: Long?,
    ) {
        val settled = executionInfo.llmAccounting?.let { unappliedSettlements[processId]?.remove(it) }
        val recorded =
            repository.completeStep(
                processId,
                settled?.let { executionInfo.copy(llmAccounting = it) } ?: executionInfo,
                nextLabel,
                nextStart,
            )
        if (recorded) broadcaster.emit(ProcessEvent.CurrentCommandChanged(processId, nextLabel, nextStart))
    }

    override suspend fun settleAccounting(
        processId: Int,
        pending: LlmAccounting,
        settled: LlmAccounting,
    ) {
        // A gone row is not buffered: removeProcess or eviction already ran the only removal
        // that would ever clear the entry, so it would leak for the uptime (ADR-0089).
        when (repository.settleAccounting(processId, pending, settled)) {
            is SettlementSwap.Settled -> Unit
            is SettlementSwap.RowGone -> Unit
            is SettlementSwap.PendingNotFound -> bufferSettlement(processId, pending, settled)
        }
    }

    /** Keeps a settlement whose step is not recorded yet; merged when the step is recorded (ADR-0089). */
    private fun bufferSettlement(
        processId: Int,
        pending: LlmAccounting,
        settled: LlmAccounting,
    ) {
        unappliedSettlements.computeIfAbsent(processId) { ConcurrentHashMap() }[pending] = settled
    }

    /**
     * Sets a step's current command at step start and emits [ProcessEvent.CurrentCommandChanged], only while the row is
     * live: after a kill the command stays cleared with no event, as in [completeStep].
     */
    override suspend fun setCurrentCommand(
        processId: Int,
        label: String?,
        startTime: Long?,
    ) {
        if (repository.setCurrentCommand(processId, label, startTime)) {
            broadcaster.emit(ProcessEvent.CurrentCommandChanged(processId, label, startTime))
        }
    }

    data class ProcessListResult(
        val processes: List<ProcessInfo>,
        val total: Int,
        // Open, non-dispatch-bound question count per listed id; an absent id has none.
        val openQuestions: Map<Int, Int> = emptyMap(),
        // The listed rows' root ids the caller may see (ADR-0058). Empty unless the read
        // was given a canSeeRoot check, so a hidden root is the default.
        val visibleRootIds: Set<Int> = emptySet(),
    )

    /**
     * Totals per tag value, plus whether the candidate row set was cut short by the
     * repository's row bound (see [ProcessRepository.costByTag]). See ADR-0062.
     */
    data class CostByTagResult(
        val valuesUsd: Map<String, Double>,
        val truncated: Boolean,
    )

    companion object {
        const val DEFAULT_MAX_PROCESSES = 10_000
    }
}
