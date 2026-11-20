package homer.app.executors

import homer.app.EnvConstants
import homer.app.models.StopReason

/** The `HMR_STOP_REASON` and `ExecutionInfo.stoppedBy` wire value of [this]. */
val StopReason.wire: String
    get() =
        when (this) {
            StopReason.STEP_TIMEOUT -> EnvConstants.STOP_REASON_STEP_TIMEOUT
            StopReason.RUN_DEADLINE -> EnvConstants.STOP_REASON_RUN_DEADLINE
        }

/**
 * Builds the `HMR_STOP_*` env of an agent's `onStop:` steps. Every value is attacker-influenced, so it
 * is validated and bounded here and passed as a plain env value, never interpolated into a shell
 * string (ADR-0047 rule 2, ADR-0108).
 */
object StopContext {
    /** The cap on the exported step label; past it the label is cut, never the whole variable. */
    const val MAX_LABEL_CHARS = 256

    /** The only session-id shape ever exported: what the CLI harnesses already gate their own ids to. */
    private val SESSION_ID = Regex("^[A-Za-z0-9-]{1,128}$")

    /**
     * The stop env for one stop phase. [hardDeadlineEpochSec] is exported only for a
     * [StopReason.RUN_DEADLINE] stop — a step timeout has no run deadline to name — and
     * [sessionId] only when it passes [SESSION_ID].
     */
    fun env(
        reason: StopReason,
        stepIndex: Int,
        stepLabel: String,
        hardDeadlineEpochSec: Long?,
        sessionId: String?,
    ): Map<String, String> =
        buildMap {
            put(EnvConstants.STOP_REASON, reason.wire)
            put(EnvConstants.STOP_STEP_INDEX, stepIndex.toString())
            put(EnvConstants.STOP_STEP_LABEL, sanitizeLabel(stepLabel))
            if (reason == StopReason.RUN_DEADLINE && hardDeadlineEpochSec != null) {
                put(EnvConstants.STOP_DEADLINE, hardDeadlineEpochSec.toString())
            }
            sanitizeSessionId(sessionId)?.let { put(EnvConstants.STOP_SESSION_ID, it) }
        }

    /** [label] with control characters removed and cut to [MAX_LABEL_CHARS] — a newline would forge an env line. */
    fun sanitizeLabel(label: String): String = label.filterNot { it.isISOControl() }.take(MAX_LABEL_CHARS)

    /** [raw] when it is a plain session id, else null: anything else is dropped, not repaired. */
    fun sanitizeSessionId(raw: String?): String? = raw?.takeIf { SESSION_ID.matches(it) }
}
