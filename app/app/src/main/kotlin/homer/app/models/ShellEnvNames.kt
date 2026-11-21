package homer.app.models

/**
 * POSIX shell identifiers: the names agent inputs and result captures must use at load, and the only env names the
 * ssh and Kubernetes runners can deliver through a sourced shell file.
 */
object ShellEnvNames {
    const val PATTERN = "^[A-Za-z_][A-Za-z0-9_]*$"

    private val regex = Regex(PATTERN)

    /** The first of [names] that is not a shell identifier, or null when every one is. */
    fun firstInvalid(names: Iterable<String>): String? = names.firstOrNull { !regex.matches(it) }
}
