package homer.app.runners.kubernetes

import homer.app.runners.ConfiguredRunners
import org.slf4j.LoggerFactory

/**
 * At boot, deletes every configured Kubernetes runner's Homer-managed pods (and Jobs in Job mode) whose
 * `homer.app/process-id` is not an active process of [ActiveRunIds], so an unclean restart's orphans do not linger.
 * Assumes one Homer replica per instance.
 */
class KubernetesRunnerStartupSweep(
    private val runners: ConfiguredRunners,
    private val activeRuns: ActiveRunIds,
    private val status: SweepStatus,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sweep() {
        val active = activeRuns.activeProcessIds()
        var scanned = 0
        val deleted = mutableListOf<SweepStatus.DeletedPod>()
        val errors = mutableListOf<String>()
        var deletedJobs: List<SweepStatus.DeletedJob>? = null

        for (alias in runners.aliases()) {
            val runner = runners.get(alias) as? KubernetesRunner ?: continue
            try {
                val result = runner.sweepOrphans(active)
                scanned += result.scanned
                deleted += result.deleted
                errors += result.errors
                result.deletedJobs?.let { deletedJobs = deletedJobs.orEmpty() + it }
                log.info(
                    "sweep: namespace={} scanned={} deleted={} deletedJobs={} errors={}",
                    runner.namespace,
                    result.scanned,
                    result.deleted.size,
                    result.deletedJobs?.size,
                    result.errors.size,
                )
            } catch (e: Exception) {
                val msg = "sweep failed for runner '$alias': ${e.message}"
                log.warn(msg, e)
                errors += msg
            }
        }

        status.set(
            SweepStatus.Snapshot(
                ranAt = clock() / 1000, // epoch seconds, matching every other API timestamp
                scanned = scanned,
                deleted = deleted.toList(),
                errors = errors.toList(),
                deletedJobs = deletedJobs,
            ),
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(KubernetesRunnerStartupSweep::class.java)
    }
}
