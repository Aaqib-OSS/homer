package homer.app.services.persistence

import homer.app.EnvConstants
import homer.app.models.ResultEnvParam
import homer.app.services.Continuation
import homer.app.services.ContinuationKind
import homer.app.services.ContinuationStatus
import homer.app.services.JoinWatch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.AbstractQuery
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.union
import org.jetbrains.exposed.v1.jdbc.update

/**
 * `continuation_watches`' implicit SQLite `rowid`, the only column that follows registration order, as a raw
 * expression: declared as a `Table` column, `rowid` would be created as a real column of its own.
 */
private object ContinuationWatchesRowId : Expression<Long>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("rowid")
    }
}

/**
 * Durable access to completion-continuation records. SQLite is authoritative
 * (ADR-0020); a continuation parks until its watched process reaches a terminal
 * state, at which point it fires a target run. See ADR-0023.
 */
class ContinuationRepository(
    private val db: DatabaseService,
    nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val records = FiredRecords(db, Continuations, ContinuationStatus.entries.map { it.name }, nowSec)

    suspend fun create(
        continuation: Continuation,
        maxPending: Int,
    ): Int? =
        db.query {
            if (records.pendingCapReachedTx(maxPending)) return@query null
            insertTx(continuation, continuation.watchedProcessId, continuation.resultTransformer, ContinuationKind.SINGLE)
        }

    /**
     * Inserts a `kind = JOIN` continuation and its [watches] rows in one transaction; the sentinel
     * `watched_process_id = 0` keeps the single-watch trigger off it (ADR-0026). Returns the new id,
     * or null at the PENDING cap, with nothing inserted.
     */
    suspend fun createJoin(
        continuation: Continuation,
        watches: List<JoinWatch>,
        maxPending: Int,
    ): Int? =
        db.query {
            if (records.pendingCapReachedTx(maxPending)) return@query null
            val id = insertTx(continuation, watchedProcessId = 0, resultTransformer = null, ContinuationKind.JOIN)
            ContinuationWatches.batchInsert(watches, shouldReturnGeneratedValues = false) { w ->
                this[ContinuationWatches.continuationId] = id
                this[ContinuationWatches.watchedProcessId] = w.watchedProcessId
                this[ContinuationWatches.interruptId] = w.interruptId
                this[ContinuationWatches.resultTransformer] =
                    w.resultTransformer?.let { t -> columnJson.encodeToString(ResultEnvParam.serializer(), t) }
            }
            id
        }

    /** INSERTs [continuation]'s row with the given watch columns and returns its id; a JOIN's are the sentinels. */
    private fun insertTx(
        continuation: Continuation,
        watchedProcessId: Int,
        resultTransformer: ResultEnvParam?,
        kind: ContinuationKind,
    ): Int =
        Continuations.insert {
            it[Continuations.watchedProcessId] = watchedProcessId
            it[status] = continuation.status.name
            it[agentName] = continuation.targetAgentName
            it[resumeParam] = continuation.resumeParam
            it[params] = columnJson.encodeToString(stringMapSerializer, continuation.params)
            it[Continuations.resultTransformer] =
                resultTransformer?.let { t -> columnJson.encodeToString(ResultEnvParam.serializer(), t) }
            it[originProcessId] = continuation.originProcessId
            it[originAgentName] = continuation.originAgentName
            it[expiresAt] = continuation.expiresAt
            it[createdAt] = continuation.createdAt
            it[Continuations.kind] = kind.name
            it[owner] = continuation.owner
            it[secretInputKeys] = continuation.secretInputKeys?.let { encodeKeys(it) }
            it[secretParamNames] = continuation.secretParamNames?.let { encodeKeys(it) }
            it[threadId] = continuation.params[EnvConstants.LG_THREAD]
        }[Continuations.id]

    suspend fun get(id: Int): Continuation? =
        db.read {
            Continuations
                .selectAll()
                .where { Continuations.id eq id }
                .toList()
                .toContinuations()
                .singleOrNull()
        }

    /** PENDING continuation(s) watching [processId]. The trigger fires each in turn. */
    suspend fun findByWatchedProcessId(processId: Int): List<Continuation> =
        db.read {
            Continuations
                .selectAll()
                .where {
                    (Continuations.watchedProcessId eq processId) and
                        (Continuations.status eq ContinuationStatus.PENDING.name)
                }.toList()
                .toContinuations()
        }

    /**
     * PENDING JOIN continuations whose watch set contains [processId]. The terminal hook checks
     * each: once all of a join's watched processes are terminal it fires once. See ADR-0026.
     */
    suspend fun findJoinsByWatchedProcessId(processId: Int): List<Continuation> =
        db.read {
            val ids =
                ContinuationWatches
                    .selectAll()
                    .where { ContinuationWatches.watchedProcessId eq processId }
                    .map { it[ContinuationWatches.continuationId] }
                    .distinct()
            if (ids.isEmpty()) return@read emptyList()
            Continuations
                .selectAll()
                .where {
                    (Continuations.id inList ids) and
                        (Continuations.status eq ContinuationStatus.PENDING.name)
                }.toList()
                .toContinuations()
        }

    /**
     * Every continuation of any status that [owner] registered for LangGraph thread [threadId], in id
     * order, served by `continuations_thread_id` with no sort. [threadId] is agent-supplied and only
     * bound as an equality parameter; a null [owner] matches only unowned rows. See ADR-0044.
     */
    suspend fun byThread(
        threadId: String,
        owner: String?,
    ): List<Continuation> =
        db.read {
            Continuations
                .selectAll()
                .where {
                    (Continuations.threadId eq threadId) and
                        (if (owner == null) Continuations.owner.isNull() else Continuations.owner eq owner)
                }.orderBy(Continuations.id to SortOrder.ASC)
                .toList()
                .toContinuations()
        }

    /** Every continuation in [status], through `continuations_status_created`; an unfiltered list is [page]'s job. */
    suspend fun list(status: ContinuationStatus): List<Continuation> =
        db.read {
            Continuations
                .selectAll()
                .where { Continuations.status eq status.name }
                .toList()
                .toContinuations()
        }

    /** One page of continuations, newest first, and the count of all rows matching the filters: [FiredRecords.page]. */
    suspend fun page(
        status: ContinuationStatus?,
        agentName: String?,
        visibleOwner: String?,
        visibleAgents: Set<String>?,
        offset: Int,
        limit: Int,
    ): RecordPage<Continuation> =
        records.page(status?.name, agentName, visibleOwner, visibleAgents, offset, limit) { rows -> rows.toContinuations() }

    /**
     * Deletes settled continuations (every status but PENDING and DISPATCHING) created before
     * [cutoff], with their `continuation_watches` rows, in batches of at most [batchSize]
     * ([FiredRecords.pruneSettled]). Returns the number deleted. See ADR-0023.
     */
    suspend fun pruneSettled(
        cutoff: Long,
        batchSize: Int = PRUNE_BATCH_SIZE,
    ): Int =
        records.pruneSettled(cutoff, batchSize, emptyList()) { batch ->
            val ids = batch.map { it[Continuations.id] }
            ContinuationWatches.deleteWhere { ContinuationWatches.continuationId inList ids }
        }

    /**
     * Claims a PENDING continuation for firing (PENDING→DISPATCHING). False if it was no longer
     * PENDING or its TTL has lapsed, swept or not (ADR-0021).
     */
    suspend fun claim(id: Int): Boolean = records.claim(id)

    suspend fun markDispatched(
        id: Int,
        processId: Int,
        at: Long,
    ): Boolean =
        transition(id, ContinuationStatus.DISPATCHING) {
            it[Continuations.status] = ContinuationStatus.DISPATCHED.name
            it[Continuations.firedProcessId] = processId
            it[Continuations.firedAt] = at
        }

    /**
     * Returns a DISPATCHING continuation to PENDING, counting the attempt, recording [error] and
     * scheduling [nextAttemptAt]: a transient fire failure's path, where [markFailed] is a
     * permanent one's (ADR-0023).
     */
    suspend fun releaseForRetry(
        id: Int,
        error: String,
        attempts: Int,
        nextAttemptAt: Long,
    ): Boolean =
        transition(id, ContinuationStatus.DISPATCHING) {
            it[Continuations.status] = ContinuationStatus.PENDING.name
            it[Continuations.error] = error
            it[Continuations.fireAttempts] = attempts
            it[Continuations.nextAttemptAt] = nextAttemptAt
        }

    /**
     * PENDING continuations with a due retry (`next_attempt_at <= now`) that have not
     * lapsed. Rows never released for retry have a null `next_attempt_at` and are skipped,
     * so the sweep re-fires only what actually failed transiently.
     */
    suspend fun findDueRetries(now: Long): List<Continuation> =
        db.read {
            Continuations
                .selectAll()
                .where {
                    (Continuations.status eq ContinuationStatus.PENDING.name) and
                        (Continuations.nextAttemptAt.isNotNull()) and
                        (Continuations.nextAttemptAt lessEq now) and
                        (Continuations.expiresAt.isNull() or (Continuations.expiresAt greater now))
                }.toList()
                .toContinuations()
        }

    suspend fun markFailed(
        id: Int,
        error: String,
        at: Long,
    ): Boolean =
        transition(id, ContinuationStatus.DISPATCHING) {
            it[Continuations.status] = ContinuationStatus.FAILED.name
            it[Continuations.error] = error
            it[Continuations.firedAt] = at
        }

    /** Cancels a PENDING continuation (PENDING→CANCELLED). Null if it was no longer PENDING. */
    suspend fun cancel(id: Int): Continuation? =
        db.query {
            val row = Continuations.selectAll().where { Continuations.id eq id }.singleOrNull() ?: return@query null
            val continuation = listOf(row).toContinuations().single()
            if (continuation.status != ContinuationStatus.PENDING) return@query null
            Continuations.update({ (Continuations.id eq id) and (Continuations.status eq ContinuationStatus.PENDING.name) }) {
                it[status] = ContinuationStatus.CANCELLED.name
            }
            continuation.copy(status = ContinuationStatus.CANCELLED)
        }

    /**
     * Moves the PENDING continuations lapsed at [now] to EXPIRED and returns them. An idle tick's
     * `read { }` pre-check takes no write lock, and the write re-applies the predicate, so a row
     * claimed in between stays. Both are served by `continuations_status_expires`.
     */
    suspend fun expirePending(now: Long): List<Continuation> {
        if (!records.anyDue(records.lapsedAt(now))) return emptyList()
        return db.query {
            val lapsed =
                Continuations
                    .selectAll()
                    .where { records.lapsedAt(now) }
                    .toList()
                    .toContinuations()
            // One UPDATE over the same predicate, not one per row: inside this
            // write transaction it matches exactly the rows just read.
            Continuations.update({ records.lapsedAt(now) }) { it[Continuations.status] = ContinuationStatus.EXPIRED.name }
            lapsed.map { it.copy(status = ContinuationStatus.EXPIRED) }
        }
    }

    /**
     * Marks all DISPATCHING continuations (interrupted mid-fire by a restart) FAILED, so the
     * crash window between run-start and markDispatched cannot cause a duplicate re-fire. Returns them.
     */
    suspend fun failOrphanedDispatching(at: Long): List<Continuation> =
        db.query {
            val orphaned =
                Continuations
                    .selectAll()
                    .where { Continuations.status eq ContinuationStatus.DISPATCHING.name }
                    .toList()
                    .toContinuations()
            Continuations.update({ Continuations.status eq ContinuationStatus.DISPATCHING.name }) {
                it[Continuations.status] = ContinuationStatus.FAILED.name
                it[Continuations.error] = "Interrupted during dispatch by a server restart"
                it[Continuations.firedAt] = at
            }
            orphaned.map {
                it.copy(
                    status = ContinuationStatus.FAILED,
                    error = "Interrupted during dispatch by a server restart",
                    firedAt = at,
                )
            }
        }

    private suspend fun transition(
        id: Int,
        from: ContinuationStatus,
        body: (UpdateStatement) -> Unit,
    ): Boolean =
        db.query {
            Continuations.update(
                { (Continuations.id eq id) and (Continuations.status eq from.name) },
            ) { body(it) } > 0
        }

    /**
     * Maps continuation rows to [Continuation]s, loading every JOIN row's watches in one batched
     * read, never one per row. Runs inside the caller's `read { }` or `query { }` transaction.
     */
    private fun List<ResultRow>.toContinuations(): List<Continuation> {
        val joinIds =
            mapNotNull { row ->
                row[Continuations.id].takeIf { ContinuationKind.valueOf(row[Continuations.kind]) == ContinuationKind.JOIN }
            }
        val watchesById = watchesByContinuationTx(joinIds)
        return map { it.toContinuation(watchesById[it[Continuations.id]] ?: emptyList()) }
    }

    /**
     * The watches of each JOIN continuation in [ids], each list in registration order (`rowid`),
     * in chunked `IN` reads that `continuation_watches_continuation_id` serves with no sort.
     */
    private fun watchesByContinuationTx(ids: List<Int>): Map<Int, List<JoinWatch>> {
        if (ids.isEmpty()) return emptyMap()
        val byId = mutableMapOf<Int, MutableList<JoinWatch>>()
        for (chunk in ids.chunked(SQLITE_PARAM_CHUNK)) {
            ContinuationWatches
                .selectAll()
                .where { ContinuationWatches.continuationId inList chunk }
                .orderBy(ContinuationWatches.continuationId to SortOrder.ASC, ContinuationWatchesRowId to SortOrder.ASC)
                .forEach { w ->
                    byId.getOrPut(w[ContinuationWatches.continuationId]) { mutableListOf() } +=
                        JoinWatch(
                            watchedProcessId = w[ContinuationWatches.watchedProcessId],
                            interruptId = w[ContinuationWatches.interruptId],
                            resultTransformer =
                                w[ContinuationWatches.resultTransformer]
                                    ?.let { columnJson.decodeFromString(ResultEnvParam.serializer(), it) },
                        )
                }
        }
        return byId
    }

    private fun ResultRow.toContinuation(watches: List<JoinWatch>): Continuation {
        val kind = ContinuationKind.valueOf(this[Continuations.kind])
        val id = this[Continuations.id]
        val secretKeys: Set<String>? =
            this[Continuations.secretInputKeys]?.let { decodeKeys(it) }
        val secretParamNamesValue: Set<String>? =
            this[Continuations.secretParamNames]?.let { decodeKeys(it) }
        return Continuation(
            id = id,
            secretInputKeys = secretKeys,
            secretParamNames = secretParamNamesValue,
            watchedProcessId = this[Continuations.watchedProcessId],
            status = ContinuationStatus.valueOf(this[Continuations.status]),
            targetAgentName = this[Continuations.agentName],
            params = columnJson.decodeFromString(stringMapSerializer, this[Continuations.params]),
            resumeParam = this[Continuations.resumeParam],
            resultTransformer =
                this[Continuations.resultTransformer]?.let { columnJson.decodeFromString(ResultEnvParam.serializer(), it) },
            originProcessId = this[Continuations.originProcessId],
            originAgentName = this[Continuations.originAgentName],
            expiresAt = this[Continuations.expiresAt],
            createdAt = this[Continuations.createdAt],
            fireAttempts = this[Continuations.fireAttempts],
            firedAt = this[Continuations.firedAt],
            firedProcessId = this[Continuations.firedProcessId],
            error = this[Continuations.error],
            kind = kind,
            watches = watches,
            owner = this[Continuations.owner],
        )
    }

    private companion object {
        /** Rows per prune transaction. */
        const val PRUNE_BATCH_SIZE = 500
    }
}
