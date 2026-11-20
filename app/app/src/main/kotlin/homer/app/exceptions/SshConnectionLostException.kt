package homer.app.exceptions

/**
 * Thrown when the ssh session itself drops before the exec channel yields an exit status — a dropped
 * session, or a channel open whose handshake times out or loses the connection; a channel closed on a
 * live session is a refused request instead (SshExecRefusedException). Never treated as success.
 */
class SshConnectionLostException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
