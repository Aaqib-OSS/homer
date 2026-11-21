package homer.app.runners.ssh

import homer.app.runners.RemoteShell
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
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
 * [RemoteCommandBuilder]'s POSIX single-quoting is the ssh runner's argv contract (ADR-0032): a token the remote shell
 * would re-interpret (quote, `$`, backtick, newline, glob, leading `-`) must reach the command as one argument, byte
 * for byte. These run the built string under a real `sh -c` and read the argv back; the example tests pin the string.
 */
class RemoteCommandBuilderPropertyTest {
    /** Tokens carry no NUL (the one byte argv cannot hold) and every shell metacharacter that matters. */
    private val tokens: Arb<String> = Arb.list(Arb.element(TOKEN_CHARS), 0..12).map { it.joinToString("") }

    /** The env file `export`s each name, so a generated name has to be a plain shell identifier. */
    private val envNames: Arb<String> = Arb.list(Arb.element(NAME_CHARS), 0..6).map { "V" + it.joinToString("") }

    private val envs: Arb<Map<String, String>> =
        Arb.bind(Arb.list(envNames, 0..3), Arb.list(tokens, 0..3)) { names, values ->
            names.zip(values).fold(linkedMapOf<String, String>()) { acc, (key, value) -> acc.apply { put(key, value) } }
        }

    private val argvs: Arb<List<String>> = Arb.list(tokens, 1..4)

    /** A command script that exits with a status or dies by a signal, and the code the caller must see: n, or 128 + n. */
    private val outcomes: Arb<Pair<String, Int>> =
        Arb.choice(
            Arb.int(0..255).map { "exit $it" to it },
            Arb.element(SIGNALS.entries.toList()).map { (name, number) -> "kill -s $name \$\$" to 128 + number },
        )

    @Test
    fun `every generated argument and env value survives build and sh -c`(
        @TempDir tmp: Path,
    ) {
        assumeTrue(Files.isExecutable(Path.of(SH)), "$SH is required to run the built command")
        // The workdir needs quoting too: a space and a single quote are the two the builder escapes.
        val workdir = Files.createDirectories(tmp.resolve("wo rk's dir"))

        // Each check spawns several processes (sh, the env file's rm); macOS under a full test run needs the room.
        runTest(timeout = 5.minutes) {
            checkAll(100, envs, argvs) { env, args ->
                val (argv, expected) = probe(env, args)
                assertEquals(
                    expected,
                    fields(listOf(SH, "-c", RemoteCommandBuilder.build(workdir.toString(), writeEnvFile(tmp, env), argv))),
                    "build lost an argument or env value for argv $argv",
                )
                assertNoEnvFileLeft(tmp)
            }
        }
    }

    @Test
    fun `every generated argument and env value survives buildReaping and sh -c`(
        @TempDir tmp: Path,
    ) {
        assumeTrue(Files.isExecutable(Path.of(SH)), "$SH is required to run the built command")
        val workdir = Files.createDirectories(tmp.resolve("wo rk's dir"))
        val pgidFile = tmp.resolve("reap/cmd-1.pgid").toString()

        // Each check spawns several processes (sh, the env file's rm); macOS under a full test run needs the room.
        runTest(timeout = 5.minutes) {
            checkAll(100, envs, argvs) { env, args ->
                val (argv, expected) = probe(env, args)
                for ((name, launch) in reapingLaunchers) {
                    val command = RemoteCommandBuilder.buildReaping(workdir.toString(), writeEnvFile(tmp, env), argv, pgidFile)
                    assertEquals(expected, fields(launch(command)), "buildReaping $name lost an argument or env value for argv $argv")
                    assertNoEnvFileLeft(tmp)
                }
            }
        }
    }

    @Test
    fun `every exit status and signal death reaches the caller through buildReaping`(
        @TempDir tmp: Path,
    ) {
        assumeTrue(Files.isExecutable(Path.of(SH)), "$SH is required to run the built command")
        val workdir = Files.createDirectories(tmp.resolve("wo rk's dir"))
        val pgidFile = tmp.resolve("reap/cmd-1.pgid").toString()

        runTest(timeout = 5.minutes) {
            checkAll(100, envs, outcomes) { env, (script, expected) ->
                for ((name, launch) in reapingLaunchers) {
                    val argv = listOf("sh", "-c", script)
                    val command = RemoteCommandBuilder.buildReaping(workdir.toString(), writeEnvFile(tmp, env), argv, pgidFile)
                    assertEquals(expected, exitOf(launch(command)), "buildReaping $name reported the wrong code for `$script`")
                    assertNoEnvFileLeft(tmp)
                }
            }
        }
    }

    /**
     * How the reaping command is run: its wrapper alone on every host, and the whole line under a login shell
     * that leads its session where util-linux `setsid -w` exists, as SshRunner's probe requires (ADR-0041).
     */
    private val reapingLaunchers: Map<String, (String) -> List<String>> by lazy {
        buildMap {
            put("wrapper alone") { command -> wrapperAlone(command) }
            if (setsidCanFormGroup()) put("under setsid -w", ::underLoginSession)
        }
    }

    /** Writes [env] as the env file the built command sources, or none for an empty env, as SshShellExecutor does. */
    private fun writeEnvFile(
        tmp: Path,
        env: Map<String, String>,
    ): String? {
        if (env.isEmpty()) return null
        val file = Files.createTempFile(tmp, "env-", ".env").toString()
        Files.writeString(Path.of(file), RemoteShell.envFileContent(file, env))
        return file
    }

    /**
     * The argv to hand the builder, and what running it must print: one `printf '%s\0'` field per
     * env value (read by name, so the sourced env file is exercised) followed by one per
     * generated argument (read through `"$@"`, so they are argv elements and not re-parsed).
     */
    private fun probe(
        env: Map<String, String>,
        args: List<String>,
    ): Pair<List<String>, List<String>> {
        val script = "printf '%s\\0' " + env.keys.joinToString(" ") { "\"\$$it\"" } + " \"\$@\""
        return listOf("sh", "-c", script, PROBE) + args to env.values.toList() + args
    }

    /** The sourced env file removes itself before the command runs, so none is left once it has. */
    private fun assertNoEnvFileLeft(tmp: Path) {
        val left = Files.list(tmp).use { files -> files.filter { it.fileName.toString().startsWith("env-") }.toList() }
        assertEquals(emptyList(), left, "the env file outlived the command")
    }

    /** Runs [argv] and splits its stdout on the NUL separators the probe prints. */
    private fun fields(argv: List<String>): List<String> {
        val process = ProcessBuilder(argv).start()
        val stdout = process.inputStream.readBytes().decodeToString()
        val stderr = process.errorStream.readBytes().decodeToString()
        val exit = process.waitFor()
        assertEquals(0, exit, "the built command failed with $exit: $stderr")
        return stdout.split('\u0000').dropLast(1)
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

        /** `$0` of the probe `sh -c`; never printed, so only `$@` — the real argv — is read back. */
        const val PROBE = "homer-probe"

        val TOKEN_CHARS =
            listOf(
                'a',
                'B',
                '7',
                ' ',
                '\t',
                '\n',
                '\'',
                '"',
                '`',
                '$',
                '\\',
                '*',
                '?',
                ';',
                '|',
                '&',
                '<',
                '>',
                '(',
                ')',
                '{',
                '}',
                '[',
                ']',
                '!',
                '~',
                '#',
                '%',
                '=',
                ':',
                ',',
                '-',
                '.',
                '/',
                'é',
                '☃',
            )

        val NAME_CHARS = listOf('a', 'Z', '0', '_')

        /** Signals whose numbers Linux and macOS share and whose default action dumps no core. */
        val SIGNALS = mapOf("HUP" to 1, "INT" to 2, "KILL" to 9, "PIPE" to 13, "ALRM" to 14, "TERM" to 15)
    }
}
