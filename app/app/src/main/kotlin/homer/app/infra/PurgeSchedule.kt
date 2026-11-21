package homer.app.infra

import homer.app.models.PurgeAfterParser
import homer.app.models.Workspace

/**
 * How long a terminal run's directory and record outlive its end, and which artifact globs
 * survive the sweep; derived from `workspace.purgeAfter` / `retain`. See ADR-0048, ADR-0053.
 */
data class PurgeSchedule(
    val purgeAfterSeconds: Long,
    val purgeRecordAfterSeconds: Long,
    val retainArtifacts: List<String>,
) {
    companion object {
        fun of(workspace: Workspace): PurgeSchedule {
            val purgeAfterSeconds = PurgeAfterParser.toSeconds(workspace.purgeAfter)
            return PurgeSchedule(
                purgeAfterSeconds = purgeAfterSeconds,
                // Falls back to purgeAfterSeconds, i.e. record and directory die together —
                // the behaviour without a retain block.
                purgeRecordAfterSeconds =
                    workspace.retain
                        ?.let { PurgeAfterParser.toSeconds(it.recordFor, "workspace.retain.recordFor") }
                        ?: purgeAfterSeconds,
                retainArtifacts = workspace.retain?.artifacts ?: emptyList(),
            )
        }

        /** An agent that is no longer loaded: the model's default workspace. */
        val DEFAULT: PurgeSchedule by lazy { of(Workspace()) }
    }
}
