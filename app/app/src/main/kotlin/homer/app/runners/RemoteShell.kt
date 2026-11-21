package homer.app.runners

import homer.app.models.ShellEnvNames

// POSIX shell pieces the ssh and Kubernetes runners share. A step's env reaches its command only through a sourced
// file, never a command line or exec request URI (CWE-214, CWE-598, ADR-0032, ADR-0043).
object RemoteShell {
    /** POSIX single-quote escaping: wrap in '...', and render embedded ' as '\''. */
    fun quote(token: String): String = "'" + token.replace("'", "'\\''") + "'"

    /** Sources the env file the shell variable [fileRef] names, then replaces the shell with the argv in `$@`. */
    fun sourceThenExec(fileRef: String): String = ". \"$fileRef\" && exec \"\$@\""

    /**
     * The `export` script a launcher sources from [envFile]: its first line removes the file before any export can
     * change the `PATH` that `rm` resolves on. Fails on a key that is not a POSIX shell identifier and on a value
     * holding a NUL byte.
     */
    fun envFileContent(
        envFile: String,
        env: Map<String, String>,
    ): String {
        val invalid = ShellEnvNames.firstInvalid(env.keys)
        require(invalid == null) { "env name '$invalid' is not a POSIX shell identifier, which a remote runner can export" }
        val withNul = env.entries.firstOrNull { '\u0000' in it.value }?.key
        require(withNul == null) { "env value of '$withNul' contains a NUL byte, which no process environment can hold" }
        return buildString {
            append("rm -f -- ").append(quote(envFile)).append(" || exit 1\n")
            env.forEach { (k, v) ->
                append("export ")
                    .append(k)
                    .append('=')
                    .append(quote(v))
                    .append(" || exit 1\n")
            }
        }
    }
}
