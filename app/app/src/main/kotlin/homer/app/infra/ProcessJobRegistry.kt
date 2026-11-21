package homer.app.infra

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of live coroutine [Job]s keyed by process id; `ProcessManager.killProcess`
 * cancels a run through it. Starts empty on every boot, so interrupted runs are reconciled to
 * `UNKNOWN` by `StartupReconciliation`.
 */
class ProcessJobRegistry {
    private val jobs = ConcurrentHashMap<Int, Job>()

    fun put(
        processId: Int,
        job: Job,
    ) {
        jobs[processId] = job
    }

    fun get(processId: Int): Job? = jobs[processId]

    fun remove(processId: Int) {
        jobs.remove(processId)
    }
}
