package homer.app.services.persistence

import homer.app.infra.ProcessJobRegistry
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.RunStop
import homer.app.models.RunnerState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.io.path.Path

// The `processes` row ↔ [ProcessInfo] mapping the ProcessRepository files share: status-name sets, JSON
// codecs and side-table reads. Every `*Tx` function runs inside the caller's transaction.

internal val TERMINAL_STATUS_NAMES =
    listOf(
        ProcessStatus.FINISHED.name,
        ProcessStatus.FAILED.name,
        ProcessStatus.KILLED.name,
        ProcessStatus.UNKNOWN.name,
    )

internal val ACTIVE_STATUS_NAMES = listOf(ProcessStatus.CREATED.name, ProcessStatus.WORKING.name)

private val historyEntrySerializer = PairSerializer(Long.serializer(), ProcessStatus.serializer())
private val historySerializer = ListSerializer(historyEntrySerializer)
private val executionsSerializer = ListSerializer(ExecutionInfo.serializer())

internal val EMPTY_HISTORY_JSON = columnJson.encodeToString(historySerializer, emptyList())
internal val EMPTY_EXECUTIONS_JSON = columnJson.encodeToString(executionsSerializer, emptyList())

internal fun encodeHistory(value: List<Pair<Long, ProcessStatus>>) = columnJson.encodeToString(historySerializer, value)

internal fun decodeHistory(text: String) = columnJson.decodeFromString(historySerializer, text)

internal fun encodeHistoryElement(entry: Pair<Long, ProcessStatus>) = columnJson.encodeToString(historyEntrySerializer, entry)

internal fun encodeExecutions(value: List<ExecutionInfo>) = columnJson.encodeToString(executionsSerializer, value)

internal fun decodeExecutions(text: String) = columnJson.decodeFromString(executionsSerializer, text)

internal fun encodeParams(value: Map<String, String>) = columnJson.encodeToString(stringMapSerializer, value)

internal fun decodeParams(text: String) = columnJson.decodeFromString(stringMapSerializer, text)

internal fun encodeRetainArtifacts(value: List<String>) = columnJson.encodeToString(stringListSerializer, value)

internal fun decodeRetainArtifacts(text: String) = columnJson.decodeFromString(stringListSerializer, text)

internal fun encodeRunnerState(value: RunnerState) = columnJson.encodeToString(RunnerState.serializer(), value)

internal fun decodeRunnerState(text: String) = columnJson.decodeFromString(RunnerState.serializer(), text)

internal fun encodeRunStop(value: RunStop) = columnJson.encodeToString(RunStop.serializer(), value)

internal fun decodeRunStop(text: String) = columnJson.decodeFromString(RunStop.serializer(), text)

// Both status writes' projection: status, history, agent_name and the hook rows' owner. Never
// `selectAll()`: it runs under the write lock. See ADR-0020, ADR-0107.
internal fun transitionRowTx(processId: Int): ResultRow? =
    Processes
        .select(Processes.status, Processes.history, Processes.agentName, Processes.owner)
        .where { Processes.id eq processId }
        .singleOrNull()

// Both status writes' tail, in the caller's transaction: the agent name the caller broadcasts on, and the
// hook rows recorded for [status] that [homer.app.services.HookService] fires after the commit (ADR-0107).
internal fun settledTransitionTx(
    processId: Int,
    row: ResultRow,
    status: ProcessStatus,
    hookRecording: HookRecording?,
    nowSec: Long,
): ProcessRepository.TransitionResult =
    ProcessRepository.TransitionResult(
        updated = true,
        agentName = row[Processes.agentName],
        // A write that leaves the status unchanged records no hooks: the run's own CREATED re-assert
        // after the INSERT would fire the CREATED hooks twice. See ADR-0107.
        hooks =
            hookRecording
                .takeIf { row[Processes.status] != status.name }
                ?.record(processId, row[Processes.agentName], status, row[Processes.owner], nowSec)
                ?: RecordedHooks(),
    )

// One run's tags in tag order, served by the (process_id, tag) primary key with no sort; paths that do
// not surface tags skip this read.
internal fun tagsForTx(processId: Int): List<String> =
    ProcessTags
        .select(ProcessTags.tag)
        .where { ProcessTags.processId eq processId }
        .orderBy(ProcessTags.tag)
        .map { it[ProcessTags.tag] }

// One run's inputs; a missing row (deleted concurrently) reads as none. A page uses inputsByProcessTx.
internal fun inputsForTx(processId: Int): Map<String, String> =
    ProcessInputs
        .select(ProcessInputs.inputParams)
        .where { ProcessInputs.processId eq processId }
        .singleOrNull()
        ?.let { decodeParams(it[ProcessInputs.inputParams]) }
        ?: emptyMap()

// A page's inputs in one chunked `IN` read keyed by process id, never one query per row.
private fun inputsByProcessTx(ids: List<Int>): Map<Int, Map<String, String>> {
    if (ids.isEmpty()) return emptyMap()
    val inputsById = mutableMapOf<Int, Map<String, String>>()
    for (chunk in ids.chunked(SQLITE_PARAM_CHUNK)) {
        ProcessInputs
            .select(ProcessInputs.processId, ProcessInputs.inputParams)
            .where { ProcessInputs.processId inList chunk }
            .forEach { row -> inputsById[row[ProcessInputs.processId]] = decodeParams(row[ProcessInputs.inputParams]) }
    }
    return inputsById
}

// A page's tags in one chunked `IN` read grouped by process id, never one query per row. CWE-400: an id
// set past SQLite's bound-parameter cap must degrade to repeated reads, not a 500.
private fun tagsByProcessTx(ids: List<Int>): Map<Int, List<String>> =
    if (ids.isEmpty()) {
        emptyMap()
    } else {
        val tagsById = mutableMapOf<Int, MutableList<String>>()
        for (chunk in ids.chunked(SQLITE_PARAM_CHUNK)) {
            ProcessTags
                .select(ProcessTags.processId, ProcessTags.tag)
                .where { ProcessTags.processId inList chunk }
                .orderBy(ProcessTags.processId to SortOrder.ASC, ProcessTags.tag to SortOrder.ASC)
                .forEach { row ->
                    val id = row[ProcessTags.processId]
                    // An id's rows never straddle chunks, and the ORDER BY (primary key, no sort)
                    // delivers them tag-ordered, so appending keeps tag order.
                    tagsById.getOrPut(id) { mutableListOf() } += row[ProcessTags.tag]
                }
        }
        tagsById
    }

/**
 * Maps full process rows to [ProcessInfo]s, with their tags and inputs read in one batch each.
 * Lazy, so a caller that folds the rows (costByTag, up to 10 000) decodes them one at a time.
 */
internal fun List<ResultRow>.toProcessInfosTx(jobRegistry: ProcessJobRegistry): Sequence<ProcessInfo> {
    val ids = map { it[Processes.id] }
    val tagsById = tagsByProcessTx(ids)
    val inputsById = inputsByProcessTx(ids)
    return asSequence().map {
        it.toProcessInfo(jobRegistry, tagsById[it[Processes.id]] ?: emptyList(), inputsById[it[Processes.id]] ?: emptyMap())
    }
}

/** Maps one row to a [ProcessInfo]; its live [ProcessInfo.job] comes from [jobRegistry]. */
internal fun ResultRow.toProcessInfo(
    jobRegistry: ProcessJobRegistry,
    tags: List<String>,
    inputParams: Map<String, String>,
): ProcessInfo {
    val id = this[Processes.id]
    return ProcessInfo(
        id = id,
        status = ProcessStatus.valueOf(this[Processes.status]),
        agentName = this[Processes.agentName],
        inputParams = inputParams,
        secretInputKeys = this[Processes.secretInputKeys]?.let { decodeKeys(it) },
        job = jobRegistry.get(id),
        // The blob columns read as absent on the projected record-level reads
        // (`Processes.recordReadColumns`), where their consumers read none of them.
        history = getOrNull(Processes.history)?.let(::decodeHistory).orEmpty(),
        executions = getOrNull(Processes.executions)?.let(::decodeExecutions).orEmpty(),
        currentCommand = this[Processes.currentCommand],
        currentCommandStart = this[Processes.currentCommandStart],
        artifactsDir = this[Processes.artifactsDir]?.let { Path(it) },
        purgeAt = this[Processes.purgeAt],
        runner = getOrNull(Processes.runnerState)?.let(::decodeRunnerState),
        costUsd = this[Processes.costUsd],
        costSource = this[Processes.costSource],
        owner = this[Processes.owner],
        traceId = this[Processes.traceId],
        parentProcessId = this[Processes.parentProcessId],
        rootProcessId = this[Processes.rootProcessId],
        retainArtifacts = this[Processes.retainArtifacts]?.let { decodeRetainArtifacts(it) } ?: emptyList(),
        retainedTruncated = this[Processes.retainedTruncated],
        tags = tags,
        failureReason = this[Processes.failureReason],
        failureReasonRunId = this[Processes.failureReasonRunId],
        stop = getOrNull(Processes.stop)?.let(::decodeRunStop),
    )
}
