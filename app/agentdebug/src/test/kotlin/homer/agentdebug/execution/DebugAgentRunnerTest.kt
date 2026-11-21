package homer.agentdebug.execution

import homer.agentdebug.ConfigLoader
import homer.app.models.Agent
import homer.app.models.Command
import homer.app.models.ResultEnvParam
import homer.app.models.SrcType
import homer.app.models.When
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real (non-dry) debug runs: skip rules, `when` outcomes, failure stop and the summary line. */
class DebugAgentRunnerTest {
    @TempDir
    lateinit var tempDir: Path

    private fun sh(
        script: String,
        label: String = "cmd",
        continueIfFail: Boolean = false,
        whenClause: When? = null,
        resultEnvParam: List<ResultEnvParam> = emptyList(),
    ) = Command.Cmd(
        cmd = listOf("sh", "-c", script),
        label = label,
        continueIfFail = continueIfFail,
        whenClause = whenClause,
        resultEnvParam = resultEnvParam,
    )

    /** Runs [commands]; returns `run()`'s error flag and everything printed. */
    private fun run(
        commands: List<Command>,
        cmdIndex: Int? = null,
        keepWorkspace: Boolean = false,
        dryRun: Boolean = false,
    ): Pair<Boolean, String> {
        val runner = runner(commands, cmdIndex, keepWorkspace, dryRun)
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true))
        val hadError =
            try {
                runBlocking { runner.run() }
            } finally {
                System.setOut(original)
            }
        return hadError to buffer.toString()
    }

    private fun runner(
        commands: List<Command>,
        cmdIndex: Int? = null,
        keepWorkspace: Boolean = false,
        dryRun: Boolean = false,
    ): DebugAgentRunner {
        val configPath = tempDir.resolve("app.yaml")
        configPath.writeText(
            """
            agentsSearchPath: "${tempDir.resolve("agents")}"
            workspaceRootPath: "${tempDir.resolve("ws")}"
            databasePath: "${tempDir.resolve("db.sqlite")}"
            """.trimIndent(),
        )
        val appConfig = ConfigLoader.loadAppConfig(configPath.toString(), emptyMap())
        return DebugAgentRunner(
            appConfig = appConfig,
            agent = Agent(name = "debug-agent", commands = commands),
            envOverrides = emptyMap(),
            inputMap = emptyMap(),
            dryRun = dryRun,
            keepWorkspace = keepWorkspace,
            cmdIndex = cmdIndex,
            logger = LoggerFactory.getLogger("agentdebug-test"),
        )
    }

    @Test
    fun `commands the debugger cannot run are skipped and the rest run to the summary`() {
        val (hadError, out) =
            run(
                listOf(
                    sh("echo hello", label = "greet"),
                    Command.RunAgent(agentName = "other"),
                    Command.LangGraph(module = "graph"),
                    Command.RunnerCmd(runner = "remote", execute = "true"),
                    sh("exit 3", label = "tolerated", continueIfFail = true),
                ),
            )

        assertFalse(hadError, out)
        assertContains(out, "[CMD 0/4] cmd \"greet\"")
        assertContains(out, "[OUT] hello")
        assertContains(out, "[SKIP] Sub-agent commands are not executed in debug mode")
        assertContains(out, "[SKIP] LangGraph commands need server-side dispatch/continuation; not executed in debug mode")
        assertContains(out, "[SKIP] runnerCmd targets a configured RunnerRegistry, which agentdebug does not build")
        assertContains(out, "[CMD 4] exit=3")
        assertContains(out, "[INFO] Done: 2 executed, 3 skipped")
    }

    @Test
    fun `a failing command stops the run and reports an error`() {
        val (hadError, out) = run(listOf(sh("exit 2"), sh("echo never-runs")))

        assertTrue(hadError, out)
        assertContains(out, "[ERROR] Command failed with exit=2. Stopping.")
        assertContains(out, "[INFO] Done: 1 executed, 0 skipped")
        assertFalse("never-runs" in out, out)
    }

    @Test
    fun `a false when predicate skips the command and a broken one aborts the run`() {
        val (hadError, out) =
            run(
                listOf(
                    sh("echo skipped-body", whenClause = When(When.Operator.ALL, listOf("false"))),
                    sh("echo aborted-body", whenClause = When(When.Operator.ALL, listOf("exit 7"))),
                    sh("echo never-runs"),
                ),
            )

        assertTrue(hadError, out)
        assertContains(out, "[SKIP when]")
        assertContains(out, "[ERROR] when-predicate evaluation failed for 'exit 7'")
        assertContains(out, "[INFO] Done: 0 executed, 1 skipped")
        assertFalse("skipped-body" in out || "aborted-body" in out || "never-runs" in out, out)
    }

    @Test
    fun `a result env param is captured and printed`() {
        val (hadError, out) =
            run(listOf(sh("printf captured", resultEnvParam = listOf(ResultEnvParam(SrcType.STD_OUT, "CAPTURED")))))

        assertFalse(hadError, out)
        assertContains(out, "[ENV ] CAPTURED=captured")
    }

    @Test
    fun `cmdIndex runs only that command and keepWorkspace keeps the workspace`() {
        val (hadError, out) = run(listOf(sh("echo first"), sh("echo second")), cmdIndex = 1, keepWorkspace = true)

        assertFalse(hadError, out)
        assertFalse("[OUT] first" in out, out)
        assertContains(out, "[OUT] second")
        assertContains(out, "[INFO] Done: 1 executed, 0 skipped")
        assertContains(out, "[INFO] Workspace kept at: ")
    }

    @Test
    fun `cmdIndex at or above the command count fails naming the range before anything runs`() {
        val (hadError, out) = run(listOf(sh("echo first"), sh("echo second")), cmdIndex = 2)

        assertTrue(hadError, out)
        assertContains(out, "[ERROR] --cmd 2 is out of range: agent 'debug-agent' has 2 commands (0..1)")
        assertFalse("echo first" in out || "echo second" in out, out)
        assertFalse("[INFO] Agent:" in out || "[INFO] Workspace:" in out, out)
        assertFalse(tempDir.resolve("ws").toFile().exists(), out)
    }

    @Test
    fun `a negative cmdIndex fails naming the range before anything runs`() {
        val (hadError, out) = run(listOf(sh("echo first"), sh("echo second")), cmdIndex = -1)

        assertTrue(hadError, out)
        assertContains(out, "[ERROR] --cmd -1 is out of range: agent 'debug-agent' has 2 commands (0..1)")
        assertFalse("echo first" in out || "echo second" in out, out)
        assertFalse(tempDir.resolve("ws").toFile().exists(), out)
    }

    @Test
    fun `an out-of-range cmdIndex fails in a dry run before anything is printed`() {
        val (hadError, out) = run(listOf(sh("echo first"), sh("echo second")), cmdIndex = 5, dryRun = true)

        assertTrue(hadError, out)
        assertContains(out, "[ERROR] --cmd 5 is out of range: agent 'debug-agent' has 2 commands (0..1)")
        assertFalse("[DRY ]" in out, out)
    }

    @Test
    fun `a cmdIndex against an agent with no commands fails naming no range`() {
        val (hadError, out) = run(emptyList(), cmdIndex = 0)

        assertTrue(hadError, out)
        assertContains(out, "[ERROR] --cmd 0 is out of range: agent 'debug-agent' has 0 commands")
        assertFalse("(0.." in out, out)
    }
}
