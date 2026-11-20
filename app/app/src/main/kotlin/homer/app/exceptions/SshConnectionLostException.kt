package homer.app.exceptions

/**
 * Thrown when the ssh session itself drops before the exec channel yields an exit status, so the
 * command's outcome is unknown; a channel closed on a live session is a refused request instead
 * (SshExecRefusedException). Never treated as success — sibling of PodFailedDuringExecutionException.
 */
class SshConnectionLostException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
