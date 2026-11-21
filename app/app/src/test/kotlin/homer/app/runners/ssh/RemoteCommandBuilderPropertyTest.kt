package homer.app.runners.ssh

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
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

/**
 * [RemoteCommandBuilder]'s POSIX single-quoting is the ssh runner's argv contract (ADR-0032): the
 * built string is executed by the remote login shell, so a token the shell would otherwise
 * re-interpret — a quote, `$`, a backtick, a newline, a glob, a leading `-` — must still reach the
 * command as one argument, byte for byte. The example tests pin the built string; these run the
 * built string under a real `sh -c` and read the argv back out of it (root CLAUDE.md §6).
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

    @Test
    fun `every generated argument and env value survives build and sh -c`(
        @TempDir tmp: Path,
    ) {
        assumeTrue(Files.isExecutable(Path.of(SH)), "$SH is required to run the built command")
        // The workdir needs quoting too: a space and a single quote are the two the builder escapes.
        val workdir = Files.createDirectories(tmp.resolve("wo rk's dir"))

        runTest {
            checkAll(100, envs, argvs) { env, args ->
                val (argv, expected) = probe(env, args)
                assertEquals(
                    expected,
                    fields(RemoteCommandBuilder.build(workdir.toString(), writeEnvFile(tmp, env), argv)),
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
        // buildReaping leads the command with `setsid -w`, gated by SshRunner's capability probe
        // (ADR-0041); a target whose setsid cannot form a group (busybox) never sees that form.
        assumeTrue(setsidCanFormGroup(), "$SETSID (util-linux) is required to run the reaping command")
        val workdir = Files.createDirectories(tmp.resolve("wo rk's dir"))
        val pgidFile = tmp.resolve("reap/cmd-1.pgid").toString()

        runTest {
            checkAll(100, envs, argvs) { env, args ->
                val (argv, expected) = probe(env, args)
                assertEquals(
                    expected,
                    fields(RemoteCommandBuilder.buildReaping(workdir.toString(), writeEnvFile(tmp, env), argv, pgidFile)),
                    "buildReaping lost an argument or env value for argv $argv",
                )
                assertNoEnvFileLeft(tmp)
            }
        }
    }

    /** Writes [env] as the env file the built command sources, or none for an empty env, as SshShellExecutor does. */
    private fun writeEnvFile(
        tmp: Path,
        env: Map<String, String>,
    ): String? {
        if (env.isEmpty()) return null
        val file = Files.createTempFile(tmp, "env-", ".env").toString()
        Files.writeString(Path.of(file), RemoteCommandBuilder.envFileContent(file, env))
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

    /** Runs [command] under a real `sh -c` and splits its stdout on the NUL separators the probe prints. */
    private fun fields(command: String): List<String> {
        val process = ProcessBuilder(SH, "-c", command).start()
        val stdout = process.inputStream.readBytes().decodeToString()
        val stderr = process.errorStream.readBytes().decodeToString()
        val exit = process.waitFor()
        assertEquals(0, exit, "the built command failed with $exit: $stderr")
        return stdout.split('\u0000').dropLast(1)
    }

    /** The same one-shot probe SshRunner gates the reaping form on: `setsid -w` must be util-linux's. */
    private fun setsidCanFormGroup(): Boolean =
        runCatching {
            val process = ProcessBuilder(SETSID, "-w", "true").start()
            process.inputStream.readBytes()
            process.errorStream.readBytes()
            process.waitFor() == 0
        }.getOrDefault(false)

    private companion object {
        const val SH = "/bin/sh"
        const val SETSID = "setsid"

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
    }
}
