package homer.app.services

import homer.app.infra.LangGraphTopologyLimits
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.LangGraphGraphInfo
import homer.app.models.LangGraphTopology
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs the `homer_langgraph inspect` subprocess for one LangGraph command. */
fun interface GraphInspectRunner {
    suspend fun run(
        python: String,
        module: String,
        env: Map<String, String>,
        timeout: Duration,
    ): GraphInspectResult
}

sealed class GraphInspectResult {
    /** stdout of `inspect --json`. */
    data class Ok(
        val json: String,
    ) : GraphInspectResult()

    data class Err(
        val message: String,
    ) : GraphInspectResult()
}

/** JSON printed by `homer_langgraph inspect --json` (see the runtime's README.md). */
@Serializable
private data class InspectOutput(
    val mermaid: String? = null,
    val topology: LangGraphTopology? = null,
)

/**
 * Each `Command.LangGraph`'s topology (and the deprecated Mermaid source, ADR-0073) from a read-only
 * `homer_langgraph inspect --json` subprocess given the agent's `env` on an allowlisted base. A failed
 * command surfaces as [LangGraphGraphInfo.error]; every command gets an entry. See ADR-0027.
 */
class LangGraphInspectService(
    private val runner: GraphInspectRunner,
    private val timeout: Duration = 15.seconds,
    maxConcurrentInspects: Int = 4,
    private val maxQueueWait: Duration = 5.seconds,
) {
    // Bounds the interpreters across requests, with a bounded wait; independent of
    // LangGraphRunStatusService's budget. See ADR-0027, ADR-0047 rules 4–5.
    private val inspectSlots = Semaphore(maxConcurrentInspects)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun inspect(agent: Agent): List<LangGraphGraphInfo> {
        val commands = agent.commands.filterIsInstance<Command.LangGraph>()
        if (commands.isEmpty()) return emptyList()
        // One permit per request (ADR-0027). Set `acquired` right after acquire(), with no suspension
        // between: withTimeoutOrNull can return null with the permit taken, and trusting it leaks one.
        var acquired = false
        withTimeoutOrNull(maxQueueWait) {
            inspectSlots.acquire()
            acquired = true
        }
        if (!acquired) {
            return commands.map { LangGraphGraphInfo(label = it.label, module = it.module, error = BUSY_MESSAGE) }
        }
        return try {
            commands.map { cmd ->
                when (val result = runner.run(cmd.python, cmd.module, agent.env, timeout)) {
                    is GraphInspectResult.Ok -> {
                        graphInfo(cmd, result.json)
                    }

                    is GraphInspectResult.Err -> {
                        LangGraphGraphInfo(label = cmd.label, module = cmd.module, error = result.message)
                    }
                }
            }
        } finally {
            inspectSlots.release()
        }
    }

    private fun graphInfo(
        cmd: Command.LangGraph,
        raw: String,
    ): LangGraphGraphInfo {
        fun failed(message: String) = LangGraphGraphInfo(label = cmd.label, module = cmd.module, error = message)
        val output =
            runCatching { json.decodeFromString<InspectOutput>(raw) }
                .getOrElse { return failed("unparseable inspect output: ${it.message}") }
        val topology = output.topology ?: return failed("inspect output has no graph topology")
        LangGraphTopologyLimits.violation(topology)?.let { return failed(it) }
        return LangGraphGraphInfo(label = cmd.label, module = cmd.module, topology = topology, mermaid = output.mermaid)
    }

    companion object {
        /** Reported per command when no inspect slot came free in time; retrying is safe. */
        const val BUSY_MESSAGE = "graph inspection is busy (too many concurrent requests) — retry"
    }
}
