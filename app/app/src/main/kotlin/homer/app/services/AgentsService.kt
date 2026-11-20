package homer.app.services

import homer.app.infra.AgentDefinitionValidator
import homer.app.infra.AgentInputChecks
import homer.app.infra.AgentInputResolver
import homer.app.infra.AgentReloadDiff
import homer.app.infra.AgentScanner
import homer.app.infra.InputSchemaValidator
import homer.app.infra.ParamValidator
import homer.app.infra.ReloadComputation
import homer.app.infra.SysEnvResolver
import homer.app.models.Agent
import homer.app.models.AgentReloadResult
import org.slf4j.Logger
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads agent definitions from [agentsSearchPath] and publishes them as one atomic
 * snapshot. Scanning ([AgentScanner], validating through [AgentDefinitionValidator])
 * and the reload diff ([AgentReloadDiff]) are collaborators built from the
 * constructor's parameters, so the DI wiring sees one service.
 */
class AgentsService(
    private val agentsSearchPath: String,
    private val logger: Logger,
    sysEnvResolver: SysEnvResolver = SysEnvResolver(),
    private val broadcaster: ProcessEventBroadcaster = ProcessEventBroadcaster(),
    maxConfigFileBytes: Long = DEFAULT_MAX_CONFIG_FILE_BYTES,
    inputSchemaValidator: InputSchemaValidator = InputSchemaValidator(),
    paramValidator: ParamValidator = ParamValidator(),
    inputResolver: AgentInputResolver = AgentInputResolver(inputSchemaValidator, paramValidator),
    runnerAliases: Set<String> = setOf("local"),
    /**
     * Tag sets the configured runners serve, read at load time for the unmatched-tags
     * warning. Warn-only, so a throw here degrades to a warning; see ADR-0066 deviation (9).
     */
    runnerTagSets: () -> Collection<Set<String>> = { emptyList() },
) {
    private val scanner: AgentScanner =
        AgentInputChecks(logger, paramValidator).let { inputChecks ->
            AgentScanner(
                agentsSearchPath,
                logger,
                sysEnvResolver,
                maxConfigFileBytes,
                AgentDefinitionValidator(logger, runnerAliases, runnerTagSets, inputResolver, inputSchemaValidator, inputChecks),
                inputChecks,
            )
        }

    private val stateRef = AtomicReference(scanner.scan().toState())

    init {
        logger.info("Agents loaded: {}", stateRef.get().agents.size)
    }

    val agents: Map<String, Agent>
        get() = stateRef.get().agents

    val rootPath: Path
        get() = Path.of(agentsSearchPath)

    /**
     * Resolves the on-disk directory for a loaded agent. Returns null if the agent
     * is not currently loaded (unknown name, or its files failed to parse at scan time).
     */
    fun getAgentDir(name: String): Path? {
        val sourceDir = stateRef.get().sourceDirs[name] ?: return null
        return Path.of(agentsSearchPath).resolve(sourceDir)
    }

    /**
     * Rescan agent definitions on disk and atomically swap in the new state.
     * Disk I/O (`scanner.scan()`) runs outside any lock so concurrent callers can scan in
     * parallel; the new state is published via `compareAndSet` against the snapshot the scan
     * was based on. On a lost race we rescan and retry — both the scan AND the diff must be
     * recomputed against the now-current state, or a stale scan attributes "removed" to
     * directories the winning writer just legitimately added.
     */
    fun reload(): AgentReloadResult {
        var attempt = 0
        while (true) {
            // Read before the scan: a reload that publishes while this one scans must fail its CAS.
            val currentState = stateRef.get()
            val scan = scanner.scan()
            val computation = AgentReloadDiff.compute(scan, currentState)

            if (stateRef.compareAndSet(currentState, computation.newState)) {
                return publishReload(computation, attempt)
            }

            attempt++
            if (attempt >= MAX_RELOAD_CAS_ATTEMPTS) {
                return giveUpAfterContention(attempt)
            }
        }
    }

    /** Log the won-after-retries note (if any), the completion summary, and emit the reload event. */
    private fun publishReload(
        computation: ReloadComputation,
        attempt: Int,
    ): AgentReloadResult {
        val result = computation.result
        if (attempt > 0) {
            // Only emitted under genuine concurrent-reload contention, so it
            // never spams the happy path — a single line summarising the race.
            logger.info("AgentsService.reload() won after {} CAS retr(y/ies) under concurrent reload", attempt)
        }
        logger.info(
            "Reload complete: added={}, updated={}, removed={}, errors={}, total={}",
            result.added.size,
            result.updated.size,
            result.removed.size,
            result.errors.size,
            computation.newState.agents.size,
        )
        broadcaster.emit(ProcessEvent.AgentsReloaded(result.added, result.updated, result.removed))
        return result
    }

    /**
     * After [MAX_RELOAD_CAS_ATTEMPTS] lost races this reload published nothing and emits no event, so it
     * reports no agent change, only the failure in `errors`; the winning reloads reported theirs.
     */
    private fun giveUpAfterContention(attempt: Int): AgentReloadResult {
        logger.warn(
            "AgentsService.reload() lost CAS race {} times; another writer is reloading concurrently. Giving up.",
            attempt,
        )
        return AgentReloadResult(
            added = emptyList(),
            updated = emptyList(),
            removed = emptyList(),
            errors = listOf("Reload published nothing: $attempt concurrent reloads changed the agents during its scans; retry it"),
        )
    }

    private companion object {
        const val MAX_RELOAD_CAS_ATTEMPTS = 8
        const val DEFAULT_MAX_CONFIG_FILE_BYTES: Long = 1L * 1024 * 1024
    }
}
