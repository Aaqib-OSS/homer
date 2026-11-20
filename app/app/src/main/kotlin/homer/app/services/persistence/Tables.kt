package homer.app.services.persistence

import homer.app.services.ContinuationKind
import homer.app.services.DispatchKind
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull

// A Boolean in a column declared INTEGER, the type the v18 migration added, so a fresh database
// matches an upgraded one. `bool()` would declare BOOLEAN (NUMERIC affinity).
internal class IntegerBooleanColumnType : ColumnType<Boolean>() {
    private val bool = BooleanColumnType()

    override fun sqlType(): String = "INTEGER"

    override fun valueFromDB(value: Any): Boolean = bool.valueFromDB(value)

    override fun notNullValueToDB(value: Boolean): Any = bool.notNullValueToDB(value)

    override fun nonNullValueToString(value: Boolean): String = bool.nonNullValueToString(value)
}

// `integer()` without the int32 range CHECK `Table.integer()` adds, for a column a migration adds
// with `ALTER TABLE ADD COLUMN`, which cannot add the CHECK — so a fresh DB matches an upgraded one.
private fun Table.integerWithoutRangeCheck(name: String): Column<Int> = registerColumn(name, IntegerColumnType())

// Durable schema for orchestrator state. Filtered/sorted fields are real columns; whole-blob fields
// are JSON text. Write-once `input_params` lives in [ProcessInputs]. See ADR-0020.
internal object Processes : Table("processes") {
    // INTEGER PRIMARY KEY AUTOINCREMENT: monotonic across restarts and never reused, so a
    // post-restart run always gets a strictly higher id than any historical one.
    val id = integer("id").autoIncrement()
    val status = varchar("status", 16)
    val agentName = varchar("agent_name", 255)
    val currentCommand = text("current_command").nullable()
    val currentCommandStart = long("current_command_start").nullable()
    val purgeAt = long("purge_at").nullable()

    // When the process ROW may be deleted, vs purge_at (its workspace dir); equal unless workspace.retain.recordFor. See ADR-0048.
    val recordPurgeAt = long("record_purge_at").nullable()
    val costUsd = double("cost_usd").default(0.0)

    // Which pricing rung produced cost_usd: 'cli' | 'session' | 'mixed'; flips to 'mixed' once two charges disagree. See ADR-0061.
    val costSource = varchar("cost_source", 16).default("cli")
    val artifactsDir = text("artifacts_dir").nullable()

    val history = text("history")
    val executions = text("executions")
    val runnerState = text("runner_state").nullable()
    val owner = text("owner").nullable()

    // W3C trace id of this run's root span; null when none. Outlives the artifacts, so a purged run still links to its trace.
    val traceId = varchar("trace_id", 32).nullable()

    // Id of the run that caused this one; null at the root. Not a foreign key: the parent may be evicted or purged first. See ADR-0058.
    val parentProcessId = integerWithoutRangeCheck("parent_process_id").nullable()

    // First run of this run's flow, written once at INSERT as `parent.rootProcessId ?: parent.id`;
    // null at the root. Denormalized so a flow is one paginated read. Not a foreign key. See ADR-0058.
    val rootProcessId = integerWithoutRangeCheck("root_process_id").nullable()

    // Wall-clock second the row was inserted, in the INSERT's own transaction — that is what makes it
    // the cooldown's "last run started" (the first `history` entry is written later). Null before v13. See ADR-0051.
    val createdAt = long("created_at").nullable()

    // Wall-clock second the row reached a terminal status, written by the same UPDATE that sets it, so
    // `MAX(ended_at)` is an exact "last failed". Null while active and before v14. See ADR-0051.
    val endedAt = long("ended_at").nullable()

    // The subject this run's cooldown windows are counted per: `cooldown.key` rendered at start, in the
    // INSERT's transaction. Null when no key and on pre-v31 rows (no backfill); a keyed cooldown ignores null rows. See ADR-0106.
    val cooldownKey = text("cooldown_key").nullable()

    // envNames declared `secret: true` at run time. Redaction reads this, not the current definition:
    // records outlive their workspace, so a later `secret: false` would expose the historical value. Null before v15. See ADR-0052.
    val secretInputKeys = text("secret_input_keys").nullable()

    // Artifact globs (JSON list) declared to survive the workspace purge, captured at terminal status. See ADR-0053.
    val retainArtifacts = text("retain_artifacts").nullable()

    // "Workspace sweep already ran" marker for rows that keep artifacts_dir set; a null artifacts_dir marks rows with nothing retained. See ADR-0053.
    val workspacePurgedAt = long("workspace_purged_at").nullable()

    val retainedTruncated = registerColumn("retained_truncated", IntegerBooleanColumnType()).nullable()

    // Why the run ended FAILED before any command started: a fixed-vocabulary sentence from typed
    // fields, never a raw exception message, redacted before write. Written once, only while FAILED with no step. See ADR-0098.
    val failureReason = text("failure_reason").nullable()

    // The lock holder that blocked this run; not a foreign key, like the other run links; served only to a caller who may see that run.
    val failureReasonRunId = integerWithoutRangeCheck("failure_reason_run_id").nullable()

    // What a graceful stop did, as a JSON blob written once; null for a run no graceful stop ended, and before v31. See ADR-0108.
    val stop = text("stop").nullable()

    // The columns the record-level reads (the two purge sweeps and `costByTag`) use and nothing else:
    // `history`, `executions` and `runner_state` are multi-MiB and none of those paths serves them.
    val recordReadColumns: List<Expression<*>> =
        listOf(
            id,
            status,
            agentName,
            currentCommand,
            currentCommandStart,
            purgeAt,
            recordPurgeAt,
            costUsd,
            costSource,
            artifactsDir,
            owner,
            traceId,
            parentProcessId,
            rootProcessId,
            secretInputKeys,
            retainArtifacts,
            workspacePurgedAt,
            retainedTruncated,
            failureReason,
            failureReasonRunId,
        )

    // One per list filter of GET /status/all plus `owner`, so COUNT(*) behind `total` never scans; migration v23 creates the same five.
    init {
        index("processes_agent_name", false, agentName)
        index("processes_status", false, status)
        index("processes_parent_process_id", false, parentProcessId)
        index("processes_root_process_id", false, rootProcessId)
        index("processes_owner", false, owner)
        // v24: the shared-workspace check before a purge or eviction deletes a directory.
        index("processes_artifacts_dir", false, artifactsDir)
        // v24: the two purge sweeps. The partial index holds only rows still waiting; the legacy index covers pre-v12 rows.
        index("processes_workspace_purge", false, status, purgeAt, filterCondition = {
            artifactsDir.isNotNull() and workspacePurgedAt.isNull()
        })
        index("processes_record_purge", false, status, recordPurgeAt)
        index("processes_legacy_record_purge", false, status, purgeAt, filterCondition = { recordPurgeAt.isNull() })
        // v24: cooldown.afterFailure's latest-failure lookup, run under the write lock at run start.
        index("processes_agent_status_ended", false, agentName, status, endedAt)
        // v31: the two cooldown lookups scoped to one `cooldown.key`. The first is served by rowid order;
        // the second adds `status = ?` and orders by ended_at, so both columns are in the key.
        index("processes_agent_cooldown_key", false, agentName, cooldownKey)
        index("processes_agent_cooldown_status_ended", false, agentName, cooldownKey, status, endedAt)
    }

    override val primaryKey = PrimaryKey(id)
}

// A run's subject tags, one row per (process, tag), written and deleted co-transactionally with the
// process row. Not a foreign key; child runs do not inherit parent tags. See ADR-0060.
internal object ProcessTags : Table("process_tags") {
    val processId = integer("process_id")

    // The whole `key:value` tag as stored and matched; exact-equality filtering, so `(tag, process_id)` is the driving index.
    val tag = text("tag")

    init {
        index(false, tag, processId)
    }

    override val primaryKey = PrimaryKey(processId, tag)
}

// A run's write-once `input_params` as JSON text, in its own row since v25 so per-step UPDATEs
// never rewrite it. Not a foreign key; deleted co-transactionally with the process row. See ADR-0020.
internal object ProcessInputs : Table("process_inputs") {
    val processId = integer("process_id")
    val inputParams = text("input_params")

    override val primaryKey = PrimaryKey(processId)
}

internal object Questions : Table("questions") {
    val id = varchar("id", 64)
    val processId = integer("process_id")
    val agentName = varchar("agent_name", 255)
    val text = text("text")

    // Named optionsJson because Exposed 1.3 added Table.options; the column name stays "options".
    val optionsJson = text("options")
    val status = varchar("status", 16)
    val answer = text("answer").nullable()
    val createdAt = long("created_at")
    val answeredAt = long("answered_at").nullable()

    // 1 only for ask-and-dispatch: question-teardown queries skip these rows, so a dispatch outlives its asking process. See ADR-0021.
    val dispatchBound = integerWithoutRangeCheck("dispatch_bound").default(0)

    // v24: the per-process open-question checks and the open-question counts on process-list reads; v28: the read-grant arm of paged GET /questions.
    init {
        index("questions_status_dispatch_bound", false, status, dispatchBound, processId)
        index("questions_process_dispatch_bound", false, processId, dispatchBound)
        index("questions_agent_name", false, agentName)
    }

    override val primaryKey = PrimaryKey(id)
}

// The per-run inbox: operator messages a running run takes through a hook or the MCP tool. Not a foreign key. See ADR-0097.
internal object RunMessages : Table("run_messages") {
    val id = varchar("id", 64)
    val processId = integer("process_id")
    val text = text("text")
    val status = varchar("status", 16)
    val sender = varchar("sender", 255).nullable()
    val createdAt = long("created_at")
    val deliveredAt = long("delivered_at").nullable()

    init {
        index("run_messages_process_status", false, processId, status)
    }

    override val primaryKey = PrimaryKey(id)
}

internal object Dispatches : Table("dispatches"), FiredRecordTable {
    override val id = integer("id").autoIncrement()
    val questionId = varchar("question_id", 64).uniqueIndex()
    override val status = varchar("status", 16)
    override val agentName = varchar("agent_name", 255)
    val answerParam = varchar("answer_param", 255)
    val params = text("params")
    val originProcessId = integer("origin_process_id")
    val originAgentName = varchar("origin_agent_name", 255)
    override val expiresAt = long("expires_at").nullable()
    override val createdAt = long("created_at")
    val dispatchedAt = long("dispatched_at").nullable()
    val dispatchedProcessId = integer("dispatched_process_id").nullable()
    val error = text("error").nullable()
    override val owner = text("owner").nullable()

    // envNames the TARGET agent declared `secret: true` at registration, read at fire time so a later
    // edit cannot turn a stored value into a cleartext trace tag or an unredacted API field. Null before v16. See ADR-0052.
    val secretInputKeys = text("secret_input_keys").nullable()

    // paramNames the TARGET declared `secret: true` at registration; `params` is keyed by paramName, so
    // this is what `redactStoredParams` matches directly. Null before v17. See ADR-0052.
    val secretParamNames = text("secret_param_names").nullable()

    // SINGLE (one question) or GROUP (N answered independently, fired once after the last). A GROUP row's
    // question_id is a synthetic sentinel naming no `questions` row; its set is DispatchGroupQuestions. See ADR-0109.
    val kind = varchar("kind", 16).default(DispatchKind.SINGLE.name)

    // v24: the PENDING cap, the expiry sweep and the DISPATCHING recovery at startup; v26: the settled-row prune and the list filters.
    init {
        index("dispatches_status_expires", false, status, expiresAt)
        index("dispatches_status_created", false, status, createdAt)
        index("dispatches_agent_name", false, agentName)
        index("dispatches_owner", false, owner)
    }

    override val primaryKey = PrimaryKey(id)
}

// The ordered question set of a [DispatchKind.GROUP] dispatch: one row per member question,
// `position` 0-based, the aggregation order. SINGLE dispatches have no rows here. See ADR-0109.
internal object DispatchGroupQuestions : Table("dispatch_group_questions") {
    val dispatchId = integer("dispatch_id")
    val questionId = varchar("question_id", 64)
    val position = integer("position")

    // The member→group lookup every answer takes, never by scanning the table.
    init {
        index("dispatch_group_questions_question_id", false, questionId)
    }

    override val primaryKey = PrimaryKey(dispatchId, position)
}

internal object ResultTokens : Table("result_tokens") {
    // Capability token scoping a read-by-reference fetch to one (watchedProcessId, artifact path).
    // Reusable within its TTL; independent of the continuation row's lifetime. See ADR-0025.
    val token = varchar("token", 64)
    val watchedProcessId = integer("watched_process_id")
    val path = text("path")

    // JSON-encoded ResultEnvParam applied to the artifact at fetch time; null = stream raw.
    val resultTransformer = text("result_transformer").nullable()
    val sizeBytes = long("size_bytes")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").nullable()

    // Agent the token is minted for; the fetch endpoint redeems it only for a caller running this agent (audience binding). See ADR-0025.
    val consumerAgent = varchar("consumer_agent", 255)

    override val primaryKey = PrimaryKey(token)

    // v34: the expiry sweep's due-row read and its delete. See ADR-0025.
    init {
        index("result_tokens_expires_at", false, expiresAt)
    }
}

internal object Continuations : Table("continuations"), FiredRecordTable {
    override val id = integer("id").autoIncrement()
    val watchedProcessId = integer("watched_process_id")
    override val status = varchar("status", 16)
    override val agentName = varchar("agent_name", 255)
    val resumeParam = varchar("resume_param", 255)
    val params = text("params")

    // JSON-encoded ResultEnvParam extracting a value from the watched process's stdout; null = pass the raw result.
    val resultTransformer = text("result_transformer").nullable()

    // envNames the TARGET agent declared `secret: true` at registration, read at fire time so a later
    // edit cannot turn a stored value into a cleartext trace tag or an unredacted API field. Null before v16. See ADR-0052.
    val secretInputKeys = text("secret_input_keys").nullable()

    // paramNames the TARGET declared `secret: true` at registration; `params` is keyed by paramName, so
    // this is what `redactStoredParams` matches directly. Null before v17. See ADR-0052.
    val secretParamNames = text("secret_param_names").nullable()
    val originProcessId = integer("origin_process_id")
    val originAgentName = varchar("origin_agent_name", 255)
    override val expiresAt = long("expires_at").nullable()
    override val createdAt = long("created_at")
    val firedAt = long("fired_at").nullable()
    val firedProcessId = integer("fired_process_id").nullable()
    val error = text("error").nullable()

    // SINGLE or JOIN. JOIN rows set watched_process_id = 0 (sentinel) and carry their watch set in
    // continuation_watches. See ADR-0026.
    val kind = varchar("kind", 16).default(ContinuationKind.SINGLE.name)
    override val owner = text("owner").nullable()

    // Transient-failure retry: a fire that failed for a reason that may clear (capacity, cost cap)
    // returns the row to PENDING, attempt counted, next attempt scheduled. A null next_attempt_at is never retried. See ADR-0023.
    val fireAttempts = integerWithoutRangeCheck("fire_attempts").default(0)
    val nextAttemptAt = long("next_attempt_at").nullable()

    // `params[HMR_LG_THREAD]` copied out at INSERT so a LangGraph run's `subs` reads its thread's
    // rows by index. Agent-supplied: only ever compared for equality with a bound parameter.
    val threadId = text("thread_id").nullable()

    // v24: the watched-process lookup, the PENDING cap and the expiry/retry sweeps. v26: the
    // settled-row prune and the list filters. v27: the LangGraph `subs` thread lookup.
    init {
        index("continuations_watched_status", false, watchedProcessId, status)
        index("continuations_status_expires", false, status, expiresAt)
        index("continuations_status_created", false, status, createdAt)
        index("continuations_agent_name", false, agentName)
        index("continuations_owner", false, owner)
        index("continuations_thread_id", false, threadId)
    }

    override val primaryKey = PrimaryKey(id)
}

// The watch set of a [ContinuationKind.JOIN] continuation: one row per watched sub-process,
// carrying the interrupt id its result is keyed under and the per-sub transformer. See ADR-0026.
internal object ContinuationWatches : Table("continuation_watches") {
    val continuationId = integer("continuation_id").index()
    val watchedProcessId = integer("watched_process_id").index()
    val interruptId = varchar("interrupt_id", 255)

    // JSON-encoded ResultEnvParam applied to this sub's stdout; null = raw result.
    val resultTransformer = text("result_transformer").nullable()

    override val primaryKey = PrimaryKey(continuationId, watchedProcessId)
}

// A durable agent-level status hook: written in the same transaction as the status write, claimed
// (PENDING→DISPATCHING) before the run starts, settled. No TTL: `expires_at` is always null. See ADR-0107.
internal object PendingHooks : Table("pending_hooks"), FiredRecordTable {
    override val id = integer("id").autoIncrement()
    val processId = integer("process_id")
    val declaringAgent = varchar("declaring_agent", 255)
    override val status = varchar("status", 16)
    override val agentName = varchar("agent_name", 255)
    override val owner = text("owner").nullable()
    override val createdAt = long("created_at")
    override val expiresAt = long("expires_at").nullable()

    val triggeringStatus = varchar("triggering_status", 16)

    val fireAttempts = integer("fire_attempts").default(0)
    val nextAttemptAt = long("next_attempt_at").nullable()
    val firedProcessId = integer("fired_process_id").nullable()
    val firedAt = long("fired_at").nullable()
    val error = text("error").nullable()

    // v31: the due-retry sweep, the settled-row prune and the per-run lookup the fire path and boot replay use.
    init {
        index("pending_hooks_status_next_attempt", false, status, nextAttemptAt)
        index("pending_hooks_process_id", false, processId)
        index("pending_hooks_status_created", false, status, createdAt)
    }

    override val primaryKey = PrimaryKey(id)
}

// Append-only spend ledger: one row per accounted attempt, not only successful commands. Rows are
// never pruned — all-time totals depend on them. Months are bucketed in UTC. See ADR-0035, ADR-0061.
internal object CostEntries : Table("cost_entries") {
    val id = long("id").autoIncrement()
    val agentName = varchar("agent_name", 255)
    val timestampSec = long("timestamp_sec")
    val costUsd = double("cost_usd")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, agentName, timestampSec)
        index(false, timestampSec)
    }
}

// Durable server-instance metadata: the stable per-instance id that scopes the Kubernetes startup
// sweep, persisted so the sweep reaps this instance's own orphans across a restart.
internal object ServerMeta : Table("server_meta") {
    val key = varchar("key", 64)
    val value = text("value")

    override val primaryKey = PrimaryKey(key)
}
