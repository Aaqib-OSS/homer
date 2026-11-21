package homer.app.exceptions

/**
 * Thrown when the exec channel of a live ssh session closes with an exit-signal name outside the
 * RFC 4254 set, such as OpenSSH's `SIG@openssh.com`. [signal] is already safe to quote (CWE-117).
 */
class SshUnknownExitSignalException(
    signal: String,
) : RuntimeException("the remote command ended on an unknown signal '$signal'")
