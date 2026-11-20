package homer.app.services.persistence

import homer.app.infra.ProcessJobRegistry
import homer.app.infra.PurgeSchedule
import homer.app.models.Cooldown
import homer.app.models.CostSource
import homer.app.models.ExecutionInfo
import homer.app.models.NewRunRecord
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.services.CooldownDecision
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Hard row cap the tag-screened filter path fetches before screening: a `?tag=`
 * read is bounded by this, not only by the caller's `limit`, so a visible-matching
 * set cannot be materialized wholesale (CWE-400). See ADR-0060.
 */
internal const val MAX_TAG_SCREENED_ROWS = 10_000

// Ids per chunked `IN` read. SQLITE_LIMIT_VARIABLE_NUMBER default is 999; stay well
// under even if the build is linked against an older SQLite.
internal const val SQLITE_PARAM_CHUNK = 512

/**
 * All durable access to process state; SQLite is authoritative and every read-modify-write is one
 * `BEGIN IMMEDIATE` transaction. A row's live [ProcessInfo.job] comes from [jobRegistry]. See ADR-0020.
 */
class ProcessRepository(
    private val db: DatabaseService,
    internal val jobRegistry: ProcessJobRegistry,
) {
    /**
     * Runs [block] in one write transaction on the writer handle. With [read], the only way this
     * package's extension functions reach the database: [db] is private to the class. See ADR-0020.
     */
    internal suspend fun <T> query(block: JdbcTransaction.() -> T): T = db.query(block)

    /** Runs a read-only [block] on the reader handle; see [query]. */
    internal suspend fun <T> read(block: JdbcTransaction.() -> T): T = db.read(block)

    /** Outcome of [obtainProcessId]: the new id plus any terminal row evicted to make room. */
    data class ObtainResult(
        val processId: Int,
        val evicted: EvictedProcess?,
        val hooks: RecordedHooks = RecordedHooks(),
    )

    /** The terminal row evicted to make room, with enough detail for the caller to log it and purge its workspace. */
    data class EvictedProcess(
        val id: Int,
        val agentName: String,
        val status: ProcessStatus,
        val artifactsDir: java.nio.file.Path?,
    )

    /** Outcome of a guarded status write: whether it applied, the row's agent name and its hook rows. */
    data class TransitionResult(
        val updated: Boolean,
        val agentName: String?,
        val hooks: RecordedHooks = RecordedHooks(),
    )

    /**
     * A run-time tag write's outcome: [written] is false only when the per-run ceiling was already
     * reached (an exact duplicate is [written], count unchanged), and [count] is the run's runtime-tag
     * count after the attempt, `fromInputs` tags excluded. See ADR-0060.
     */
    data class RuntimeTagResult(
        val written: Boolean,
        val count: Int,
    )

    /**
     * Inserts a new CREATED row and returns its id, enforcing capacity and [NewRunRecord.cooldown] in the
     * INSERT's own `BEGIN IMMEDIATE` transaction, so concurrent starts cannot both pass (ADR-0051).
     * Replay-unsafe (a re-run takes a fresh id); the writer handle is single-attempt (ADR-0020).
     */
    suspend fun obtainProcessId(
        record: NewRunRecord,
        maxProcesses: Int,
        hookRecording: HookRecording? = null,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): ObtainResult =
        db.query {
            record.cooldown?.let { enforceCooldownTx(record.agentName, it, nowSec, record.cooldownKey) }
            val evicted = evictForCapacityTx(maxProcesses)
            val newId =
                Processes.insert {
                    it[status] = ProcessStatus.CREATED.name
                    it[Processes.agentName] = record.agentName
                    it[history] = EMPTY_HISTORY_JSON
                    it[executions] = EMPTY_EXECUTIONS_JSON
                    it[costUsd] = 0.0
                    it[costSource] = CostSource.CLI.wire
                    it[Processes.owner] = record.owner
                    // The cooldown's timestamp, set in the INSERT's transaction: `history` gets
                    // its first entry only later, from the launched coroutine. See ADR-0051.
                    it[createdAt] = nowSec
                    // Rendered before this transaction and written with the row, so every read sees
                    // one subject; never re-derived from the agent definition. See ADR-0106.
                    it[Processes.cooldownKey] = record.cooldownKey
                    // Captured now, never looked up later (ADR-0052). Null = no snapshot ("ask the
                    // current definition"); an empty set = nothing was secret.
                    it[Processes.secretInputKeys] = record.secretInputKeys?.let { keys -> encodeKeys(keys) }
                    it[Processes.parentProcessId] = record.parentProcessId
                    it[Processes.rootProcessId] = record.rootProcessId
                }[Processes.id]
            // Write-once inputs in their own table, which per-step UPDATEs never rewrite, written in
            // the INSERT's transaction so no run is readable without them. See ADR-0020.
            ProcessInputs.insert {
                it[ProcessInputs.processId] = newId
                it[ProcessInputs.inputParams] = encodeParams(record.inputParams)
            }
            // fromInputs tags in the INSERT's transaction, so `?tag=` finds the run as soon as it
            // exists; de-duplicated for the (process_id, tag) primary key. See ADR-0060.
            ProcessTags.batchInsert(record.tags.distinct(), shouldReturnGeneratedValues = false) { tag ->
                this[ProcessTags.processId] = newId
                this[ProcessTags.tag] = tag
            }
            // CREATED hooks ride the INSERT's own transaction: a restart between the row and
            // the fire loses nothing. See ADR-0107.
            ObtainResult(newId, evicted, hookRecording.record(newId, record.agentName, ProcessStatus.CREATED, record.owner, nowSec))
        }

    /**
     * Throws the [CooldownDecision] refusal when [cooldown] bars a start of [agentName] at [nowSec], judged
     * on rows read in the caller's transaction; a window's lookup runs only when it is configured.
     * [cooldownKey] scopes both lookups to one subject, null counts every key. See ADR-0051, ADR-0106.
     */
    private fun enforceCooldownTx(
        agentName: String,
        cooldown: Cooldown,
        nowSec: Long,
        cooldownKey: String?,
    ) {
        CooldownDecision
            .evaluate(
                agentName = agentName,
                cooldown = cooldown,
                nowSec = nowSec,
                latestRunStartedAt = cooldown.minIntervalSeconds?.let { latestRunStartedAtTx(agentName, cooldownKey) },
                latestFailureEndedAt = cooldown.afterFailureSeconds?.let { latestFailureEndedAtTx(agentName, cooldownKey) },
            )?.let { throw it }
    }

    suspend fun getProcessStatus(processId: Int): ProcessStatus =
        db.read {
            Processes
                .select(Processes.status)
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.let { ProcessStatus.valueOf(it[Processes.status]) }
                ?: ProcessStatus.UNKNOWN
        }

    /** One transaction: [accepts] judges the current status, then writes [newStatus] and records hooks; a rejection records none. */
    private suspend fun guardedTransition(
        processId: Int,
        newStatus: ProcessStatus,
        nowSec: Long,
        hookRecording: HookRecording?,
        clearCommand: Boolean,
        reportAgentOnReject: Boolean,
        accepts: (ProcessStatus) -> Boolean,
    ): TransitionResult =
        db.query {
            val row = transitionRowTx(processId) ?: return@query TransitionResult(updated = false, agentName = null)
            if (!accepts(ProcessStatus.valueOf(row[Processes.status]))) {
                return@query TransitionResult(updated = false, agentName = if (reportAgentOnReject) row[Processes.agentName] else null)
            }
            writeStatusTx(processId, row, newStatus, nowSec, clearCommand)
            settledTransitionTx(processId, row, newStatus, hookRecording, nowSec)
        }

    /** Appends [newStatus] to history and writes status (`endedAt` when terminal) in the caller's transaction. */
    private fun writeStatusTx(
        processId: Int,
        row: ResultRow,
        newStatus: ProcessStatus,
        nowSec: Long,
        clearCommand: Boolean,
    ) {
        val newHistory = decodeHistory(row[Processes.history]) + (nowSec to newStatus)
        Processes.update({ Processes.id eq processId }) {
            it[status] = newStatus.name
            it[history] = encodeHistory(newHistory)
            if (clearCommand) {
                it[currentCommand] = null
                it[currentCommandStart] = null
            }
            // Same UPDATE that sets the status, so the terminal time can never
            // disagree with it. See ADR-0051.
            if (newStatus.name in TERMINAL_STATUS_NAMES) it[endedAt] = nowSec
        }
    }

    /**
     * Appends [newStatus] to history and writes it when the row exists and [guard] accepts its current
     * status (null = unconditional). Read, check and write are one transaction: the KILLED-precedence CAS.
     */
    suspend fun transitionStatus(
        processId: Int,
        newStatus: ProcessStatus,
        nowSec: Long,
        guard: Set<ProcessStatus>?,
        hookRecording: HookRecording? = null,
    ): TransitionResult =
        guardedTransition(processId, newStatus, nowSec, hookRecording, clearCommand = false, reportAgentOnReject = true) {
            guard == null || it in guard
        }

    /**
     * Transitions a CREATED/WORKING process to KILLED, clearing the current
     * command. Its agent name is set when it actually transitioned (so the caller
     * can cancel the job and broadcast), and null otherwise.
     */
    suspend fun killTransition(
        processId: Int,
        nowSec: Long,
        hookRecording: HookRecording? = null,
    ): TransitionResult =
        guardedTransition(processId, ProcessStatus.KILLED, nowSec, hookRecording, clearCommand = true, reportAgentOnReject = false) {
            it == ProcessStatus.WORKING || it == ProcessStatus.CREATED
        }

    /**
     * `created_at` of [agentName]'s newest run by id, never its first `history` entry, which is written
     * later (ADR-0051); null when no run is left, so a purge resets the cooldown. [cooldownKey] scopes
     * the subject, null counts every row (ADR-0106).
     */
    suspend fun latestRunStartedAt(
        agentName: String,
        cooldownKey: String? = null,
    ): Long? = db.read { latestRunStartedAtTx(agentName, cooldownKey) }

    /**
     * `ended_at` of [agentName]'s most recent FAILED run that failed while executing, or null; a run
     * refused before it started carries a `failure_reason` and does not arm the window (ADR-0051).
     * [cooldownKey] narrows the read to one subject, as in [latestRunStartedAt] (ADR-0106).
     */
    suspend fun latestFailureEndedAt(
        agentName: String,
        cooldownKey: String? = null,
    ): Long? = db.read { latestFailureEndedAtTx(agentName, cooldownKey) }

    /**
     * Nulls a row's `ended_at`, reproducing a row migrated in from before v14.
     * Test-only: nothing in production ever clears a recorded terminal time.
     */
    internal suspend fun clearEndedAtForTest(processId: Int) {
        db.query { Processes.update({ Processes.id eq processId }) { it[endedAt] = null } }
    }

    suspend fun getProcessHistory(processId: Int): List<Pair<Long, ProcessStatus>> =
        db.read {
            Processes
                .select(Processes.history)
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.let { decodeHistory(it[Processes.history]) }
                ?: emptyList()
        }

    suspend fun getProcessExecutions(processId: Int): List<ExecutionInfo> =
        db.read {
            Processes
                .select(Processes.executions)
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.let { decodeExecutions(it[Processes.executions]) }
                ?: emptyList()
        }

    suspend fun getProcessInfo(processId: Int): ProcessInfo? =
        db.read {
            Processes
                .selectAll()
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.toProcessInfo(jobRegistry, tagsForTx(processId), inputsForTx(processId))
        }

    /**
     * The (owner, agentName) pair a visibility gate reads, by primary key, or null when the row is gone.
     * Projected, not [getProcessInfo]: a gate runs on polled paths, where decoding the blobs is pure cost.
     */
    suspend fun getProcessVisibility(processId: Int): Pair<String?, String>? =
        db.read {
            Processes
                .select(Processes.owner, Processes.agentName)
                .where { Processes.id eq processId }
                .singleOrNull()
                ?.let { it[Processes.owner] to it[Processes.agentName] }
        }

    /** Highest id ever allocated (AUTOINCREMENT high-water), or -1 if none. */
    suspend fun lastProcessId(): Int =
        db.read {
            exec("SELECT seq FROM sqlite_sequence WHERE name = 'processes'") { rs ->
                if (rs.next()) rs.getInt(1) else -1
            } ?: -1
        }

    /**
     * Marks every still-active (CREATED/WORKING) process as UNKNOWN with a
     * history entry and returns their ids. Called once on boot — the coroutines
     * that drove them are gone. See `StartupReconciliation`.
     */
    suspend fun markInterruptedAsUnknown(
        nowSec: Long,
        hookRecording: HookRecording? = null,
    ): InterruptedResult =
        db.query {
            val rows =
                Processes
                    .select(Processes.id, Processes.agentName, Processes.owner)
                    .where { Processes.status inList ACTIVE_STATUS_NAMES }
                    .map { Triple(it[Processes.id], it[Processes.agentName], it[Processes.owner]) }
            if (rows.isNotEmpty()) {
                // One bulk UPDATE, never one per row: `json_insert(.., '$[#]', ..)` appends the entry,
                // encoded by the serializer decodeHistory reads, so it round-trips.
                val element = encodeHistoryElement(nowSec to ProcessStatus.UNKNOWN)
                exec(
                    "UPDATE processes SET status = 'UNKNOWN', " +
                        "history = json_insert(history, '\$[#]', json(?)), " +
                        "current_command = NULL, current_command_start = NULL, " +
                        "ended_at = ? " +
                        "WHERE status IN ('CREATED', 'WORKING')",
                    listOf(TextColumnType() to element, LongColumnType() to nowSec),
                )
            }
            // UNKNOWN hook rows ride that write: no other transition will ever fire for
            // these runs. See ADR-0107.
            val recorded = rows.map { (id, agent, owner) -> hookRecording.record(id, agent, ProcessStatus.UNKNOWN, owner, nowSec) }
            InterruptedResult(rows.map { it.first }, RecordedHooks(recorded.flatMap { it.rows }, recorded.flatMap { it.shed }))
        }

    /**
     * Stamps purge times and `retain_artifacts`, counted from [nowSec], on every UNKNOWN row with no `purge_at`
     * (both sweeps skip one) and returns how many; a repeat boot is a no-op. Startup only, on `processes_status`
     * and the primary key. See ADR-0048 (2026-10-01 amendment).
     */
    suspend fun stampUnknownPurgeTimes(
        nowSec: Long,
        scheduleFor: (agentName: String) -> PurgeSchedule,
    ): Int =
        db.query {
            val unstamped =
                Processes
                    .select(Processes.id, Processes.agentName)
                    .where { (Processes.status eq ProcessStatus.UNKNOWN.name) and Processes.purgeAt.isNull() }
                    .map { it[Processes.id] to it[Processes.agentName] }
            val schedules = unstamped.map { it.second }.distinct().associateWith(scheduleFor)
            for ((schedule, rows) in unstamped.groupBy { schedules.getValue(it.second) }) {
                val retain = schedule.retainArtifacts.takeIf { it.isNotEmpty() }?.let { encodeRetainArtifacts(it) }
                for (chunk in rows.map { it.first }.chunked(SQLITE_PARAM_CHUNK)) {
                    Processes.update({ Processes.id inList chunk }) {
                        it[Processes.purgeAt] = nowSec + schedule.purgeAfterSeconds
                        it[Processes.recordPurgeAt] = nowSec + schedule.purgeRecordAfterSeconds
                        it[Processes.retainArtifacts] = retain
                    }
                }
            }
            unstamped.size
        }
}
