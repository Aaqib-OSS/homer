package homer.app.infra

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

// Best-effort POSIX process-group reaping. `ProcessHandle.descendants()` misses a child spawned in
// the snapshot→kill window and any double-fork/setsid-detached child, so the command is launched
// under `setsid` and the negative pgid reaped. Degrades to descendants() where setsid is absent. See ADR-0041.
object ProcessGroupSupport {
    private const val KILL_GROUP_WAIT_SEC = 2L

    /** Absolute path to `setsid`, or null when it is unavailable on this host. */
    val setsidPath: String? by lazy { resolveOnPath("setsid") }

    /** Prepends `setsid` so [command] leads a new process group, when available. */
    fun launchArgv(command: List<String>): List<String> = setsidPath?.let { listOf(it) + command } ?: command

    // The pgid to reap for [process], or null when a group kill is not safe. Returns a pgid ONLY when
    // the process leads its own group (pgid == pid), so a group kill can never target the JVM's own
    // group. Read at KILL time, not at launch: the child is a leader only after setsid(2) has run.
    fun ownProcessGroupId(process: java.lang.Process): Long? = if (setsidPath == null) null else groupLeaderPgid(process, ::readPgid)

    // [process]'s pid when [pgidOf] reports it as its own group's leader and the pid still names
    // [process]; null otherwise. `/proc` read injected.
    internal fun groupLeaderPgid(
        process: java.lang.Process,
        pgidOf: (Long) -> Long?,
    ): Long? {
        val pid = process.pid()
        val pgid = pgidOf(pid) ?: return null
        // PID-reuse guard (TOCTOU): confirm AFTER the /proc read that the pid still belongs to THIS
        // process (ProcessHandle equality includes the start time), else fall back to descendants-only.
        val isSameProcess =
            ProcessHandle
                .of(pid)
                .map { it == process.toHandle() }
                .orElse(false)
        if (!isSameProcess) return null
        return pid.takeIf { pgid == it }
    }

    // SIGKILL to the whole group, best-effort. Must go through the `sh` builtin `kill`: the standalone
    // binaries misparse the negative target. [pgid] is a Long, so the interpolation cannot inject.
    fun killGroup(pgid: Long) {
        signalGroup("KILL", pgid)
    }

    // SIGINT to the whole group so a handler can shut down before [killGroup] follows. Same
    // `sh`-builtin and Long-interpolation rules as [killGroup]. See ADR-0108.
    fun interruptGroup(pgid: Long) {
        signalGroup("INT", pgid)
    }

    private fun signalGroup(
        signal: String,
        pgid: Long,
    ) {
        runCatching {
            ProcessBuilder("/bin/sh", "-c", "kill -$signal -$pgid")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor(KILL_GROUP_WAIT_SEC, TimeUnit.SECONDS)
        }
    }

    private fun readPgid(pid: Long): Long? =
        runCatching {
            // /proc/<pid>/stat comm can contain spaces and ')': parse the fields after the final ") ".
            val fields =
                Path
                    .of("/proc/$pid/stat")
                    .readText()
                    .substringAfterLast(") ")
                    .trim()
                    .split(" ")
            fields[2].toLong() // [0]=state [1]=ppid [2]=pgrp
        }.getOrNull()

    private fun resolveOnPath(name: String): String? = firstExecutableOnPath(name, System.getenv("PATH"))

    // First executable named [name] on [pathValue], as an absolute path. Relative PATH entries are
    // ignored: they would resolve against the writable per-run workdir and let a planted binary hijack.
    internal fun firstExecutableOnPath(
        name: String,
        pathValue: String?,
    ): String? {
        if (pathValue == null) return null
        return pathValue
            .split(File.pathSeparatorChar)
            .filter { it.isNotEmpty() }
            .map { Path.of(it) }
            .filter { it.isAbsolute }
            .map { it.resolve(name) }
            .firstOrNull { Files.isExecutable(it) }
            ?.toString()
    }
}
