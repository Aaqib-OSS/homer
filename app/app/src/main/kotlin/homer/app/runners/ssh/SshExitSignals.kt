package homer.app.runners.ssh

/** The RFC 4254 exit-signal names, as Linux signal numbers: the remote is assumed Linux, as `setsid -w` already does. */
internal object SshExitSignals {
    private val NUMBERS =
        mapOf(
            "HUP" to 1,
            "INT" to 2,
            "QUIT" to 3,
            "ILL" to 4,
            "ABRT" to 6,
            "FPE" to 8,
            "KILL" to 9,
            "USR1" to 10,
            "SEGV" to 11,
            "USR2" to 12,
            "PIPE" to 13,
            "ALRM" to 14,
            "TERM" to 15,
        )

    private val PLAIN_NAME = Regex("[A-Za-z0-9_@.-]{1,24}")

    /** The signal [name]'s number, or null when it is not an RFC 4254 name. */
    fun number(name: String): Int? = NUMBERS[name]

    /** [name] as safe to quote: the server chose it, so anything but a short printable name is withheld (CWE-117). */
    fun quotable(name: String): String = if (PLAIN_NAME.matches(name)) name else "<unprintable>"
}
