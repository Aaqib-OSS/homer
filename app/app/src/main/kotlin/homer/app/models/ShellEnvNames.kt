package homer.app.models

/** Env names the ssh and Kubernetes runners can deliver through a sourced shell file: POSIX shell identifiers. */
object ShellEnvNames {
    const val PATTERN = "^[A-Za-z_][A-Za-z0-9_]*$"

    private val regex = Regex(PATTERN)

    /** The first of [names] that is not a shell identifier, or null when every one is. */
    fun firstInvalid(names: Iterable<String>): String? = names.firstOrNull { !regex.matches(it) }
}
