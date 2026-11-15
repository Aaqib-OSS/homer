package homer.app.services.persistence

import homer.app.models.ExecutionInfo
import homer.app.models.ProcessInfo
import homer.app.models.ProcessListQuery
import homer.app.models.ProcessStatus
import homer.app.models.RunnerState
import homer.app.models.SubRunProgress
import homer.app.services.ProcessManager
import homer.app.services.ProcessManager.CostByTagResult
import homer.app.services.ProcessManager.ProcessListResult

/**
 * The reads of [ProcessManager]'s API: the durable process records as stored, with no state
 * transition and no event. [ProcessManager] exposes them by delegation to
 * [DefaultProcessRecordReads]. See ADR-0020.
 */
interface ProcessRecordReads {
    /** Highest process id ever allocated (monotonic AUTOINCREMENT high-water), or -1 if none. */
    suspend fun lastProcessId(): Int

    suspend fun getProcessStatus(processId: Int): ProcessStatus

    /** (id, agent name) of CREATED runs of the given agents, for `/agents/reload` to kill. */
    suspend fun createdRunsOf(agentNames: Collection<String>): List<Pair<Int, String>>

    suspend fun activeProcessIds(): Set<Int>

    suspend fun getRunnerState(processId: Int): RunnerState?

    suspend fun getProcessHistory(processId: Int): List<Pair<Long, ProcessStatus>>

    suspend fun getProcessExecutions(processId: Int): List<ExecutionInfo>

    suspend fun getProcessInfo(processId: Int): ProcessInfo?

    /** The (owner, agentName) a visibility gate reads, or null when the run is gone. See [ProcessRepository.getRunVisibility]. */
    suspend fun getProcessVisibility(processId: Int): Pair<String?, String>?

    /** [SubRunProgress] of each of [processIds] that still exists, in one batched read. */
    suspend fun getSubRunProgress(processIds: Collection<Int>): Map<Int, SubRunProgress>

    /** See [ProcessRepository.getAll]. */
    suspend fun getAllProcesses(query: ProcessListQuery = ProcessListQuery()): ProcessListResult

    /** See [ProcessRepository.costByTag]. */
    suspend fun costByTag(
        key: String,
        screenTags: (ProcessInfo) -> List<String>,
    ): CostByTagResult
}

/** [ProcessRecordReads] straight from [repository]. */
internal class DefaultProcessRecordReads(
    private val repository: ProcessRepository,
) : ProcessRecordReads {
    override suspend fun lastProcessId(): Int = repository.lastProcessId()

    override suspend fun getProcessStatus(processId: Int): ProcessStatus = repository.getStatus(processId)

    override suspend fun createdRunsOf(agentNames: Collection<String>): List<Pair<Int, String>> = repository.createdRunsOf(agentNames)

    override suspend fun activeProcessIds(): Set<Int> = repository.activeProcessIds()

    override suspend fun getRunnerState(processId: Int): RunnerState? = repository.getRunnerState(processId)

    override suspend fun getProcessHistory(processId: Int): List<Pair<Long, ProcessStatus>> = repository.getHistory(processId)

    override suspend fun getProcessExecutions(processId: Int): List<ExecutionInfo> = repository.getExecutions(processId)

    override suspend fun getProcessInfo(processId: Int): ProcessInfo? = repository.getInfo(processId)

    override suspend fun getProcessVisibility(processId: Int): Pair<String?, String>? = repository.getRunVisibility(processId)

    override suspend fun getSubRunProgress(processIds: Collection<Int>): Map<Int, SubRunProgress> = repository.subRunProgress(processIds)

    override suspend fun getAllProcesses(query: ProcessListQuery): ProcessListResult = repository.getAll(query)

    override suspend fun costByTag(
        key: String,
        screenTags: (ProcessInfo) -> List<String>,
    ): CostByTagResult = repository.costByTag(key, screenTags)
}
