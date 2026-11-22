package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.AgentDispatchConfig
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.CronConfig
import homer.app.models.DEFAULT_INTERRUPT_GRACE_SECONDS
import homer.app.models.MAX_INTERRUPT_GRACE_SECONDS
import homer.app.models.MAX_ON_STOP_STEPS
import homer.app.models.OnTimeout
import homer.app.models.Param
import homer.app.models.ResultEnvParam
import homer.app.models.SrcType
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentDefinitionValidatorTest {
    private val logger = TestFixtures.mockLogger()
    private val validator =
        AgentDefinitionValidator(
            logger,
            runnerAliases = setOf("local"),
            runnerTagSets = { error("registry unavailable") },
            inputResolver = AgentInputResolver(),
            inputSchemaValidator = InputSchemaValidator(),
            inputChecks = AgentInputChecks(logger, ParamValidator()),
        )
    private val errors = mutableListOf<String>()

    @Test
    fun `an invalid agent name is reported and refused`() {
        assertNull(validator.checkAgentName(AgentFixtures.simpleAgent(name = "bad name"), "dir", errors))
        assertEquals(listOf("Invalid agent name 'bad name' in dir"), errors)
        assertEquals("ok", validator.checkAgentName(AgentFixtures.simpleAgent(name = "ok"), "dir", mutableListOf()))
    }

    @Test
    fun `a valid definition loads with its schedule enabled`() {
        assertEquals(false, validator.checkDefinition(AgentFixtures.simpleAgent(name = "a"), "dir", errors))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `no commands, an unknown runner or an invalid cron skip the agent`() {
        val agent = AgentFixtures.simpleAgent(name = "a")
        assertNull(validator.checkDefinition(agent.copy(commands = emptyList()), "dir", errors))
        assertNull(validator.checkDefinition(agent.copy(runner = "nowhere"), "dir", errors))
        assertNull(validator.checkDefinition(agent.copy(cron = CronConfig("not a cron")), "dir", errors))
        assertEquals(3, errors.size)
    }

    @Test
    fun `a schedule no required input can satisfy is disabled, not skipped`() {
        val agent = AgentFixtures.simpleAgent(name = "a").copy(cron = CronConfig("0 * * * *"), inputs = listOf(Param(paramName = "q")))
        assertEquals(true, validator.checkDefinition(agent, "dir", errors))
        assertEquals(1, errors.size)
    }

    @Test
    fun `a claude step that skips permissions with only allowedTools warns and still loads`() {
        val agent =
            AgentFixtures.claudeAgent(
                name = "a",
                options = ClaudeCmdOptions(dangerouslySkipPermissions = true, allowedTools = listOf("Read", "Grep")),
            )

        assertEquals(false, validator.checkDefinition(agent, "dir", errors))
        assertEquals(emptyList(), errors)
        verify { logger.warn(match<String> { it.contains("allowedTools") }, "a", "command", "claude") }
    }

    // ========== Graceful-stop policy (ADR-0108) ==========

    /** A one-step agent whose step is `onTimeout: stop`, plus the given `onStop:` steps. */
    private fun stoppableAgent(
        onStop: List<Command> = emptyList(),
        onTimeout: OnTimeout = OnTimeout.STOP,
    ) = AgentFixtures.simpleAgent(name = "a").copy(
        commands = listOf(Command.Cmd(cmd = listOf("run"), timeout = 60, onTimeout = onTimeout)),
        onStop = onStop,
    )

    private fun stopStep(grace: Long = DEFAULT_INTERRUPT_GRACE_SECONDS) =
        Command.Cmd(cmd = listOf("save"), timeout = 30, interruptGraceSeconds = grace)

    @Test
    fun `onTimeout stop with no onStop steps is refused`() {
        assertNull(validator.checkDefinition(stoppableAgent(), "dir", errors))

        assertTrue(errors.single().contains("declares no onStop steps"), errors.single())
    }

    @Test
    fun `onTimeout stop with an onStop step loads`() {
        assertEquals(false, validator.checkDefinition(stoppableAgent(onStop = listOf(stopStep())), "dir", errors))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a bare onStop loads, so a run may be stopped by its deadline alone`() {
        // No step opted in: the run can still be stopped by the runner's soft run deadline.
        val agent = stoppableAgent(onStop = listOf(stopStep()), onTimeout = OnTimeout.FAIL)

        assertEquals(false, validator.checkDefinition(agent, "dir", errors))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `an onStop step that itself declares onTimeout stop is refused`() {
        val agent =
            stoppableAgent(
                onStop = listOf(Command.Cmd(cmd = listOf("save"), timeout = 30, onTimeout = OnTimeout.STOP)),
            )

        assertNull(validator.checkDefinition(agent, "dir", errors))
        assertTrue(errors.single().contains("on an onStop step"), errors.single())
    }

    @Test
    fun `an onStop step capturing into a reserved envName is refused at load`() {
        val agent =
            stoppableAgent(
                onStop =
                    listOf(
                        Command.Cmd(
                            cmd = listOf("save"),
                            timeout = 30,
                            resultEnvParam = listOf(ResultEnvParam(src = SrcType.STD_OUT, envName = "PATH")),
                        ),
                    ),
            )

        assertNull(validator.checkDefinition(agent, "dir", errors))
        assertTrue(errors.single().contains("onStop step 'cmd' captures a result into invalid envName 'PATH'"), errors.single())
    }

    @Test
    fun `an interrupt grace outside the bound is refused on a regular and on a stop step`() {
        for (grace in listOf(-1L, MAX_INTERRUPT_GRACE_SECONDS + 1)) {
            val onRegular = stoppableAgent(onStop = listOf(stopStep())).copy(commands = listOf(stopStep(grace)))
            assertNull(validator.checkDefinition(onRegular, "dir", errors), "grace $grace on a regular step")

            val onStop = stoppableAgent(onStop = listOf(stopStep(grace)))
            assertNull(validator.checkDefinition(onStop, "dir", errors), "grace $grace on a stop step")
        }

        // Both ends of the bound are legal, and a grace is checked even when no step opted in.
        errors.clear()
        assertEquals(false, validator.checkDefinition(stoppableAgent(onStop = listOf(stopStep(0))), "dir", errors))
        assertEquals(
            false,
            validator.checkDefinition(
                stoppableAgent(onStop = listOf(stopStep(MAX_INTERRUPT_GRACE_SECONDS))),
                "dir",
                errors,
            ),
        )
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `more stop steps than the bound allows are refused`() {
        assertNull(
            validator.checkDefinition(stoppableAgent(onStop = List(MAX_ON_STOP_STEPS + 1) { stopStep() }), "dir", errors),
        )
        assertTrue(errors.single().contains("more than $MAX_ON_STOP_STEPS"), errors.single())

        errors.clear()
        assertEquals(
            false,
            validator.checkDefinition(stoppableAgent(onStop = List(MAX_ON_STOP_STEPS) { stopStep() }), "dir", errors),
        )
    }

    // ========== The runnerCmd and langgraph rules cover onStop steps too ==========

    /** A validator whose runner registry serves [aliases] alongside `local`. */
    private fun validatorServing(vararg aliases: String) =
        AgentDefinitionValidator(
            logger,
            runnerAliases = aliases.toSet() + "local",
            runnerTagSets = { error("registry unavailable") },
            inputResolver = AgentInputResolver(),
            inputSchemaValidator = InputSchemaValidator(),
            inputChecks = AgentInputChecks(logger, ParamValidator()),
        )

    /** A `runnerCmd` step targeting [runner], uploading [syncToRemote] when given. */
    private fun runnerCmdStep(
        runner: String = "local",
        syncToRemote: String? = null,
    ) = Command.RunnerCmd(
        runner = runner,
        execute = "echo hi",
        syncToRemote = listOfNotNull(syncToRemote),
        label = "sync",
    )

    @Test
    fun `an onStop runnerCmd off a local runner is refused with the command message`() {
        val validator = validatorServing("gpu-box")
        val asStopStep = stoppableAgent(onStop = listOf(runnerCmdStep())).copy(runner = "gpu-box")
        val asCommand =
            stoppableAgent(onStop = listOf(stopStep())).copy(commands = listOf(runnerCmdStep()), runner = "gpu-box")

        assertNull(validator.checkDefinition(asStopStep, "dir", errors))
        assertTrue(errors.single().contains("agents with runnerCmd must run on runner: local"), errors.single())

        val commandErrors = mutableListOf<String>()
        assertNull(validator.checkDefinition(asCommand, "dir", commandErrors))
        assertEquals(commandErrors, errors)
    }

    @Test
    fun `an onStop runnerCmd naming an unknown runner is refused with the command message`() {
        val asStopStep = stoppableAgent(onStop = listOf(runnerCmdStep(runner = "ghost")))
        val asCommand = stoppableAgent(onStop = listOf(stopStep())).copy(commands = listOf(runnerCmdStep(runner = "ghost")))

        assertNull(validator.checkDefinition(asStopStep, "dir", errors))
        assertTrue(errors.single().contains("references unknown runner 'ghost' in a runnerCmd"), errors.single())

        val commandErrors = mutableListOf<String>()
        assertNull(validator.checkDefinition(asCommand, "dir", commandErrors))
        assertEquals(commandErrors, errors)
    }

    @Test
    fun `an onStop runnerCmd sync path that is absolute, escaping or blank is refused`() {
        for (path in listOf("/etc/passwd", "../outside", "  ")) {
            errors.clear()
            assertNull(
                validator.checkDefinition(stoppableAgent(onStop = listOf(runnerCmdStep(syncToRemote = path))), "dir", errors),
                "onStop sync path '$path'",
            )
            assertTrue(errors.single().contains("has an invalid runnerCmd sync path '$path'"), errors.single())

            val commandErrors = mutableListOf<String>()
            assertNull(
                validator.checkDefinition(
                    stoppableAgent(onStop = listOf(stopStep())).copy(commands = listOf(runnerCmdStep(syncToRemote = path))),
                    "dir",
                    commandErrors,
                ),
                "command sync path '$path'",
            )
            assertEquals(commandErrors, errors, "sync path '$path'")
        }
    }

    @Test
    fun `an onStop runnerCmd rejection names the path, never an env value`() {
        val agent =
            stoppableAgent(onStop = listOf(runnerCmdStep(syncToRemote = "../outside")))
                .copy(env = mapOf("TOKEN" to "s3cr3t-value"))

        assertNull(validator.checkDefinition(agent, "dir", errors))
        assertTrue(errors.single().contains("'../outside'"), errors.single())
        assertFalse(errors.single().contains("s3cr3t-value"), errors.single())
    }

    @Test
    fun `a valid local onStop runnerCmd still loads`() {
        assertEquals(false, validator.checkDefinition(stoppableAgent(onStop = listOf(runnerCmdStep())), "dir", errors))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `a langgraph step only in onStop needs dispatch enabled`() {
        val agent = stoppableAgent(onStop = listOf(Command.LangGraph(module = "graphs.flow")))

        assertNull(validator.checkDefinition(agent, "dir", errors))
        assertTrue(errors.single().contains("has a langgraph command but dispatch.enabled is not true"), errors.single())

        errors.clear()
        assertEquals(
            false,
            validator.checkDefinition(agent.copy(dispatch = AgentDispatchConfig(enabled = true)), "dir", errors),
        )
        assertEquals(emptyList(), errors)
    }
}
