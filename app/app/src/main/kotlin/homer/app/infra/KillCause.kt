package homer.app.infra

import java.nio.file.Path
import kotlin.time.Duration

/** Why a command was killed, for the note each shell executor appends to its stderr. */
internal class KillCause {
    var byTimeout = false
    var byCap = false
}

/**
 * Appends the note naming why [kill] stopped the command: the TTL or output-cap marker, else nothing.
 * [graceNote] extends the TTL marker with the SIGINT grace (ADR-0108).
 */
internal fun appendKillNotices(
    stdErr: Path,
    kill: KillCause,
    timeout: Duration,
    maxOutputBytes: Long,
    graceNote: String = "",
) {
    if (kill.byTimeout) stdErr.toFile().appendText("\n[HOMER] Command killed due to TTL timeout ($timeout)$graceNote\n")
    if (kill.byCap) stdErr.toFile().appendText("\n[HOMER] Output cap exceeded ($maxOutputBytes bytes)\n")
}
