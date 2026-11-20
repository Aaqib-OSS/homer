package homer.app.services

import homer.app.executors.RunMessageExpiry
import homer.app.infra.PurgeSchedule
import homer.app.services.persistence.ProcessRepository
import org.slf4j.Logger
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Boot-time reconciliation of state left by an unclean shutdown: `CREATED`/`WORKING` runs become `UNKNOWN`, their OPEN questions expire, their QUEUED messages become UNDELIVERED (ADR-0097), and every `UNKNOWN` row still missing purge times is stamped from its agent's current `workspace`. Runs once before traffic. See ADR-0020, ADR-0048. */
@OptIn(ExperimentalTime::class)
internal class StartupReconciliation(
    private val processRepository: ProcessRepository,
    private val questionsService: QuestionsService,
    private val runMessagesService: RunMessageExpiry,
    private val dispatchService: DispatchService,
    private val continuationService: ContinuationService,
    private val hookService: HookService,
    private val agentsService: AgentsService,
    private val logger: Logger,
    private val clock: () -> Long = { Clock.System.now().epochSeconds },
) {
    suspend fun reconcile() {
        val now = clock()
        // UNKNOWN records hooks through ProcessManager's per-transition lookup, so a hook
        // declared for UNKNOWN fires for a restart-interrupted run too. See ADR-0107.
        val interrupted = processRepository.markInterruptedAsUnknown(now, hookService.recording())
        if (interrupted.processIds.isNotEmpty()) {
            logger.info(
                "Reconciled {} interrupted process(es) to UNKNOWN after restart: {}",
                interrupted.processIds.size,
                interrupted.processIds,
            )
            for (processId in interrupted.processIds) {
                questionsService.expireForProcess(processId)
                runMessagesService.closeForProcess(processId)
            }
        }
        // Backstop for a run whose inbox close failed, or whose server stopped before it ran.
        runMessagesService.closeForEndedRuns()
        val agents = agentsService.agents
        val stamped =
            processRepository.stampUnknownPurgeTimes(now) { agentName ->
                agents[agentName]?.let { PurgeSchedule.of(it.workspace) } ?: PurgeSchedule.DEFAULT
            }
        if (stamped > 0) logger.info("Scheduled the purge of {} UNKNOWN process(es)", stamped)
        // Fail dispatches interrupted mid-fire (avoid duplicate runs), then fire any that were
        // answered before the crash but never started.
        dispatchService.failOrphanedDispatching()
        dispatchService.refirePendingAnswered()
        // Same crash-safety for continuations: fail any left mid-dispatch, then fire any whose
        // watched process reached a terminal state before this boot (the live hook never ran).
        continuationService.failOrphanedDispatching()
        continuationService.refirePendingTerminal()
        // Same for hooks: fail any left mid-fire, fire the rows the UNKNOWN writes recorded, then replay
        // what is still PENDING; the claim CAS stops a replay double-firing a live row. See ADR-0107.
        hookService.failOrphanedDispatching()
        hookService.onRowsRecorded(interrupted.hooks)
        hookService.refirePending()
    }
}
