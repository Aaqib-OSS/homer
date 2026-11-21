package homer.app.runners.kubernetes

import homer.app.runners.RemoteShell
import homer.app.runners.ssh.setsidCanFormGroup
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * The group launch's wrapper waits for the argv in a child, so the exec channel's exit status is the command's own:
 * n for an exit, 128 + n for a signal death, with or without `setsid -w` in front and an env file sourced (ADR-0041).
 */
class PodProcessControlPropertyTest {
    /** A command script that exits with a status or dies by a signal, and the code the caller must see: n, or 128 + n. */
    private val outcomes: Arb<Pair<String, Int>> =
        Arb.choice(
            Arb.int(0..255).map { "exit $it" to it },
            Arb.element(SIGNALS.entries.toList()).map { (name, number) -> "kill -s $name \$\$" to 128 + number },
        )

    @Test
    fun `every exit status and signal death reaches the caller through the group launch`(
        @TempDir tmp: Path,
    ) {
        assumeTrue(Files.isExecutable(Path.of(SH)), "$SH is required to run the built launch")
        val pgidFile = tmp.resolve(".homer/homer-exec-1.pgid").toString()

        runTest(timeout = 5.minutes) {
            checkAll(100, outcomes, Arb.boolean()) { (script, expected), withEnvFile ->
                for ((name, launch) in launchers) {
                    val argv = launch(pgidFile, envFile(tmp, withEnvFile), listOf("sh", "-c", script))
                    assertEquals(expected, exitOf(argv), "the launch $name reported the wrong code for `$script`")
                }
            }
        }
    }

    /**
     * The launch on a pod without `setsid -w`, its wrapper started directly, on every host; and where util-linux
     * `setsid -w` exists here, the launch with it, run as a pod exec runs it, so `setsid -w` forks and waits.
     */
    private val launchers: Map<String, (String, String?, List<String>) -> List<String>> by lazy {
        buildMap {
            val without = podControl(hasSetsidWait = false)
            put("without setsid") { pgidFile, envFile, argv -> without.groupLaunch(pgidFile, envFile, argv) }
            if (setsidCanFormGroup()) {
                val withSetsid = podControl(hasSetsidWait = true)
                put("under setsid -w") { pgidFile, envFile, argv -> asPodExec(withSetsid.groupLaunch(pgidFile, envFile, argv)) }
            }
        }
    }

    /** A fresh env file the launch sources and removes, or none. */
    private fun envFile(
        tmp: Path,
        wanted: Boolean,
    ): String? {
        if (!wanted) return null
        val file = Files.createTempFile(tmp, "env-", ".env").toString()
        Files.writeString(Path.of(file), RemoteShell.envFileContent(file, mapOf("STEP_VALUE" to "v")))
        return file
    }

    /** Runs [argv] with its output discarded and returns its exit code. */
    private fun exitOf(argv: List<String>): Int =
        ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor()

    private companion object {
        const val SH = "/bin/sh"

        /** Signals whose numbers Linux and macOS share and whose default action dumps no core. */
        val SIGNALS = mapOf("HUP" to 1, "INT" to 2, "KILL" to 9, "PIPE" to 13, "ALRM" to 14, "TERM" to 15)
    }
}
