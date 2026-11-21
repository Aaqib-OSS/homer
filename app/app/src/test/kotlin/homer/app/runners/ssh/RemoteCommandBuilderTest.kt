package homer.app.runners.ssh

import homer.app.runners.RemoteShell
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                "'d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; f=\$1; shift; . \"\$f\" && exec \"\$@\"' " +
                "'/w/.homer/reap/cmd-1.pgid' '/w/.homer/env/a1.env' 'echo' 'hi'",
            cmd,
        )
    }

    @Test
    fun `buildReaping without an env file execs the argv directly`() {
        val cmd = RemoteCommandBuilder.buildReaping("/w", null, listOf("ls"), "/w/.homer/reap/cmd-1.pgid")
        assertEquals(
            "cd '/w' && exec setsid -w sh -c " +
                "'d=\$(dirname \"\$0\"); mkdir -p \"\$d\"; echo \$\$ > \"\$0\"; exec \"\$@\"' " +
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
     * Runs the real buildReapKill command with `kill` shadowed by a shell
     * function so nothing is actually signalled, and asserts the numeric guard
     * lets only a plain integer > 1 through. Proves a forged `1`/`0`, a
     * non-numeric value, or an injection attempt is a no-op.
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
}
