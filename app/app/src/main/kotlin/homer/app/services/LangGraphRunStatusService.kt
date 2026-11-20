package homer.app.services

import homer.app.EnvConstants
import homer.app.infra.FileOperations
import homer.app.infra.GraphStateResult
import homer.app.infra.GraphStateRunner
import homer.app.infra.LangGraphTopologyLimits
import homer.app.infra.coRunCatching
import homer.app.models.Agent
import homer.app.models.AppConfig
import homer.app.models.Command
import homer.app.models.LangGraphEdgeRef
import homer.app.models.LangGraphRunIdentity
import homer.app.models.LangGraphRunStatus
import homer.app.models.LangGraphSubCommand
import homer.app.models.LangGraphSubProgress
import homer.app.models.LangGraphTopology
import homer.app.models.ProcessInfo
import homer.app.models.SubRunProgress
import homer.app.services.persistence.ContinuationRepository
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** JSON shape printed by `homer_langgraph state` (see the runtime's README.md). */
@Serializable
private data class GraphStateOutput(
    val threadId: String,
    val nodes: Map<String, String>,
    val errors: Map<String, String> = emptyMap(),
    // node -> pending interrupt ids parked at that node; absent on older runtimes. See ADR-0044.
    val interrupts: Map<String, List<String>> = emptyMap(),
    // node -> the interrupt ids in the thread's history (newest first), so a node keeps its
    // fan-out history after the join resolved.
    val interruptsSeen: Map<String, List<String>> = emptyMap(),
    // Nodes whose history hit the runtime's per-node cap; the live park is never dropped.
    val interruptsSeenTruncated: List<String> = emptyList(),
    // The graph's Mermaid source, riding along so no second `inspect` subprocess is needed.
    val mermaid: String? = null,
    // Nodes and edges for the console to draw; absent on older runtimes. See ADR-0073.
    val topology: LangGraphTopology? = null,
    // Edges this run's checkpoint history recorded as taken; re-filtered against the validated
    // topology in status() — agent-writable data, not trusted as-is. See ADR-0075.
    val traversed: List<LangGraphEdgeRef>? = null,
) {
    // Interrupt ids per node, history included; the union keeps a node's live park visible
    // even when a backend omits the latest snapshot from its history.
    fun nodeInterruptIds(): Map<String, List<String>> =
        (interruptsSeen.keys + interrupts.keys).associateWith { node ->
            ((interruptsSeen[node] ?: emptyList()) + (interrupts[node] ?: emptyList())).distinct().sorted()
        }
}

/** Outcome of one `state` subprocess: parsed output, or the message to report as `error`. */
private sealed interface StateOutcome {
    data class Ok(
        val output: GraphStateOutput,
    ) : StateOutcome

    data class Failed(
        val message: String,
    ) : StateOutcome
}

// Subprocess input in full — interpreter, graph, thread, env (the owner-scoped state dir), so two
// principals can never share an entry. Same input, same output, which is what makes it cacheable.
private data class StateKey(
    val python: String,
    val module: String,
    val threadId: String,
    val env: Map<String, String>,
)

private data class CachedState(
    val outcome: StateOutcome,
    val atMillis: Long,
)

// Per-run LangGraph introspection from the run-identity artifact the Python runtime writes, plus an
// on-demand, briefly cached `homer_langgraph state` read. See ADR-0044.
class LangGraphRunStatusService(
    private val fileOperations: FileOperations,
    private val stateRunner: GraphStateRunner,
    private val appConfig: AppConfig,
    private val continuationRepository: ContinuationRepository,
    private val processManager: ProcessManager,
    private val timeout: Duration = 15.seconds,
    private val stateCacheTtl: Duration = 4.seconds,
    maxCachedStates: Int = 64,
    maxConcurrentStateRuns: Int = 4,
    private val maxQueueWait: Duration = 5.seconds,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Bounds *simultaneous* work: each `state` run blocks a Dispatchers.IO thread (the pool SQLite
    // uses), so callers past the limit wait only [maxQueueWait] and then shed rather than pile up.
    private val stateRunSlots = Semaphore(maxConcurrentStateRuns)

    // Cache TTL sits just under the console's 5 s poll; a size-bounded LRU, not a plain map,
    // because the key includes an agent-writable thread id (untrusted cardinality).
    private val stateCache: MutableMap<StateKey, CachedState> =
        Collections.synchronizedMap(
            object : LinkedHashMap<StateKey, CachedState>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<StateKey, CachedState>): Boolean = size > maxCachedStates
            },
        )

    /** The run's LangGraph identity, or null when this is not a LangGraph run. */
    fun identity(processInfo: ProcessInfo): LangGraphRunIdentity? {
        val dir = processInfo.artifactsDir ?: return null
        val text = fileOperations.readTextBounded(dir.resolve(ARTIFACT_FILENAME), MAX_IDENTITY_BYTES) ?: return null
        return runCatching { json.decodeFromString<LangGraphRunIdentity>(text) }.getOrNull()
    }

    // Identity plus, when [includeGraph], topology and per-node status; null when the process
    // has no identity artifact (route -> 404).
    suspend fun status(
        processInfo: ProcessInfo,
        agent: Agent,
        includeGraph: Boolean,
    ): LangGraphRunStatus? {
        val identity = identity(processInfo) ?: return null
        val cmd =
            agent.commands.filterIsInstance<Command.LangGraph>().firstOrNull { it.module == identity.module }
                ?: agent.commands.filterIsInstance<Command.LangGraph>().firstOrNull()
        val base =
            LangGraphRunStatus(
                label = cmd?.label ?: identity.module,
                module = identity.module,
                threadId = identity.threadId,
                segment = identity.segment,
            )
        if (cmd == null) return base.copy(error = "agent '${agent.name}' no longer defines a LangGraph command")
        if (!includeGraph) return base

        // Scoped to the process's own owner: the thread id above comes from an agent-writable
        // artifact, so a forged id must not address another principal's checkpoint store. See ADR-0046.
        val env = agent.env + (EnvConstants.LG_STATE_DIR to appConfig.resolveLangGraphStateDir(processInfo.owner))
        // Only the checkpoint reading is cached. `subs` runs every call: it is a DB
        // read, so keeping sub progress live costs nothing worth saving.
        return when (val outcome = cachedState(StateKey(cmd.python, cmd.module, identity.threadId, env))) {
            is StateOutcome.Ok -> {
                val topology = outcome.output.topology?.takeIf { LangGraphTopologyLimits.violation(it) == null }
                base.copy(
                    topology = topology,
                    traversed = traversed(outcome.output.traversed, topology),
                    mermaid = outcome.output.mermaid,
                    nodes = outcome.output.nodes,
                    errors = outcome.output.errors,
                    subs = subs(outcome.output.nodeInterruptIds(), identity.threadId, processInfo.owner),
                    subsTruncated = outcome.output.interruptsSeenTruncated.takeIf { it.isNotEmpty() },
                )
            }

            is StateOutcome.Failed -> {
                base.copy(error = outcome.message)
            }
        }
    }

    // [runtimeTraversed] kept only where each pair is a real edge of [topology]: its source is
    // agent-writable checkpoint data, so Homer re-checks it (§12 rule 2). See ADR-0075.
    private fun traversed(
        runtimeTraversed: List<LangGraphEdgeRef>?,
        topology: LangGraphTopology?,
    ): List<LangGraphEdgeRef>? {
        if (topology == null || runtimeTraversed == null) return null
        val edges = topology.edges.mapTo(HashSet()) { it.source to it.target }
        return runtimeTraversed.filter { (it.source to it.target) in edges }.distinct()
    }

    // One `state` reading for [key], reused for [stateCacheTtl]. Failures are cached too: a
    // timing-out or missing interpreter must not be re-attempted once per poll per viewer.
    private suspend fun cachedState(key: StateKey): StateOutcome {
        fresh(key)?.let { return it }
        // `acquired` is set inside the timeout block right after acquire() returns, with no
        // suspension between: withTimeoutOrNull can report null even when the permit was taken.
        var acquired = false
        withTimeoutOrNull(maxQueueWait) {
            stateRunSlots.acquire()
            acquired = true
        }
        // Deliberately NOT cached: caching "busy" would serve it to everyone for the
        // whole TTL, turning a moment of load into a guaranteed outage.
        if (!acquired) return StateOutcome.Failed(BUSY_MESSAGE)
        return try {
            // Checked again now that we hold a slot: while these callers queued, the first one
            // published a reading, so only the few in flight when the entry was cold spawn a run.
            fresh(key) ?: runAndCache(key)
        } finally {
            stateRunSlots.release()
        }
    }

    /** The cached reading for [key] if it is still within [stateCacheTtl], else null. */
    private fun fresh(key: StateKey): StateOutcome? {
        val ttl = stateCacheTtl.inWholeMilliseconds
        // Read and write are separate map operations on purpose: a subprocess must
        // never run under the map's monitor.
        return stateCache[key]?.takeIf { nowMillis() - it.atMillis < ttl }?.outcome
    }

    /** Spawns one `state` subprocess for [key] and caches whatever it yields. */
    private suspend fun runAndCache(key: StateKey): StateOutcome {
        val startedAt = nowMillis()
        val outcome =
            when (val result = stateRunner.run(key.python, key.module, key.threadId, key.env, timeout)) {
                is GraphStateResult.Ok -> {
                    runCatching { json.decodeFromString<GraphStateOutput>(result.json) }
                        .fold(
                            onSuccess = { StateOutcome.Ok(it) },
                            onFailure = { StateOutcome.Failed("unparseable state output: ${it.message}") },
                        )
                }

                is GraphStateResult.Err -> {
                    StateOutcome.Failed(result.message)
                }
            }
        // Stamped with the pre-run time, so the entry expires a subprocess-duration
        // early rather than late.
        stateCache[key] = CachedState(outcome, startedAt)
        return outcome
    }

    // Per-sub progress for fan-out nodes: matches each interrupt id (pending or resolved) against the
    // thread's continuations. Only [owner]'s count — thread id/interrupt ids are agent-writable. ADR-0044.
    private suspend fun subs(
        interrupts: Map<String, List<String>>,
        threadId: String,
        owner: String?,
    ): Map<String, List<LangGraphSubProgress>>? {
        if (interrupts.isEmpty()) return null
        return coRunCatching {
            val watchedProcessByInterrupt =
                continuationRepository
                    .byThread(threadId, owner)
                    .flatMap { continuation ->
                        when (continuation.kind) {
                            ContinuationKind.JOIN -> {
                                continuation.watches.map { it.interruptId to it.watchedProcessId }
                            }

                            ContinuationKind.SINGLE -> {
                                continuation.params[EnvConstants.LG_INTERRUPT]
                                    ?.let { listOf(it to continuation.watchedProcessId) }
                                    ?: emptyList()
                            }
                        }
                    }.toMap()
            val progress =
                processManager.getSubRunProgress(interrupts.values.flatten().mapNotNull { watchedProcessByInterrupt[it] })
            buildMap {
                for ((node, ids) in interrupts) {
                    val nodeSubs =
                        ids.mapNotNull { interruptId ->
                            val processId = watchedProcessByInterrupt[interruptId] ?: return@mapNotNull null
                            val sub = progress[processId]
                            LangGraphSubProgress(
                                interruptId,
                                processId,
                                sub?.agentName,
                                sub?.status?.name,
                                commands = sub?.let(::subCommands),
                            )
                        }
                    if (nodeSubs.isNotEmpty()) put(node, nodeSubs)
                }
            }
        }.getOrElse { null }
    }

    /** Compact per-command progress of a sub run: finished executions + the running command. */
    private fun subCommands(sub: SubRunProgress): List<LangGraphSubCommand> =
        buildList {
            sub.executions.forEach {
                val state =
                    when {
                        it.skipped -> "skipped"

                        // errMsg is set only on CommandOutcome.Failure; a finishExitCode/continueIfFail
                        // success records its raw non-zero exit code, so resultCode alone would lie.
                        it.errMsg == null -> "ok"

                        else -> "failed"
                    }
                add(LangGraphSubCommand(label = it.label, state = state))
            }
            // The step boundary commits the append and the current-command change in one
            // transaction, so currentCommand is never a just-finished step.
            sub.currentCommand
                ?.let { add(LangGraphSubCommand(label = it, state = "running")) }
        }

    companion object {
        /** Written by the Python runtime before every invoke (runner.py ARTIFACT_FILENAME). */
        const val ARTIFACT_FILENAME = "langgraph.json"

        /** Largest identity file read; a run's own code writes it (CWE-400). */
        const val MAX_IDENTITY_BYTES = 64L * 1024

        /** Reported when no run slot came free in time; the caller may simply poll again. */
        const val BUSY_MESSAGE = "graph status is busy (too many concurrent requests) — retry"
    }
}
