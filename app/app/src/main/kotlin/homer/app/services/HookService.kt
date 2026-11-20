package homer.app.services

import homer.app.infra.AgentInputResolver
import homer.app.infra.coRunCatching
import homer.app.models.HookConfig
import homer.app.models.ProcessStatus
import homer.app.services.persistence.HookRecording
import homer.app.services.persistence.HookRepository
import homer.app.services.persistence.RecordedHooks
import homer.app.services.persistence.ShedHooks
import org.slf4j.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Where a hook row is in its one-shot delivery. `DROPPED` is settled before any attempt. See ADR-0107. */
enum class HookStatus { PENDING, DISPATCHING, DISPATCHED, FAILED, DROPPED }

/** One durable agent-level hook: a run of [declaringAgent] reached [triggeringStatus]. */
data class PendingHook(
    val id: Int,
    val processId: Int,
    val declaringAgent: String,
    val triggeringStatus: ProcessStatus,
    val targetAgentName: String,
    val owner: String?,
    val status: HookStatus,
    val fireAttempts: Int,
    val nextAttemptAt: Long?,
    val firedProcessId: Int?,
    val firedAt: Long?,
    val error: String?,
    val createdAt: Long,
)

/**
 * Agent-declared status hooks: durable rows written with the status they ride, fired here after that
 * transaction commits, claimed before the start and settled once. The fire itself is [HookFireService].
 * See ADR-0107, ADR-0023.
 */
@OptIn(ExperimentalTime::class)
internal class HookService(
    private val repository: HookRepository,
    agentRunStarter: AgentRunStarter,
    private val agents: AgentsService,
    authorizer: Authorizer,
    agentInputResolver: AgentInputResolver,
    processManager: ProcessManager,
    private val logger: Logger,
    private val config: HookConfig = HookConfig(),
    private val nowSec: () -> Long = { Clock.System.now().epochSeconds },
) {
    private val fires =
        HookFireService(
            repository,
            agents,
            authorizer,
            agentRunStarter,
            agentInputResolver,
            processManager,
            logger,
            config.maxChainDepth,
            nowSec,
        )

    /**
     * Fires the rows a just-committed transition recorded, as the insert built them, and logs the shed
     * ones. Called after the commit, never inside it, since a fire writes a run row; a failure is logged,
     * never thrown, and its row is left for the retry sweep or the boot replay. See ADR-0107.
     */
    suspend fun onRowsRecorded(recorded: RecordedHooks) {
        logShed(recorded.shed)
        for (hook in recorded.rows) fireSafely(hook)
    }

    /**
     * Fires [hook] with every non-cancellation failure contained, so none reaches the committed status
     * write or the rows behind it in a sweep. See ADR-0023, ADR-0107.
     */
    private suspend fun fireSafely(hook: PendingHook) {
        coRunCatching {
            fires.fire(hook)
        }.onFailure { e ->
            logger.error("Hook {} fire failed: {}", hook.id, e.message, e)
        }
    }

    /**
     * One warn per transition that had hook entries shed by the pending cap, so an operator can
     * see what a burst cost. The transition itself has already committed: shedding never fails
     * it, and never changes the triggering run's own status. See ADR-0107.
     */
    private fun logShed(shed: List<ShedHooks>) {
        for (entry in shed) {
            logger.warn(
                "Dropped {} hook row(s) for agent '{}' status {} over server.hooks.maxPending ({})",
                entry.count,
                entry.agentName,
                entry.status,
                config.maxPending,
            )
        }
    }

    suspend fun get(id: Int): PendingHook? = repository.get(id)

    /**
     * The per-transition hook lookup this service's rows are recorded with — the same one
     * [ProcessManager] runs, so an agent's hooks are resolved identically wherever a status is
     * written. See ADR-0107.
     */
    fun recording(): HookRecording = hookRecordingFor(agents, config.maxPending)

    /** Re-fires hooks whose transient-failure retry is due. Driven by [ContinuationSweepService]. */
    suspend fun sweepRetries() {
        val due =
            coRunCatching {
                repository.findDueRetries(nowSec())
            }.getOrElse { e ->
                logger.error("Hook retry lookup failed: {}", e.message, e)
                return
            }
        for (hook in due) fireSafely(hook)
    }

    /** Fails hooks left DISPATCHING by a restart (avoids a duplicate fire). Boot only. */
    suspend fun failOrphanedDispatching() {
        val orphaned = repository.failOrphanedDispatching(nowSec())
        if (orphaned.isNotEmpty()) {
            logger.warn("Failed {} hook(s) interrupted mid-dispatch by restart", orphaned.size)
        }
    }

    /**
     * Fires every PENDING hook left by a stop between a commit and its fire, or by a retry that never
     * came. The claim CAS makes this safe beside a live fire. Boot only. See ADR-0107.
     */
    suspend fun refirePending() {
        val pending = repository.list(HookStatus.PENDING)
        if (pending.isNotEmpty()) {
            logger.info("Re-firing {} hook(s) left pending by a previous run", pending.size)
        }
        for (hook in pending) fireSafely(hook)
    }

    /** Deletes settled hooks older than `settledRetentionSec`. See ADR-0107. */
    suspend fun pruneSettled() {
        val pruned = repository.pruneSettled(nowSec() - config.settledRetentionSec)
        if (pruned > 0) {
            logger.info("Pruned {} settled hook(s) older than {} s", pruned, config.settledRetentionSec)
        }
    }
}

/**
 * The per-transition hook lookup every status writer shares through [HookService.recording]. It reads
 * [agentsService] at call time, so a reload is honoured, and no database, so it is safe inside the
 * write transaction. See ADR-0107.
 */
internal fun hookRecordingFor(
    agentsService: AgentsService,
    maxPending: Int,
): HookRecording =
    HookRecording(
        hooksFor = { agentName, status ->
            agentsService.agents[agentName]
                ?.hooks
                ?.filter { it.status == status }
                .orEmpty()
        },
        maxPending = maxPending,
    )
