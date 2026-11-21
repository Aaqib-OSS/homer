package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.models.Agent
import homer.app.models.ClaudeCmdOptions
import homer.app.models.Command
import homer.app.models.Cooldown
import homer.app.models.LlmPrompt
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.ResultEnvParam
import homer.app.models.SrcType
import homer.app.models.Tags
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentInputChecksTest {
    private val logger = TestFixtures.mockLogger()
    private val checks = AgentInputChecks(logger, ParamValidator())
    private val errors = mutableListOf<String>()

    private fun agentWith(vararg inputs: Param) = AgentFixtures.simpleAgent(name = "a").copy(inputs = inputs.toList())

    @Test
    fun `reserved and malformed envNames are refused`() {
        assertFalse(checks.checkInputEnvNames(agentWith(Param(paramName = "p", envName = "PATH")), errors))
        assertFalse(checks.checkInputEnvNames(agentWith(Param(paramName = "p", envName = "1BAD")), errors))
        assertFalse(checks.checkInputEnvNames(agentWith(Param(paramName = "Authorization", src = ParamSource.HEADER)), errors))
        assertTrue(checks.checkInputEnvNames(agentWith(Param(paramName = "p", envName = "GOOD")), mutableListOf()))
        assertEquals(3, errors.size)
    }

    @Test
    fun `tags fromInputs must name a declared, non-secret input with a tag-key paramName`() {
        val base = agentWith(Param(paramName = "repo"), Param(paramName = "tok", secret = true))
        assertTrue(checks.checkTags(base.copy(tags = Tags(fromInputs = listOf("repo"))), errors))
        assertFalse(checks.checkTags(base.copy(tags = Tags(fromInputs = listOf("missing"))), errors))
        assertFalse(checks.checkTags(base.copy(tags = Tags(fromInputs = listOf("tok"))), errors))
        assertEquals(
            listOf(
                "Agent 'a': tags.fromInputs entry 'missing' names no declared input",
                "Agent 'a': tags.fromInputs entry 'tok' names the secret input 'tok'; a run tag is exported in the clear",
            ),
            errors,
        )
    }

    @Test
    fun `tags runtime keys need the key charset and must not overlap fromInputs`() {
        val base = agentWith(Param(paramName = "repo"))
        assertTrue(checks.checkTags(base.copy(tags = Tags(runtime = listOf("pr"))), errors))
        assertFalse(checks.checkTags(base.copy(tags = Tags(runtime = listOf("bad key"))), errors))
        assertFalse(checks.checkTags(base.copy(tags = Tags(fromInputs = listOf("repo"), runtime = listOf("repo"))), errors))
        assertEquals(2, errors.size)
    }

    @Test
    fun `duplicate envNames and paramNames are refused`() {
        assertFalse(
            checks.checkInputUniqueness(agentWith(Param(paramName = "a", envName = "X"), Param(paramName = "b", envName = "X")), errors),
        )
        assertFalse(
            checks.checkInputUniqueness(agentWith(Param(paramName = "a", envName = "X"), Param(paramName = "a", envName = "Y")), errors),
        )
        assertEquals(2, errors.size)
    }

    @Test
    fun `a result captured into a reserved envName is refused`() {
        val agent =
            AgentFixtures.simpleAgent(name = "a").copy(
                commands =
                    listOf(
                        Command.Cmd(cmd = listOf("echo"), resultEnvParam = listOf(ResultEnvParam(src = SrcType.STD_OUT, envName = "PATH"))),
                    ),
            )
        assertFalse(checks.checkCommandBindings(agent, errors))
        assertTrue(checks.checkCommandBindings(AgentFixtures.simpleAgent(name = "a"), mutableListOf()))
    }

    private fun capturingStep(
        label: String,
        envName: String,
    ) = Command.Cmd(
        cmd = listOf("echo"),
        label = label,
        resultEnvParam = listOf(ResultEnvParam(src = SrcType.STD_OUT, envName = envName)),
    )

    /** A valid capture on the command, and the given capture on an `onStop:` step. */
    private fun agentStoppingWithCapture(envName: String) =
        AgentFixtures.simpleAgent(name = "a").copy(
            commands = listOf(capturingStep(label = "run", envName = "OK")),
            onStop = listOf(capturingStep(label = "save", envName = envName)),
        )

    @Test
    fun `an onStop step capturing into a reserved envName is refused, naming the step and the key`() {
        assertFalse(checks.checkCommandBindings(agentStoppingWithCapture("PATH"), errors))
        // Threat model (Secrets): the message carries the step and the key name, never a captured value.
        assertEquals(
            listOf("Agent 'a': onStop step 'save' captures a result into invalid envName 'PATH'"),
            errors,
        )
    }

    @Test
    fun `an onStop capture into a Homer token or an LD_ name is refused`() {
        assertFalse(checks.checkCommandBindings(agentStoppingWithCapture("HMR_CALLBACK_TOKEN"), errors))
        assertFalse(checks.checkCommandBindings(agentStoppingWithCapture("LD_PRELOAD"), errors))
        assertEquals(2, errors.size)
    }

    @Test
    fun `an onStop capture into a non-identifier name is refused`() {
        assertFalse(checks.checkCommandBindings(agentStoppingWithCapture("1A-B"), errors))
        assertTrue(errors.single().contains("invalid envName '1A-B'"), errors.single())
    }

    @Test
    fun `an onStop capture into a valid name loads`() {
        assertTrue(checks.checkCommandBindings(agentStoppingWithCapture("SAVED_REPORT"), errors))
        assertEquals(emptyList(), errors)
    }

    /** An agent whose commands are the given claude steps. */
    private fun claudeAgent(vararg commands: Command.Claude) = Agent(name = "a", commands = commands.toList())

    private fun claudeCommand(
        options: ClaudeCmdOptions,
        label: String = "claude",
        prompt: String = "Hello Claude",
    ) = Command.Claude(prompt = LlmPrompt.Prompt(prompt), options = options, label = label)

    @Test
    fun `a claude step that skips permissions with only allowedTools warns per command, without echoing the prompt`() {
        val agent =
            claudeAgent(
                claudeCommand(
                    ClaudeCmdOptions(dangerouslySkipPermissions = true, allowedTools = listOf("ReadOnlyName")),
                    label = "verify",
                    prompt = "PROMPT-SHOULD-NOT-LEAK",
                ),
                claudeCommand(
                    ClaudeCmdOptions(permissionMode = "bypassPermissions", allowedTools = listOf("Read")),
                    label = "ship",
                ),
            )

        checks.warnOnUncontainedClaudeTools(agent)

        // The mock records the format and its arguments as passed, so the fields are asserted
        // on the format and the command on the label argument.
        val formats = mutableListOf<String>()
        val names = mutableListOf<String>()
        val labels = mutableListOf<String>()
        verify(exactly = 2) { logger.warn(capture(formats), capture(names), capture(labels)) }
        assertEquals(listOf("a", "a"), names)
        assertEquals(listOf("verify", "ship"), labels)
        assertTrue(formats.all { it.contains("allowedTools") && it.contains("disallowedTools: [Agent, Task]") }, formats.toString())
        // Threat model (Secrets): the message names the command and the fields, never a prompt or option value.
        assertTrue(
            (formats + names + labels).none { it.contains("PROMPT-SHOULD-NOT-LEAK") || it.contains("ReadOnlyName") },
            formats.toString(),
        )
    }

    @Test
    fun `a contained, non-bypassing or tool-unlimited claude step does not warn`() {
        val bypassing = ClaudeCmdOptions(dangerouslySkipPermissions = true, allowedTools = listOf("Read"))
        val agent =
            claudeAgent(
                claudeCommand(bypassing.copy(tools = listOf("Read", "Grep"))),
                claudeCommand(bypassing.copy(disallowedTools = listOf("Agent", "Task"))),
                claudeCommand(ClaudeCmdOptions(dangerouslySkipPermissions = true)),
                claudeCommand(ClaudeCmdOptions(permissionMode = "plan", allowedTools = listOf("Read"))),
                claudeCommand(ClaudeCmdOptions(permissionMode = "plan", dangerouslySkipPermissions = true, allowedTools = listOf("Read"))),
                claudeCommand(ClaudeCmdOptions(allowedTools = listOf("Read"))),
            )

        checks.warnOnUncontainedClaudeTools(agent)

        verify(exactly = 0) { logger.warn(any<String>(), any<Any>(), any<Any>()) }
    }

    @Test
    fun `an input templated into the cooldown key warns like the lock key`() {
        // The rendered key decides which pacing window a run lands in, so a caller-chosen
        // input chooses the subject — the steering class the lock key is warned for.
        val agent =
            agentWith(Param(paramName = "mr", envName = "MR"))
                .copy(cooldown = Cooldown(minInterval = "1m", key = "mr-{{MR}}"))

        checks.warnOnInputSteering(agent)

        val names = mutableListOf<String>()
        val steered = mutableListOf<String>()
        verify(exactly = 1) { logger.warn(any<String>(), capture(names), capture(steered)) }
        assertEquals("a", names.single())
        assertTrue(steered.single().contains("cooldown.key uses input '{{MR}}'"), steered.toString())
    }

    @Test
    fun `a cooldown key over the agent env or a literal steers no input`() {
        checks.warnOnInputSteering(
            agentWith(Param(paramName = "mr", envName = "MR"))
                .copy(cooldown = Cooldown(minInterval = "1m", key = "mr-{{TEAM}}")),
        )
        checks.warnOnInputSteering(
            agentWith(Param(paramName = "mr", envName = "MR"))
                .copy(cooldown = Cooldown(minInterval = "1m", key = "nightly")),
        )

        verify(exactly = 0) { logger.warn(any<String>(), any<Any>(), any<Any>()) }
    }
}
