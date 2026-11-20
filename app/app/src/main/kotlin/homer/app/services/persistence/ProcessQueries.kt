package homer.app.services.persistence

import homer.app.models.ProcessInfo
import homer.app.models.ProcessListQuery
import homer.app.models.ProcessStatus
import homer.app.models.SubRunProgress
import homer.app.services.ProcessManager.CostByTagResult
import homer.app.services.ProcessManager.ProcessListResult
import homer.app.services.QuestionsService.QuestionStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

// The read-only queries over many process rows: the paged, filtered list behind `GET /status/all`,
// the cost-by-tag fold, and the batched id reads. Every one runs on the reader handle (`read { }`).

/**
 * One page of [listQuery]'s rows. Fails closed on a tag filter without `screenTags` and on a
 * `rootProcessId` without `canSeeRoot` (see [ProcessListQuery]).
 */
suspend fun ProcessRepository.getAllProcesses(listQuery: ProcessListQuery): ProcessListResult {
    requireListGuards(listQuery)
    // Repeated ?tag= values AND in SQL ([carriesAllTags]); no match is an empty page, never an
    // error, so "no such tag" and "none you can see" read alike (ADR-0060).
    val dedupedTags = listQuery.tags.distinct()
    val order = Processes.id to if (listQuery.orderAsc) SortOrder.ASC else SortOrder.DESC
    val offset = listQuery.offset
    val limit = listQuery.limit
    val canSeeRoot = listQuery.canSeeRoot
    return read {
        // An invisible or missing root reads exactly like an empty flow, never like "no filter"
        // (CWE-200, ADR-0058).
        val rootProcessId = listQuery.rootProcessId
        if (rootProcessId != null) {
            val canSee = checkNotNull(canSeeRoot) { "requireListGuards admits no rootProcessId without canSeeRoot" }
            if (rootProcessId !in visibleRootIdsTx(listOf(rootProcessId), canSee)) {
                return@read ProcessListResult(processes = emptyList(), total = 0)
            }
        }
        val query =
            Processes.selectAll().where {
                withAccessFilter(rowFilter(listQuery), dedupedTags, listQuery.visibleOwner, listQuery.visibleAgents)
            }
        // The display-redaction screen runs per row before a tag match counts: the SQL predicate
        // matches unredacted values (CWE-203, ADR-0060).
        val screenTags = listQuery.screenTags
        if (dedupedTags.isNotEmpty() && screenTags != null) {
            return@read screenedPageTx(query, order, dedupedTags, screenTags, offset, limit, canSeeRoot)
        }
        // Planned on the v23 indexes; a new predicate here needs one too (DatabaseMigrationTest pins the plans).
        val total = query.count().toInt()
        // offset is caller-chosen and unbounded: past the end the page is empty, so skip the
        // walk-and-discard through the whole table.
        if (offset >= total) return@read pageResultTx(emptyList(), total, canSeeRoot)
        val rows =
            query
                .orderBy(order)
                .limit(limit)
                .offset(offset.toLong())
                .toList()
        pageResultTx(rows.toProcessInfosTx(jobRegistry).toList(), total, canSeeRoot)
    }
}

/**
 * [getAllProcesses]'s fail-closed argument checks: a tag filter without a screen would be a `?tag=` oracle past
 * the display redaction (ADR-0060), and an ungated `?rootProcessId=` an existence oracle for a hidden root
 * (CWE-200, ADR-0058).
 */
private fun requireListGuards(listQuery: ProcessListQuery) {
    if (listQuery.tags.distinct().isNotEmpty() && listQuery.screenTags == null) {
        throw IllegalArgumentException("screenTags is required whenever tags is non-empty")
    }
    if (listQuery.rootProcessId != null && listQuery.canSeeRoot == null) {
        throw IllegalArgumentException("canSeeRoot is required whenever rootProcessId is set")
    }
}

/** [getAllProcesses]'s column predicates, ANDed onto `TRUE` in this order. */
private fun rowFilter(listQuery: ProcessListQuery): Op<Boolean> {
    val filterStatuses = listQuery.filterStatuses
    val agentName = listQuery.agentName
    val parentProcessId = listQuery.parentProcessId
    val rootProcessId = listQuery.rootProcessId
    var cond: Op<Boolean> = Op.TRUE
    if (filterStatuses != null) {
        cond = cond and (Processes.status inList filterStatuses.map { it.name })
    }
    if (agentName != null) {
        cond = cond and (Processes.agentName eq agentName)
    }
    if (parentProcessId != null) {
        cond = cond and (Processes.parentProcessId eq parentProcessId)
    }
    if (rootProcessId != null) {
        cond = cond and (Processes.rootProcessId eq rootProcessId)
    }
    if (listQuery.rootsOnly) {
        cond = cond and Processes.parentProcessId.isNull()
    }
    return cond
}

/** [cond] with [getAllProcesses]'s tag match and visibility rule ANDed on, in that order. */
private fun withAccessFilter(
    cond: Op<Boolean>,
    dedupedTags: List<String>,
    visibleOwner: String?,
    visibleAgents: Set<String>?,
): Op<Boolean> {
    var filtered = cond
    if (dedupedTags.isNotEmpty()) {
        // A SQL subquery, never an app-side id list (CWE-400). Only an admin gets the index-driven
        // form, whose timing would count runs a scoped caller cannot see (ADR-0060).
        filtered = filtered and carriesAllTags(dedupedTags, indexDriven = visibleAgents == null)
    }
    // null visibleAgents = admin = no visibility filter; an empty set is "owner only".
    if (visibleAgents != null) {
        filtered = filtered and visibleTo(visibleOwner, visibleAgents)
    }
    return filtered
}

/**
 * The tag-screened page: [query]'s candidates, newest or oldest first by [order], screened per row
 * by [screenTags] before paging, so its total counts only rows whose tags survive redaction.
 */
private fun ProcessRepository.screenedPageTx(
    query: Query,
    order: Pair<Column<Int>, SortOrder>,
    dedupedTags: List<String>,
    screenTags: (ProcessInfo) -> List<String>,
    offset: Int,
    limit: Int,
    canSeeRoot: ((owner: String?, agentName: String) -> Boolean)?,
): ProcessListResult {
    // Bounded in SQL, not only by `limit`, so the candidate set is never fetched whole before the
    // screen; tags arrive in one batched IN read, never per row (CWE-400, ADR-0060).
    val rows =
        query
            .orderBy(order)
            .limit(MAX_TAG_SCREENED_ROWS)
            .toList()
    val ordered = rows.toProcessInfosTx(jobRegistry).filter { info -> dedupedTags.all { it in screenTags(info) } }.toList()
    val screenedTotal = ordered.size
    val page = ordered.drop(offset).take(limit)
    return pageResultTx(page, screenedTotal, canSeeRoot)
}

/**
 * Rows [visibleOwner] owns or of one of [visibleAgents]. With no agents it is the owner clause
 * alone: `owner = ? OR 0` keeps SQLite off `processes_owner`, and the page would scan every run.
 */
private fun visibleTo(
    visibleOwner: String?,
    visibleAgents: Collection<String>,
): Op<Boolean> {
    val ownerClause: Op<Boolean> = if (visibleOwner != null) (Processes.owner eq visibleOwner) else Op.FALSE
    return if (visibleAgents.isEmpty()) ownerClause else ownerClause or (Processes.agentName inList visibleAgents)
}

// The page's open-question counts and root visibility, read in the list's own
// transaction as IN reads over the page's ids: one snapshot, no per-row query.
private fun pageResultTx(
    page: List<ProcessInfo>,
    total: Int,
    canSeeRoot: ((owner: String?, agentName: String) -> Boolean)?,
): ProcessListResult =
    ProcessListResult(
        processes = page,
        total = total,
        openQuestions = openQuestionCountsTx(page.map { it.id }),
        visibleRootIds = canSeeRoot?.let { visibleRootIdsTx(page.mapNotNull { it.rootProcessId }.distinct(), it) } ?: emptySet(),
    )

// Served by questions_status_dispatch_bound (status, dispatch_bound, process_id), covering.
private fun openQuestionCountsTx(ids: List<Int>): Map<Int, Int> {
    if (ids.isEmpty()) return emptyMap()
    val counts = mutableMapOf<Int, Int>()
    for (chunk in ids.chunked(SQLITE_PARAM_CHUNK)) {
        Questions
            .select(Questions.processId)
            .where {
                (Questions.status eq QuestionStatus.OPEN.name) and (Questions.dispatchBound eq 0) and
                    (Questions.processId inList chunk)
            }.forEach { row -> counts.merge(row[Questions.processId], 1, Int::plus) }
    }
    return counts
}

// The ids among [rootIds] whose row exists and passes [canSeeRoot]; a missing row fails closed
// (CWE-200). By primary key, only the columns the check reads.
private fun visibleRootIdsTx(
    rootIds: List<Int>,
    canSeeRoot: (owner: String?, agentName: String) -> Boolean,
): Set<Int> {
    if (rootIds.isEmpty()) return emptySet()
    val visible = mutableSetOf<Int>()
    for (chunk in rootIds.chunked(SQLITE_PARAM_CHUNK)) {
        Processes
            .select(Processes.id, Processes.owner, Processes.agentName)
            .where { Processes.id inList chunk }
            .forEach { row -> if (canSeeRoot(row[Processes.owner], row[Processes.agentName])) visible += row[Processes.id] }
    }
    return visible
}

/**
 * Sums [Processes.costUsd] by the value of each [key] tag, with [screenTags] applied per row before grouping, never
 * in SQL (CWE-203). The candidates are the newest [maxRows] (overridden only by tests), read through
 * [Processes.recordReadColumns]; [CostByTagResult.truncated] reports that more existed. See ADR-0062.
 */
suspend fun ProcessRepository.costByTag(
    key: String,
    screenTags: (ProcessInfo) -> List<String>,
    maxRows: Int = MAX_TAG_SCREENED_ROWS,
): CostByTagResult =
    read {
        val prefix = "$key:"
        // A range bound, never `LIKE`: every character TagValidation allows sorts between ':' and ';',
        // so this matches exactly `key:*`, with no wildcard (ADR-0062).
        val exclusiveUpperBound = "$key;"
        // Fetch one row past the bound to detect truncation, then drop it: ORDER BY
        // id DESC keeps the newest maxRows candidates when there are more than that.
        val fetched =
            Processes
                .select(Processes.recordReadColumns)
                .where { hasTagPrefix(prefix, exclusiveUpperBound) }
                .orderBy(Processes.id to SortOrder.DESC)
                .limit(maxRows + 1)
                .toList()
        val truncated = fetched.size > maxRows
        val rows = if (truncated) fetched.dropLast(1) else fetched
        if (rows.isEmpty()) return@read CostByTagResult(emptyMap(), truncated)
        val totals = mutableMapOf<String, Double>()
        for (info in rows.toProcessInfosTx(jobRegistry)) {
            for (tag in screenTags(info)) {
                if (tag.startsWith(prefix)) {
                    val value = tag.substring(prefix.length)
                    totals[value] = (totals[value] ?: 0.0) + info.costUsd
                }
            }
        }
        CostByTagResult(totals, truncated)
    }

/**
 * Rows carrying every one of [dedupedTags]. [indexDriven] is an `id IN (… HAVING COUNT = n)` subquery on
 * `process_tags_tag_process_id`; otherwise a correlated EXISTS per row the visibility clause admits, so
 * its cost never depends on runs the caller cannot see. See ADR-0060.
 */
private fun carriesAllTags(
    dedupedTags: List<String>,
    indexDriven: Boolean,
): Op<Boolean> =
    if (indexDriven) {
        Processes.id inSubQuery
            ProcessTags
                .select(ProcessTags.processId)
                .where { ProcessTags.tag inList dedupedTags }
                .groupBy(ProcessTags.processId)
                .having { ProcessTags.processId.count() eq dedupedTags.size.toLong() }
    } else {
        exists(
            ProcessTags
                .select(ProcessTags.processId)
                .where { (ProcessTags.processId eq Processes.id) and (ProcessTags.tag inList dedupedTags) }
                .groupBy(ProcessTags.processId)
                .having { ProcessTags.processId.count() eq dedupedTags.size.toLong() },
        )
    }

// Index-driven on process_tags_tag_process_id; a correlated EXISTS plans as SCAN processes.
// See ADR-0062 (2026-09-29 amendment).
private fun hasTagPrefix(
    prefix: String,
    exclusiveUpperBound: String,
): Op<Boolean> =
    Processes.id inSubQuery
        ProcessTags
            .select(ProcessTags.processId)
            .where {
                (ProcessTags.tag greaterEq prefix) and (ProcessTags.tag less exclusiveUpperBound)
            }

/**
 * (id, agent name) of CREATED runs of the given agents, for `/agents/reload` to kill. Served
 * by `processes_agent_status_ended`; empty [agentNames] returns empty without a query.
 */
suspend fun ProcessRepository.createdRunsOf(agentNames: Collection<String>): List<Pair<Int, String>> {
    if (agentNames.isEmpty()) return emptyList()
    return read {
        Processes
            .select(Processes.id, Processes.agentName)
            .where { (Processes.status eq ProcessStatus.CREATED.name) and (Processes.agentName inList agentNames) }
            .map { it[Processes.id] to it[Processes.agentName] }
    }
}

/**
 * The progress of each of [ids] that still exists, keyed by id: one chunked `id IN (…)` read
 * on the primary key of only the columns a LangGraph `subs` entry shows. An evicted or purged
 * id is simply absent. See ADR-0044.
 */
suspend fun ProcessRepository.getSubRunProgress(ids: Collection<Int>): Map<Int, SubRunProgress> {
    if (ids.isEmpty()) return emptyMap()
    return read {
        val byId = mutableMapOf<Int, SubRunProgress>()
        for (chunk in ids.distinct().chunked(SQLITE_PARAM_CHUNK)) {
            Processes
                .select(Processes.id, Processes.agentName, Processes.status, Processes.executions, Processes.currentCommand)
                .where { Processes.id inList chunk }
                .forEach { row ->
                    byId[row[Processes.id]] =
                        SubRunProgress(
                            agentName = row[Processes.agentName],
                            status = ProcessStatus.valueOf(row[Processes.status]),
                            executions = decodeExecutions(row[Processes.executions]),
                            currentCommand = row[Processes.currentCommand],
                        )
                }
        }
        byId
    }
}

suspend fun ProcessRepository.activeProcessIds(): Set<Int> =
    read {
        Processes
            .select(Processes.id)
            .where { Processes.status inList ACTIVE_STATUS_NAMES }
            .map { it[Processes.id] }
            .toSet()
    }
