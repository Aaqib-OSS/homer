package homer.app.exceptions

/**
 * Thrown when the ssh server refuses a step's exec request — a ForceCommand, a restricted shell
 * or a session limit — detected from a rejected channel open or a channel closed with no exit
 * status on a live session (W-021). The message is fixed and carries no cause (ADR-0047).
 */
class SshExecRefusedException : RuntimeException("the ssh server refused the exec request")
