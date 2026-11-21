package homer.agentdebug

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The root command: its help, and its `--config` / `--env` loading through the `list` subcommand. */
class MainTest {
    private fun capture(block: () -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    private fun configFile(tempDir: Path): Path {
        tempDir.resolve("agents/hello").createDirectories().resolve("agent.yaml").writeText(
            """
            name: hello
            description: fixture
            commands:
              - !<cmd>
                cmd: "echo hi"
            """.trimIndent(),
        )
        return tempDir.resolve("app.yaml").also {
            it.writeText(
                """
                agentsSearchPath: "${tempDir.resolve("agents")}"
                workspaceRootPath: "${tempDir.resolve("ws")}"
                databasePath: "${tempDir.resolve("db.sqlite")}"
                globalEnv:
                  TOKEN: "@SYSENV:AGENTDEBUG_MAIN_TEST_TOKEN"
                """.trimIndent(),
            )
        }
    }

    private fun cli() = AgentdebugCli().subcommands(ListSubcommand())

    @Test
    fun `list loads the config with the --env file resolving its @SYSENV references`(
        @TempDir tempDir: Path,
    ) {
        val config = configFile(tempDir)
        val env = tempDir.resolve("vars.env").also { it.writeText("AGENTDEBUG_MAIN_TEST_TOKEN=s3cr3t\n") }

        val output = capture { cli().parse(listOf("--config", config.toString(), "--env", env.toString(), "list")) }

        assertContains(output, "hello")
    }

    @Test
    fun `list without --env resolves @SYSENV from the host environment`(
        @TempDir tempDir: Path,
    ) {
        val config = configFile(tempDir)
        config.writeText(config.toFile().readText().replace("AGENTDEBUG_MAIN_TEST_TOKEN", "PATH"))

        val output = capture { cli().parse(listOf("--config", config.toString(), "list")) }

        assertContains(output, "hello")
    }

    @Test
    fun `the help has one usage line per registered subcommand and no other`() {
        val cli = agentdebugCli()

        val usages = usagesOf(cli.test("--help").stdout)

        assertEquals(
            cli.registeredSubcommands().map { it.commandName }.sorted(),
            usages.map(::subcommandOf).sorted(),
            usages.joinToString("\n"),
        )
    }

    @Test
    fun `the help's hash-password usage says it needs no --config`() {
        val usage = usagesOf(agentdebugCli().test("--help").stdout).single { subcommandOf(it) == "hash-password" }

        assertContains(usage, "needs no --config")
    }

    /** The hand-written usage block's entries, each rejoined across the help formatter's wrapping. */
    private fun usagesOf(help: String): List<String> =
        help
            .lines()
            .map { it.trim() }
            .dropWhile { it != "Usage:" }
            .drop(1)
            .takeWhile { it.isNotEmpty() }
            .joinToString(" ")
            .split(Regex("(?=agentdebug )"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun subcommandOf(usage: String): String = usage.split(" ").drop(1).first { it.matches(Regex("[a-z][a-z-]*")) }

    @Test
    fun `a subcommand without --config fails`() {
        val e = assertFailsWith<IllegalStateException> { cli().parse(listOf("list")) }

        assertEquals("--config is required for this subcommand", e.message)
    }
}
