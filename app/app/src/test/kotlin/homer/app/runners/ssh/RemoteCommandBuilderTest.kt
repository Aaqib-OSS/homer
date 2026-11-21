package homer.app.runners.ssh

import homer.app.fixtures.awaitProcessExit
import homer.app.fixtures.pollUntil
import homer.app.runners.RemoteShell
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class RemoteCommandBuilderTest {
    @Test
    fun `build sources the env file and carries only its path on the command line`() {
        val cmd =
            RemoteCommandBuilder.build(
                remoteWorkdir = "/home/homer/ws/p1/workdir",
                envFile = "/home/homer/ws/p1/.homer/env/a1.env",
                argv = listOf("echo", "hello world"),
            )
        assertEquals(
            "cd '/home/homer/ws/p1/workdir' && exec sh -c '. \"\$0\" && exec \"\$@\"' " +
                "'/home/homer/ws/p1/.homer/env/a1.env' 'echo' 'hello world'",
            cmd,
        )
    }

    @Test
    fun `omits the env file launcher when there is no env file`() {
        val cmd = RemoteCommandBuilder.build("/w", null, listOf("ls", "-la"))
        assertEquals("cd '/w' && exec 'ls' '-la'", cmd)
    }

    @Test
    fun `preserves argv semantics for tokens with spaces and globs`() {
        val cmd = RemoteCommandBuilder.build("/w", null, listOf("grep", "a b", "*.kt"))
        assertEquals("cd '/w' && exec 'grep' 'a b' '*.kt'", cmd)
    }

    @Test
    fun `buildReaping records the pgid, then sources the env file inside the setsid group`() {
        val cmd =
            RemoteCommandBuilder.buildReaping(
                remoteWorkdir = "/w",
                envFile = "/w/.homer/env/a1.env",
                argv = listOf("echo", "hi"),
                pgidFile = "/w/.homer/reap/cmd-1.pgid",
            )
        assertEquals(
            "cd '/w' && exec setsid -w sh -c " +
                "'trap : INT TERM HUP; d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; " +
                "f=\$1; shift; . \"\$f\" && (exec \"\$@\"); exit \$?' " +
                "'/w/.homer/reap/cmd-1.pgid' '/w/.homer/env/a1.env' 'echo' 'hi'",
            cmd,
        )
    }

    @Test
    fun `buildReaping without an env file runs the argv as a child of the wrapper`() {
        val cmd = RemoteCommandBuilder.buildReaping("/w", null, listOf("ls"), "/w/.homer/reap/cmd-1.pgid")
        assertEquals(
            "cd '/w' && exec setsid -w sh -c " +
                "'trap : INT TERM HUP; d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; (exec \"\$@\"); exit \$?' " +
                "'/w/.homer/reap/cmd-1.pgid' 'ls'",
            cmd,
        )
    }

    @Test
    fun `an env that resets PATH still removes the env file and runs an absolute command`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        val envFile = tmp.resolve("e.env").toString()
        Path.of(envFile).writeText(RemoteShell.envFileContent(envFile, mapOf("PATH" to "/nonexistent")))
        val command = RemoteCommandBuilder.build(tmp.toString(), envFile, listOf("/bin/sh", "-c", "printf %s \"\$PATH\""))
        val (exit, out) = run("/bin/sh", command)
        assertEquals(0 to "/nonexistent", exit to out)
        assertFalse(Files.exists(Path.of(envFile)), "the env file outlived the launch")
    }

    @Test
    fun `a failing export stops the command under sh and non-posix bash, and the env file is gone`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))
        for (shell in listOf("/bin/sh", "/bin/bash").filter { Files.isExecutable(Path.of(it)) }) {
            val envFile = tmp.resolve("e.env").toString()
            val lines = RemoteShell.envFileContent(envFile, linkedMapOf("A" to "1", "B" to "2")).lines()
            // `B` made readonly before its export line: the export fails in every shell.
            Path.of(envFile).writeText((listOf(lines[0], "readonly B") + lines.drop(1)).joinToString("\n"))
            val (exit, out) = run(shell, ". '$envFile' && echo RAN")
            assertTrue(exit != 0, "$shell: the failing export must fail the launch")
            assertFalse(out.contains("RAN"), "$shell: the command ran with a partial env")
            assertFalse(Files.exists(Path.of(envFile)), "$shell: the env file outlived the launch")
        }
    }

    /** Runs [script] under [shell] `-c`, returning its exit code and combined output. */
    private fun run(
        shell: String,
        script: String,
    ): Pair<Int, String> {
        val p = ProcessBuilder(shell, "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().decodeToString()
        return p.waitFor() to out
    }

    @Test
    fun `the reaping wrapper reports 137 under every shell for a command SIGKILLed from outside`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in wrapperShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(wrapperAlone(reaping(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30")), shell), dir)
            signal("KILL", awaitPid(dir.resolve("child.pid")))
            assertEquals(137, exitOf(process), "under $shell")
        }
    }

    @Test
    fun `the reaping wrapper reports a real exit 9 under every shell with its stdout and stderr`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in wrapperShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(wrapperAlone(reaping(dir, listOf("sh", "-c", "echo out; echo err >&2; exit 9")), shell), dir)
            val exit = exitOf(process)
            assertEquals(Triple(9, "out\n", "err\n"), Triple(exit, dir.resolve("out").readText(), dir.resolve("err").readText()), shell)
        }
    }

    @Test
    fun `the reaping wrapper runs a builtin-named argv from PATH under every shell, so exit is not found`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in wrapperShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            assertEquals(127, exitOf(start(wrapperAlone(reaping(dir, listOf("exit", "3")), shell), dir)), "under $shell")
        }
    }

    @Test
    fun `the reaping wrapper runs echo from PATH under every shell, so a backslash prints literally`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in wrapperShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            exitOf(start(wrapperAlone(reaping(dir, listOf("echo", "C:\\new\\table")), shell), dir))
            assertEquals("C:\\new\\table\n", dir.resolve("out").readText(), "under $shell")
        }
    }

    @Test
    fun `the reaping wrapper records its own pid under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in wrapperShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(wrapperAlone(reaping(dir, listOf("true")), shell), dir)
            exitOf(process)
            assertEquals(process.pid().toString(), dir.resolve(PGID_FILE).readText().trim(), "under $shell")
        }
    }

    // Without setsid there is no group to signal; a group SIGINT reaches each member, so this signals both.
    @Test
    fun `a command that traps SIGINT reports its own exit through the reaping wrapper under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in wrapperShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val script = "trap 'exit 0' INT; echo \$\$ > child.pid; while :; do sleep 0.1; done"
            val process = start(wrapperAlone(reaping(dir, listOf("sh", "-c", script)), shell), dir)
            signal("INT", process.pid(), awaitPid(dir.resolve("child.pid")))
            assertEquals(0, exitOf(process), "under $shell")
        }
    }

    @Test
    fun `a command that does not trap SIGINT reports 130 through the reaping wrapper under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in wrapperShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(wrapperAlone(reaping(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30")), shell), dir)
            signal("INT", process.pid(), awaitPid(dir.resolve("child.pid")))
            assertEquals(130, exitOf(process), "under $shell")
        }
    }

    @Test
    fun `a command that does not trap SIGTERM reports 143 through the reaping wrapper under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in wrapperShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(wrapperAlone(reaping(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30")), shell), dir)
            signal("TERM", process.pid(), awaitPid(dir.resolve("child.pid")))
            assertEquals(143, exitOf(process), "under $shell")
        }
    }

    @Test
    fun `under setsid -w a command SIGKILLed from outside reports 137`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        val process = start(underLoginSession(reaping(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30"))), dir)
        signal("KILL", awaitPid(dir.resolve("child.pid")))
        assertEquals(137, exitOf(process))
    }

    @Test
    fun `under setsid -w the graceful reaper lets a command that traps SIGINT report its own exit`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        val script = "trap 'exit 0' INT; echo \$\$ > child.pid; sleep 30"
        assertEquals(0, exitAfterGracefulReap(dir, script))
    }

    @Test
    fun `under setsid -w the graceful reaper makes a command that does not trap SIGINT report 130`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        assertEquals(130, exitAfterGracefulReap(dir, "echo \$\$ > child.pid; sleep 30"))
    }

    @Test
    fun `under setsid -w a group SIGTERM reports 143 for a command that does not trap it`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        assertEquals(143, exitAfterGroupSignal(dir, "TERM"))
    }

    @Test
    fun `under setsid -w a group SIGHUP reports 129 for a command that does not trap it`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        assertEquals(129, exitAfterGroupSignal(dir, "HUP"))
    }

    @Test
    fun `under setsid -w the pgid file holds the wrapper's process group id`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        val process = start(underLoginSession(reaping(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30"))), dir)
        try {
            val child = awaitPid(dir.resolve("child.pid"))
            val pgid = dir.resolve(PGID_FILE).readText().trim()
            assertEquals(listOf(pgid, pgid), listOf(groupOf(pgid.toLong()), groupOf(child)))
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    @Test
    fun `under setsid -w buildReapKill SIGKILLs the recorded group, a backgrounded child included`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        val script = "sleep 30 & echo \$! > child.pid; wait"
        val process = start(underLoginSession(reaping(dir, listOf("sh", "-c", script))), dir)
        val background = awaitPid(dir.resolve("child.pid"))
        ProcessBuilder("sh", "-c", RemoteCommandBuilder.buildReapKill(dir.resolve(PGID_FILE).toString())).start().waitFor()
        exitOf(process)
        assertTrue(awaitProcessExit(background), "the backgrounded child outlived the group kill")
    }

    /** Runs a command that traps nothing under `setsid -w`, signals its whole group with [name], and returns its exit. */
    private fun exitAfterGroupSignal(
        dir: Path,
        name: String,
    ): Int {
        val process = start(underLoginSession(reaping(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30"))), dir)
        awaitPid(dir.resolve("child.pid"))
        // The shell's builtin kill: procps-ng kill misparses a negative pid (ADR-0041).
        ProcessBuilder("sh", "-c", "kill -$name -\"\$0\"", dir.resolve(PGID_FILE).readText().trim()).start().waitFor()
        return exitOf(process)
    }

    /** Runs [script] reaping under `setsid -w`, SIGINTs its group with the real graceful reaper, and returns its exit. */
    private fun exitAfterGracefulReap(
        dir: Path,
        script: String,
    ): Int {
        val process = start(underLoginSession(reaping(dir, listOf("sh", "-c", script))), dir)
        awaitPid(dir.resolve("child.pid"))
        val reaper = ProcessBuilder("sh", "-c", RemoteCommandBuilder.buildReapInterrupt(dir.resolve(PGID_FILE).toString(), 30)).start()
        try {
            return exitOf(process)
        } finally {
            reaper.descendants().forEach { it.destroyForcibly() }
            reaper.destroyForcibly()
        }
    }

    /** The reaping command line for [argv] in [dir], recording its pgid to [PGID_FILE] there. */
    private fun reaping(
        dir: Path,
        argv: List<String>,
    ): String = RemoteCommandBuilder.buildReaping(dir.toString(), null, argv, dir.resolve(PGID_FILE).toString())

    /** Starts [argv] with its stdout and stderr in `out` and `err` under [dir]. */
    private fun start(
        argv: List<String>,
        dir: Path,
    ): Process =
        ProcessBuilder(argv)
            .redirectOutput(dir.resolve("out").toFile())
            .redirectError(dir.resolve("err").toFile())
            .start()

    /** [process]'s exit code; fails the test, killing it, when it is still running after a bounded wait. */
    private fun exitOf(process: Process): Int {
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            fail("the command did not exit")
        }
        return process.exitValue()
    }

    /** The pid the command wrote to [file], once it has written one. */
    private fun awaitPid(file: Path): Long {
        assertTrue(pollUntil { file.exists() && file.readText().trim().isNotEmpty() }, "no pid in $file")
        return file.readText().trim().toLong()
    }

    private fun signal(
        name: String,
        vararg pids: Long,
    ) {
        ProcessBuilder(listOf("kill", "-s", name) + pids.map { it.toString() }).start().waitFor()
    }

    private fun groupOf(pid: Long): String =
        ProcessBuilder("ps", "-o", "pgid=", "-p", pid.toString())
            .start()
            .inputStream
            .readBytes()
            .decodeToString()
            .trim()

    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `buildReapKill numerically guards the pgid before kill`() {
        assertEquals(
            "p=\$(cat '/w/.homer/reap/cmd-1.pgid' 2>/dev/null); case \"\$p\" in ''|*[!0-9]*) exit 0;; esac; " +
                "[ \"\$p\" -gt 1 ] && kill -KILL -\"\$p\" 2>/dev/null",
            RemoteCommandBuilder.buildReapKill("/w/.homer/reap/cmd-1.pgid"),
        )
    }

    @Test
    fun `buildReapInterrupt sends SIGINT then SIGKILL after the grace`() {
        assertEquals(
            "p=\$(cat '/w/.homer/reap/cmd-1.pgid' 2>/dev/null); case \"\$p\" in ''|*[!0-9]*) exit 0;; esac; " +
                "[ \"\$p\" -gt 1 ] && { kill -INT -\"\$p\" 2>/dev/null; sleep 3; kill -KILL -\"\$p\" 2>/dev/null; }",
            RemoteCommandBuilder.buildReapInterrupt("/w/.homer/reap/cmd-1.pgid", 3L),
        )
    }

    @Test
    fun `buildReapInterrupt clamps a negative grace to zero`() {
        val cmd = RemoteCommandBuilder.buildReapInterrupt("/w/.homer/reap/cmd-1.pgid", -5L)
        assertContains(cmd, "sleep 0; kill -KILL")
    }

    /**
     * Runs the real buildReapKill command with `kill` shadowed by a shell function so nothing is signalled.
     * Asserts the numeric guard lets only a plain integer > 1 through: a forged `1`/`0`, a non-numeric value or an
     * injection attempt is a no-op.
     */
    @Test
    fun `buildReapKill only kills a plain pgid greater than one`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))

        fun decision(pgidContent: String): String {
            val f = tmp.resolve("pgid").also { it.writeText(pgidContent) }
            val cmd = "kill() { echo \"WOULDKILL \$*\"; }; " + RemoteCommandBuilder.buildReapKill(f.toString())
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            return p.inputStream
                .readBytes()
                .decodeToString()
                .trim()
        }
        assertEquals("WOULDKILL -KILL -12345", decision("12345"))
        assertEquals("", decision("1"))
        assertEquals("", decision("0"))
        assertEquals("", decision("abc"))
        assertEquals("", decision(""))
        assertEquals("", decision("1; touch /tmp/pwned")) // non-numeric -> guarded out
    }

    /**
     * Same shadowed-`kill` harness as the buildReapKill guard test, for the graceful
     * reaper: the shared guard lets only a plain integer > 1 through, so a forged
     * side-file (`0`, `1`, `-1`, non-numeric) sends neither the SIGINT nor the SIGKILL.
     */
    @Test
    fun `buildReapInterrupt signals nothing for a forged pgid file`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"))

        fun decision(pgidContent: String): String {
            val f = tmp.resolve("pgid").also { it.writeText(pgidContent) }
            // grace 0 so the valid case does not sleep.
            val cmd = "kill() { echo \"WOULDKILL \$*\"; }; " + RemoteCommandBuilder.buildReapInterrupt(f.toString(), 0L)
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            return p.inputStream
                .readBytes()
                .decodeToString()
                .trim()
        }
        assertEquals("WOULDKILL -INT -12345\nWOULDKILL -KILL -12345", decision("12345"))
        assertEquals("", decision(""))
        assertEquals("", decision("0"))
        assertEquals("", decision("1"))
        assertEquals("", decision("-1"))
        assertEquals("", decision("abc"))
    }

    private companion object {
        const val PGID_FILE = ".homer/reap/cmd-1.pgid"
    }
}
