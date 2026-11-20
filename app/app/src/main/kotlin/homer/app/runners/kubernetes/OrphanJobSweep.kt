package homer.app.runners.kubernetes

import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.openapi.models.V1Job

/**
 * Deletes this instance's Homer-managed pods in [namespace] whose `homer.app/process-id` is not in [activeProcessIds]; a
 * blank [instanceId] selects namespace-wide. With [batchApi] (Job mode) orphan Jobs go too, Job-owned pods with them.
 * Never throws: failures are reported in `SweepResult.errors`.
 */
internal fun sweepWorkloadOrphans(
    api: CoreV1Api,
    batchApi: BatchV1Api?,
    namespace: String,
    instanceId: String,
    activeProcessIds: Set<Int>,
): KubernetesRunner.SweepResult {
    val pods = OrphanPodSweep(api, namespace, instanceId, skipJobOwned = batchApi != null).sweep(activeProcessIds)
    return batchApi?.let { OrphanJobSweep(it, namespace, instanceId).sweep(activeProcessIds, pods) } ?: pods
}

/**
 * The Job half of [KubernetesRunner.sweepOrphans] in Job mode: lists this instance's Jobs in [namespace] and deletes
 * the orphans with `Background` propagation, a Job whose pod was never created included (ADR-0100).
 */
internal class OrphanJobSweep(
    private val batchApi: BatchV1Api,
    private val namespace: String,
    private val instanceId: String,
) {
    /** [pods] with the deleted Jobs and this half's errors added; a Job list failure deletes no Job. */
    fun sweep(
        activeProcessIds: Set<Int>,
        pods: KubernetesRunner.SweepResult,
    ): KubernetesRunner.SweepResult {
        val jobs =
            try {
                listJobs()
            } catch (e: ApiException) {
                return pods.listFailed("${e.code} ${e.responseBody ?: e.message}")
            } catch (e: Exception) {
                // The typed list rejects a Job carrying fields this client does not know; the pod half must survive it.
                return pods.listFailed(e.message.orEmpty())
            }
        val errors = mutableListOf<String>()
        val deleted =
            jobs
                .mapNotNull { OrphanLabels.orphanOf(it.metadata, activeProcessIds, "Job") }
                .filter { delete(it.name, errors) }
                .map { SweepStatus.DeletedJob(namespace, it.name, it.processId, it.runnerAlias) }
        return pods.copy(deletedJobs = deleted, errors = pods.errors + errors)
    }

    private fun listJobs(): List<V1Job> =
        batchApi
            .listNamespacedJob(namespace)
            .labelSelector(OrphanLabels.selector(instanceId))
            .execute()
            .items
            .orEmpty()

    private fun KubernetesRunner.SweepResult.listFailed(detail: String) =
        copy(
            deletedJobs = emptyList(),
            errors =
                errors + "list jobs failed: $detail",
        )

    /** True when [name] was deleted; a Job already gone is no error. */
    private fun delete(
        name: String,
        errors: MutableList<String>,
    ): Boolean =
        try {
            JobCleanup.deleteOrThrow(batchApi, namespace, name)
            true
        } catch (e: ApiException) {
            if (e.code != HTTP_NOT_FOUND) errors += "delete Job $name failed: ${e.code} ${e.responseBody ?: e.message}"
            false
        }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
