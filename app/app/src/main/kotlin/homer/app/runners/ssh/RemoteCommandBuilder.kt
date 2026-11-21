package homer.app.runners.ssh

import homer.app.runners.RemoteShell.quote
import homer.app.runners.RemoteShell.sourceThenExec
import homer.app.runners.RemoteShell.sourceThenRun

// Every token is POSIX single-quoted for the remote login shell; the `exec` in [build] keeps the command's signals and exit code.
// The env reaches the command only through a sourced file, never the command line (CWE-214, ADR-0032).
object RemoteCommandBuilder {
    /** The command line for [argv] in [remoteWorkdir]; a non-null [envFile] is sourced first. */
    fun build(
        remoteWorkdir: String,
        envFile: String?,
        argv: List<String>,
    ): String {
        val parts = mutableListOf("cd", quote(remoteWorkdir), "&&", "exec")
        if (envFile != null) parts.addAll(listOf("sh", "-c", quote(sourceThenExec("\$0")), quote(envFile)))
        argv.forEach { parts.add(quote(it)) }
        return parts.joinToString(" ")
    }

    // Runs the command as its own process group and records its pgid to [pgidFile], so a control channel can kill
    // the whole group, backgrounded children included; needs `setsid -w`, which SshRunner probes for (ADR-0041).
    fun buildReaping(
        remoteWorkdir: String,
        envFile: String?,
        argv: List<String>,
        pgidFile: String,
    ): String {
        // `(exec …)` keeps PATH lookup of argv[0] and lets `exit $?` report a signal death as 128+n. `trap :`, never
        // `trap ''`, keeps the wrapper waiting through a group signal without the argv inheriting an ignore (ADR-0041).
        val launch = if (envFile != null) "f=\$1; shift; " + sourceThenRun("\$f") else "(exec \"\$@\")"
        val parts = mutableListOf("cd", quote(remoteWorkdir), "&&", "exec", "setsid", "-w", "sh", "-c")
        parts.add(quote("trap : INT TERM HUP; d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; $launch; exit \$?"))
        parts.add(quote(pgidFile))
        envFile?.let { parts.add(quote(it)) }
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
}
