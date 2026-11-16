package homer.app.infra

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of live coroutine [Job]s keyed by process id. A `Job` is a
 * runtime handle to *this* instance's coroutine and cannot be persisted, so it
 * lives here rather than in SQLite; `ProcessManager.killProcess` cancels a run
 * through it. Starts empty on every boot — the coroutines are gone after a
 * restart, which is why interrupted processes are reconciled to `UNKNOWN` (see
 * `StartupReconciliation`).
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
