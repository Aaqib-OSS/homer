package homer.app.runners

import homer.app.infra.ShellExecutor
import homer.app.models.ExtraUploadSpec
import org.slf4j.LoggerFactory
import java.nio.file.Path

enum class DownloadMode {
    OVERLAY,

    OVERWRITE,

    /** Local extras absent remotely are deleted only after a fully successful download; a partial transfer or
     * missing remote path leaves the target untouched. Single-file downloads have no extras scope (OVERWRITE). See ADR-0055. */
    MIRROR,
}

/** A run's soft and hard wall-clock deadlines in epoch seconds: at [softEpochSec] the run must stop gracefully
 * (interrupt the step, then run its stop steps); at [hardEpochSec] the backend kills it. See ADR-0108. */
data class RunDeadline(
    val softEpochSec: Long,
    val hardEpochSec: Long,
)

/** Owns the shell and any backing resource (pod, connection, ...) released when the session ends; [close] is
 * idempotent and safe to call from cancellation paths. */
interface RunnerSession : AutoCloseable {
    val shell: ShellExecutor

    /** Null when the backend imposes none, so callers must branch on null; only Kubernetes reports one, from
     * `gracefulStopSeconds`. See ADR-0108. */
    val runDeadline: RunDeadline?
        get() = null

    val runnerAlias: String

    /** Merged into the workspace env before commands run (e.g. kubernetes contributes HMR_API_URL). */
    val env: Map<String, String>
        get() = emptyMap()

    val metadata: Map<String, String>
        get() = emptyMap()

    /** Mechanism only — the executors own when to upload them: `AgentExecutor` with the workspace,
     * `RunnerCmdExecutor` after `createWorkspace()` and before checks. See ADR-0042. */
    val extraUploads: List<ExtraUploadSpec>
        get() = emptyList()

    suspend fun createWorkspace(): Map<String, String> = emptyMap()

    /** [excludes] are paths relative to [local] a copy must not transfer; an override that transfers anything
     * must honour them — dropping them silently ships the ADR-0047 parent/child leak. */
    suspend fun upload(
        local: Path,
        remoteRelative: String,
        excludes: List<String> = emptyList(),
    ) {
        if (excludes.isNotEmpty()) {
            LoggerFactory.getLogger(RunnerSession::class.java).warn(
                "upload() default ignored {} exclude(s) for '{}' — a backend that copies must honour excludes (ADR-0047)",
                excludes.size,
                remoteRelative,
            )
        }
    }

    suspend fun download(
        remoteRelative: String,
        local: Path,
        mode: DownloadMode = DownloadMode.OVERLAY,
    ) {
    }

    suspend fun cleanWorkspace() {
    }

    /** Call before [close]; lets a keep-on-failure backend (kubernetes `keepPodOnFailure`) retain the resource.
     * Kills are not failures — the caller decides. */
    fun markFailed() {
    }

    override fun close()
}
