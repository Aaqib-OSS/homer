package homer.app.runners.kubernetes

import homer.app.fixtures.pollUntil
import homer.app.fixtures.waitUnfinished
import homer.app.runners.RemoteShell
import homer.app.runners.ssh.setsidCanFormGroup
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class PodProcessControlTest {
    @Test
    fun `with setsid -w the wrapper runs under it and sources the env file passed after the pgid file`() {
        val argv = podControl(hasSetsidWait = true).groupLaunch(PGID, ENV, listOf("echo", "hi"))

        assertEquals(listOf("setsid", "-w", "sh", "-c", "$TRAP$RECORD$SOURCE_THEN_RUN; exit \$?", PGID, ENV, "echo", "hi"), argv)
    }

    @Test
    fun `with setsid -w and no env file the wrapper runs the argv in a child`() {
        val argv = podControl(hasSetsidWait = true).groupLaunch(PGID, null, listOf("echo", "hi"))

        assertEquals(listOf("setsid", "-w", "sh", "-c", "$TRAP$RECORD$RUN; exit \$?", PGID, "echo", "hi"), argv)
    }

    @Test
    fun `without setsid -w the wrapper is launched with no prefix and sources the env file`() {
        val argv = podControl(hasSetsidWait = false).groupLaunch(PGID, ENV, listOf("echo", "hi"))

        assertEquals(listOf("sh", "-c", "$TRAP$RECORD$SOURCE_THEN_RUN; exit \$?", PGID, ENV, "echo", "hi"), argv)
    }

    @Test
    fun `without setsid -w and no env file the wrapper is launched with no prefix`() {
        val argv = podControl(hasSetsidWait = false).groupLaunch(PGID, null, listOf("echo", "hi"))

        assertEquals(listOf("sh", "-c", "$TRAP$RECORD$RUN; exit \$?", PGID, "echo", "hi"), argv)
    }

    @Test
    fun `a pod without setsid -w is probed once, and never for a plain setsid`() {
        val probes = mutableListOf<List<String>>()
        val control = podControl(hasSetsidWait = false, probes)

        control.groupLaunch(PGID, null, listOf("true"))
        control.groupLaunch(PGID, null, listOf("true"))

        assertEquals(listOf(listOf("setsid", "-w", "true")), probes)
    }

    @Test
    fun `a setsid -w probe that outlives its bound launches with no prefix and is destroyed`() {
        val slowProbe =
            mockk<Process>(relaxed = true) {
                every { waitFor(any<Long>(), any<TimeUnit>()) } answers {
                    waitUnfinished(firstArg(), secondArg())
                    false
                }
            }
        val control = PodProcessControl({ _, _, _, _ -> slowProbe }, "homer", "pod-1", signalExecSlackMs = 0)

        val argv = control.groupLaunch(PGID, null, listOf("echo", "hi"))

        assertEquals(listOf("sh", "-c", "$TRAP$RECORD$RUN; exit \$?", PGID, "echo", "hi"), argv)
        verify(exactly = 1) { slowProbe.destroyForcibly() }
        verify(exactly = 0) { slowProbe.exitValue() }
    }

    @Test
    fun `a grace gets a pgid file even where the pod lacks setsid -w`() {
        assertEquals(".homer/homer-exec-1.pgid", podControl(hasSetsidWait = false).pgidFileFor(emptyMap(), 5.seconds))
    }

    @Test
    fun `no grace gets no pgid file`() {
        assertNull(podControl(hasSetsidWait = true).pgidFileFor(mapOf("HMR_WORK_DIR" to "/w"), null))
    }

    @Test
    fun `the wrapper reports 137 under every shell for a command SIGKILLed from outside`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in podShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(alone(dir, shell, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30")), dir)
            signal("KILL", awaitPid(dir.resolve("child.pid")))
            assertEquals(137, exitOf(process), "under $shell")
        }
    }

    @Test
    fun `the wrapper reports a real exit 9 under every shell with its stdout and stderr`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in podShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(alone(dir, shell, listOf("sh", "-c", "echo out; echo err >&2; exit 9")), dir)
            val exit = exitOf(process)
            assertEquals(Triple(9, "out\n", "err\n"), Triple(exit, dir.resolve("out").readText(), dir.resolve("err").readText()), "$shell")
        }
    }

    // Without a group of its own the wrapper and the command are each sent the SIGINT a group signal would deliver.
    @Test
    fun `a command that does not trap SIGINT reports 130 through the wrapper under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in podShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(alone(dir, shell, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30")), dir)
            signal("INT", process.pid(), awaitPid(dir.resolve("child.pid")))
            assertEquals(130, exitOf(process), "under $shell")
        }
    }

    @Test
    fun `a command that traps SIGINT reports its own exit through the wrapper under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in podShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val script = "trap 'exit 7' INT; echo \$\$ > child.pid; while :; do sleep 0.1; done"
            val process = start(alone(dir, shell, listOf("sh", "-c", script)), dir)
            signal("INT", process.pid(), awaitPid(dir.resolve("child.pid")))
            assertEquals(7, exitOf(process), "under $shell")
        }
    }

    @Test
    fun `the wrapper sources and removes the env file under every shell before the argv runs`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in podShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val envFile = dir.resolve("step.env")
            envFile.writeText(RemoteShell.envFileContent(envFile.toString(), mapOf("STEP_VALUE" to "a b'c")))
            val argv = podControl(hasSetsidWait = false).groupLaunch(pgidFile(dir), envFile.toString(), listOf("sh", "-c", SHOW_VALUE))
            val exit = exitOf(start(underShell(argv, shell), dir))
            assertEquals(Triple(0, "a b'c|gone", ""), Triple(exit, dir.resolve("out").readText(), dir.resolve("err").readText()), "$shell")
        }
    }

    @Test
    fun `a wrapper that does not lead its process group records no pgid under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in podShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val process = start(alone(dir, shell, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30")), dir)
            val child = awaitPid(dir.resolve("child.pid"))
            val wrapperGroup = groupOf(process.pid())
            signal("KILL", child)
            exitOf(process)
            // Without procps (BusyBox) no shell's group is observable, so only that limit is assumed, as for
            // the setsid probes; a wrapper that leads its group is a failure, not a skip.
            assumeTrue(wrapperGroup.isNotEmpty(), "ps -o pgid= unavailable: no shell's group is observable")
            assertTrue(wrapperGroup != process.pid().toString(), "precondition: $shell led its group")
            assertFalse(Path.of(pgidFile(dir)).exists(), "under $shell")
        }
    }

    @Test
    fun `a wrapper that does not lead its process group removes a stale pgid file under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in podShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val stale = seedStalePgid(dir)
            exitOf(start(alone(dir, shell, listOf("true")), dir))
            assertFalse(stale.exists(), "under $shell")
        }
    }

    @Test
    fun `without rm on the PATH the wrapper still runs the argv with nothing on stderr under every shell`(
        @TempDir tmp: Path,
    ) {
        assumeFalse(isWindows())
        for (shell in podShells()) {
            val dir = Files.createTempDirectory(tmp, "run")
            val emptyPath = Files.createDirectories(dir.resolve("empty-path"))
            val builder = ProcessBuilder(alone(dir, shell, listOf("/bin/sh", "-c", "echo ran"))).directory(dir.toFile())
            builder.environment()["PATH"] = emptyPath.toString()
            val process = builder.redirectOutput(dir.resolve("out").toFile()).redirectError(dir.resolve("err").toFile()).start()
            val exit = exitOf(process)
            assertEquals(Triple(0, "ran\n", ""), Triple(exit, dir.resolve("out").readText(), dir.resolve("err").readText()), "$shell")
        }
    }

    @Test
    fun `under setsid -w as a pod exec runs it a SIGKILLed command reports 137 with no setsid line`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        val process = start(asPodExec(underSetsidWait(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30"))), dir)
        signal("KILL", awaitPid(dir.resolve("child.pid")))
        val exit = exitOf(process)
        assertEquals(137 to false, exit to dir.resolve("err").readText().contains("setsid:"), dir.resolve("err").readText())
    }

    @Test
    fun `under setsid -w as a pod exec runs it a stale pgid file is replaced by the wrapper's own group id`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        val stale = seedStalePgid(dir)
        val process = start(asPodExec(underSetsidWait(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30"))), dir)
        try {
            val child = awaitPid(dir.resolve("child.pid"))
            assertEquals(groupOf(child), stale.readText().trim())
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    @Test
    fun `under setsid -w as a pod exec runs it the pgid file holds the forked wrapper's process group id`(
        @TempDir dir: Path,
    ) {
        assumeTrue(setsidCanFormGroup(), "util-linux setsid -w is required")
        val process = start(asPodExec(underSetsidWait(dir, listOf("sh", "-c", "echo \$\$ > child.pid; exec sleep 30"))), dir)
        try {
            val child = awaitPid(dir.resolve("child.pid"))
            val pgid = Path.of(pgidFile(dir)).readText().trim()
            assertEquals(listOf(pgid, pgid, true), listOf(groupOf(pgid.toLong()), groupOf(child), pgid != process.pid().toString()))
        } finally {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    /** The launch for [argv] in [dir] on a pod that has `setsid -w`. */
    private fun underSetsidWait(
        dir: Path,
        argv: List<String>,
    ): List<String> = podControl(hasSetsidWait = true).groupLaunch(pgidFile(dir), null, argv)

    /** The launch without `setsid` for [argv] in [dir], its wrapper run by [shell] as the started process itself. */
    private fun alone(
        dir: Path,
        shell: List<String>,
        argv: List<String>,
    ): List<String> = underShell(podControl(hasSetsidWait = false).groupLaunch(pgidFile(dir), null, argv), shell)

    private fun pgidFile(dir: Path): String = dir.resolve(".homer/homer-exec-1.pgid").toString()

    /** An earlier session's pgid file in [dir], holding a live group's id that would pass the pgid guard: the test JVM's. */
    private fun seedStalePgid(dir: Path): Path {
        val file = Path.of(pgidFile(dir))
        Files.createDirectories(file.parent)
        file.writeText(groupOf(ProcessHandle.current().pid()) + "\n")
        return file
    }

    /** Starts [argv] in [dir] with its stdout and stderr in `out` and `err` there. */
    private fun start(
        argv: List<String>,
        dir: Path,
    ): Process =
        ProcessBuilder(argv)
            .directory(dir.toFile())
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

    private companion object {
        const val PGID = "/w/homer-exec-1.pgid"
        const val ENV = "/homer/workspace/.homer/env/a1.env"
        const val TRAP = "trap : INT TERM HUP; "
        const val RECORD =
            "p=\"\$0\"; d=\"\${p%/*}\"; mkdir -p \"\$d\" 2>/dev/null; rm -f \"\$p\" 2>/dev/null; " +
                "g=; read -r _ _ _ _ g _ 2>/dev/null < /proc/\$\$/stat; [ \"\$g\" = \"\$\$\" ] && echo \$\$ > \"\$p\" 2>/dev/null; "
        const val RUN = "(exec \"\$@\")"
        const val SOURCE_THEN_RUN = "f=\$1; shift; . \"\$f\" && (exec \"\$@\")"

        /** Prints the sourced value, then whether the env file is already gone when the argv runs. */
        const val SHOW_VALUE = "printf '%s|' \"\$STEP_VALUE\"; [ -e step.env ] && printf present || printf gone"
    }
}
