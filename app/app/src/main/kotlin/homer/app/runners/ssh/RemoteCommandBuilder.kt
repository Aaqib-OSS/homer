package homer.app.runners.ssh

// Every token is POSIX single-quoted for the remote login shell, and `exec` keeps the command's signals and exit code.
// The `env K=V…` prefix puts the env on the remote command line (AR-09, ADR-0032).
object RemoteCommandBuilder {
    fun build(
        remoteWorkdir: String,
        env: Map<String, String>,
        argv: List<String>,
    ): String {
        val parts = mutableListOf("cd", quote(remoteWorkdir))
        val head = mutableListOf("&&", "exec")
        if (env.isNotEmpty()) {
            head.add("env")
            env.forEach { (k, v) -> head.add(quote("$k=$v")) }
        }
        argv.forEach { head.add(quote(it)) }
        return (parts + head).joinToString(" ")
    }

    // Runs the command as its own process group and records its pgid to [pgidFile], so a control channel can kill
    // the whole group, backgrounded children included; needs `setsid -w`, which SshRunner probes for (ADR-0041).
    fun buildReaping(
        remoteWorkdir: String,
        env: Map<String, String>,
        argv: List<String>,
        pgidFile: String,
    ): String {
        val parts = mutableListOf("cd", quote(remoteWorkdir), "&&", "exec")
        if (env.isNotEmpty()) {
            parts.add("env")
            env.forEach { (k, v) -> parts.add(quote("$k=$v")) }
        }
        parts.add("setsid")
        parts.add("-w")
        parts.add("sh")
        parts.add("-c")
        // $0 is the pgid file; $@ is the real argv. `exec "$@"` keeps the pid/pgid.
        parts.add(quote("d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; exec \"\$@\""))
        parts.add(quote(pgidFile))
        argv.forEach { parts.add(quote(it)) }
        return parts.joinToString(" ")
    }

    // Reaps the group recorded in [pgidFile] with SIGKILL; the pgid is validated as a plain integer
    // above 1 first, so a stale, corrupt or forged side-file can never become `kill -KILL -1` or `-0`.
    fun buildReapKill(pgidFile: String): String = readPgidGuard(pgidFile) + PGID_ABOVE_ONE_GUARD + "kill -KILL -\"\$p\" 2>/dev/null"

    // Graceful variant: SIGINT the group, wait [graceSeconds], then SIGKILL the rest. Shares the
    // guards with [buildReapKill] so the two cannot drift; [graceSeconds] is a clamped Long. See ADR-0108.
    fun buildReapInterrupt(
        pgidFile: String,
        graceSeconds: Long,
    ): String {
        val grace = graceSeconds.coerceAtLeast(0)
        return readPgidGuard(pgidFile) + PGID_ABOVE_ONE_GUARD +
            "{ kill -INT -\"\$p\" 2>/dev/null; sleep $grace; kill -KILL -\"\$p\" 2>/dev/null; }"
    }

    // Loads the pgid side-file and exits without signalling when it is missing, empty or non-numeric.
    private fun readPgidGuard(pgidFile: String): String =
        "p=\$(cat ${quote(pgidFile)} 2>/dev/null); case \"\$p\" in ''|*[!0-9]*) exit 0;; esac; "

    /** The `> 1` guard shared by both reapers: only a plain integer above 1 may be signalled. */
    private const val PGID_ABOVE_ONE_GUARD = "[ \"\$p\" -gt 1 ] && "

    /** POSIX single-quote escaping: wrap in '...', and render embedded ' as '\''. */
    fun quote(token: String): String = "'" + token.replace("'", "'\\''") + "'"
}
