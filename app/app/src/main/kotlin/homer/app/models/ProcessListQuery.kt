package homer.app.models

/**
 * One page request of the process list behind `GET /status/all`, read by
 * `homer.app.services.persistence.ProcessRecordReads.getAllProcesses`.
 */
data class ProcessListQuery(
    val filterStatuses: Set<ProcessStatus>? = null,
    val orderAsc: Boolean = false,
    val offset: Int = 0,
    val limit: Int = 100,
    val agentName: String? = null,
    val visibleOwner: String? = null,
    /**
     * Null means an admin: no visibility filter, and the index-driven `?tag=` subquery,
     * whose cost follows every run with the tag. Never null for a principal-scoped caller.
     * See ADR-0060 (2026-09-29 amendment).
     */
    val visibleAgents: Set<String>? = null,
    val parentProcessId: Int? = null,
    val rootProcessId: Int? = null,
    /**
     * Roots-only: `parent_process_id IS NULL`, i.e. the first run of each
     * flow. Composes with every other predicate here (AND). See ADR-0058.
     */
    val rootsOnly: Boolean = false,
    val tags: List<String> = emptyList(),
    /**
     * Read-time tag screen for the filtered path: given a matched row's [ProcessInfo], returns the tags that
     * survive display redaction. Required when [tags] is non-empty, with no default-null escape: the read
     * fails closed when it is null (CWE-203; ADR-0052, ADR-0060).
     */
    val screenTags: ((ProcessInfo) -> List<String>)? = null,
    /**
     * The caller's visibility check for a flow-root row, run in the read's transaction. Required when
     * [rootProcessId] is set (the read throws otherwise): the filter holds only when that root exists and
     * passes it, else an empty page. `visibleRootIds` names the page's passing roots; null names none. ADR-0058.
     */
    val canSeeRoot: ((owner: String?, agentName: String) -> Boolean)? = null,
)
