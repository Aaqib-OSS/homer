package homer.app.exceptions

/**
 * Thrown when the ssh server refuses a step's exec request — a ForceCommand, a restricted shell
 * or a session limit — so the channel yields no exit status while the session lives. The message
 * is fixed and no cause is attached: the server's own refusal text quotes the command (ADR-0047).
 */
class SshExecRefusedException : RuntimeException("the ssh server refused the exec request")
