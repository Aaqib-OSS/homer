package homer.app.runners.kubernetes

import homer.app.SpanAttributes
import homer.app.infra.ShellExecutor
import homer.app.models.ExtraUploadSpec
import homer.app.runners.DownloadMode
import homer.app.runners.RemotePathTranslator
import homer.app.runners.RunDeadline
import homer.app.runners.RunnerSession
import io.kubernetes.client.openapi.apis.BatchV1Api
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a session's pod ends: its run deadlines, what close() does with the pod and, in Job mode, its Job
 * (ADR-0100), and the trace close() reports under.
 */
data class PodLifecycle(
    val keepPodOnFailure: Boolean,
    val keepPodTtlSeconds: Long,
    val maxRunSeconds: Long,
    val gracefulStopSeconds: Long? = null,
    /** When Homer created the pod (`RunnerState.createdAt`); clamps a skewed `status.startTime`. */
    val podCreatedAtEpochSec: Long? = null,
    val jobName: String? = null,
    val batchApi: BatchV1Api? = null,
    val tracer: Tracer? = null,
    // The run's context, captured at openSession: close() runs after the run's scope, and an unparented close span
    // would be a new root that escapes `telemetry.export: OFF` (ADR-0050).
    val parentContext: Context = Context.root(),
)

class KubernetesRunnerSession(
    val podName: String,
    val namespace: String,
    private val api: CoreV1Api,
    override val shell: ShellExecutor,
    private val lifecycle: PodLifecycle,
    override val runnerAlias: String,
    private val sync: KubernetesWorkspaceSync,
    private val translator: RemotePathTranslator,
    private val initialEnv: Map<String, String>,
    private val syncExcludes: List<String> = emptyList(),
    override val extraUploads: List<ExtraUploadSpec> = emptyList(),
    override val env: Map<String, String> = emptyMap(),
) : RunnerSession {
    private val closed = AtomicBoolean(false)
    private var failed: Boolean = false
    private val teardown = WorkloadTeardown(api, namespace, lifecycle.jobName, lifecycle.batchApi)

    /**
     * The run's soft and hard deadlines, read once here — the session is built when the pod is
     * ready. Null unless `gracefulStopSeconds` is set, so a run without a graceful stop pays no
     * extra Kubernetes read. See ADR-0108.
     */
    override val runDeadline: RunDeadline? = runDeadlineFromPod()

    override val metadata: Map<String, String>
        get() = mapOf(SpanAttributes.POD_NAME to podName) + listOfNotNull(lifecycle.jobName?.let { SpanAttributes.JOB_NAME to it })

    override fun markFailed() {
        failed = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val mode = cleanupMode()
        val span = closeSpan(mode)
        try {
            cleanUpPod(mode)
            span?.setStatus(StatusCode.OK)
        } catch (e: Throwable) {
            span?.recordException(e)
            span?.setStatus(StatusCode.ERROR, e.message ?: "runner.session.close failed")
            throw e
        } finally {
            span?.end()
        }
    }

    /** `ttl` keeps a failed run's pod for debugging when configured to; else `delete`. */
    private fun cleanupMode(): String = if (failed && lifecycle.keepPodOnFailure) "ttl" else "delete"

    private fun closeSpan(mode: String): Span? {
        val finalPhase = if (mode == "ttl") "KEPT_FOR_DEBUG" else "DELETED"
        return lifecycle.tracer
            ?.spanBuilder("runner.session.close")
            ?.setParent(lifecycle.parentContext)
            ?.startSpan()
            ?.apply {
                setAttribute(SpanAttributes.RUNNER_ALIAS, runnerAlias)
                setAttribute(SpanAttributes.POD_NAMESPACE, namespace)
                setAttribute(SpanAttributes.POD_NAME, podName)
                lifecycle.jobName?.let { setAttribute(SpanAttributes.JOB_NAME, it) }
                setAttribute("pod.cleanup_mode", mode)
                setAttribute("pod.final_phase", finalPhase)
            }
    }

    /** The pod, and in Job mode its Job (ADR-0100). */
    private fun cleanUpPod(mode: String) {
        if (mode == "ttl") {
            teardown.keep(podName, lifecycle.keepPodTtlSeconds, lifecycle.maxRunSeconds)
        } else {
            teardown.delete(podName)
        }
    }

    override suspend fun createWorkspace(): Map<String, String> =
        withContext(Dispatchers.IO) {
            // mkdir -p creates the projectRoot too; workdir must exist before
            // any command (a runnerCmd check may run before uploads).
            sync.mkdirs(REMOTE_WORKDIR)
            translator.overrides(initialEnv) +
                extraUploads.mapNotNull { spec -> spec.env?.let { it to "$REMOTE_PROJECT_ROOT/${spec.remote}" } }
        }

    override suspend fun upload(
        local: Path,
        remoteRelative: String,
        excludes: List<String>,
    ) = withContext(Dispatchers.IO) {
        val remote = remotePath(remoteRelative)
        // Only the top-level workdir's own tar entry is omitted: GNU tar cannot restore the mtime of that kubelet-made
        // subPath dir under runAsNonRoot, and a kept workspace still needs its content (ADR-0054).
        val omitDirEntries = if (remote == REMOTE_PROJECT_ROOT) setOf("workdir") else emptySet()
        sync.upload(local, remote, syncExcludes + excludes, omitDirEntries)
    }

    override suspend fun download(
        remoteRelative: String,
        local: Path,
        mode: DownloadMode,
    ) = withContext(Dispatchers.IO) {
        sync.download(remotePath(remoteRelative), local, mode = mode)
    }

    // cleanWorkspace(): deliberately the no-op default — the emptyDir dies
    // with the pod, and a kept-for-debug pod retains its workspace (ADR-0043).

    private fun remotePath(relative: String): String =
        if (relative == "." || relative.isEmpty()) REMOTE_PROJECT_ROOT else "$REMOTE_PROJECT_ROOT/${relative.trimStart('/')}"

    /**
     * The run's deadlines: `maxRunSeconds` after the pod started is the hard one, `gracefulStopSeconds`
     * before it the soft one. Null unless `gracefulStopSeconds` is set and the pod's start is known.
     */
    private fun runDeadlineFromPod(): RunDeadline? {
        val grace = lifecycle.gracefulStopSeconds ?: return null
        val start = podStartEpochSec() ?: return null
        val hard = start + lifecycle.maxRunSeconds
        return RunDeadline(softEpochSec = hard - grace, hardEpochSec = hard)
    }

    /**
     * The pod's `status.startTime` in epoch seconds, clamped to the earlier of it and Homer's recorded `createdAt`: a
     * skewed pod-reported value may shorten the deadline, never extend it (ADR-0047 rule 2). A failed read uses `createdAt`.
     */
    private fun podStartEpochSec(): Long? {
        val reported =
            runCatching {
                api
                    .readNamespacedPodStatus(podName, namespace)
                    .execute()
                    .status
                    ?.startTime
                    ?.toEpochSecond()
            }.getOrNull()
        return listOfNotNull(reported, lifecycle.podCreatedAtEpochSec).minOrNull()
    }

    companion object {
        // Fixed in-pod workspace root; pod-per-run means no collisions (ADR-0043).
        const val REMOTE_PROJECT_ROOT = "/homer/workspace"
        const val REMOTE_WORKDIR = "$REMOTE_PROJECT_ROOT/workdir"

        // Outside workdir and artifacts, so no download brings a step's env file back to the server.
        const val REMOTE_ENV_DIR = "$REMOTE_PROJECT_ROOT/.homer/env"
    }
}
