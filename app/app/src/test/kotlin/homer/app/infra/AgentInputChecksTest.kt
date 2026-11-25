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
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `an input templated into a command's cmd is refused`() {
        val agent =
            claudeAgent(claudeCommand(ClaudeCmdOptions(), label = "run").copy(cmd = "{{BIN}}"))
                .copy(inputs = listOf(Param(paramName = "bin", envName = "BIN")))

        assertFalse(checks.checkCommandBindings(agent, errors))
        assertEquals(
            listOf("Agent 'a': command 'run' cmd references input '{{BIN}}'; an input must not choose the executable"),
            errors,
        )
    }

    @Test
    fun `an onStop step whose cmd references an input is refused, like a command step`() {
        val agent =
            claudeAgent(claudeCommand(ClaudeCmdOptions(), label = "run"))
                .copy(
                    inputs = listOf(Param(paramName = "bin", envName = "BIN")),
                    onStop = listOf(claudeCommand(ClaudeCmdOptions(), label = "stop").copy(cmd = "{{BIN}}")),
                )

        assertFalse(checks.checkCommandBindings(agent, errors))
        // Threat model (Secrets): the message names the step and the input's env key, never a value.
        assertEquals(
            listOf("Agent 'a': onStop step 'stop' cmd references input '{{BIN}}'; an input must not choose the executable"),
            errors,
        )
    }

    @Test
    fun `a captured result templated into a later LLM step's cmd is refused (CWE-78)`() {
        val agent =
            claudeAgent().copy(
                commands =
                    listOf(
                        capturingStep(label = "fetch", envName = "TOOL"),
                        claudeCommand(ClaudeCmdOptions(), label = "run").copy(cmd = "{{TOOL}}"),
                    ),
            )

        assertFalse(checks.checkCommandBindings(agent, errors))
        // Threat model (Secrets): the message names the step and the captured env key, never a value.
        assertEquals(
            listOf("Agent 'a': command 'run' cmd references captured result '{{TOOL}}'; a captured result must not choose the executable"),
            errors,
        )
    }

    @Test
    fun `an onStop capture templated into a command's cmd is refused (CWE-78)`() {
        val agent =
            claudeAgent(claudeCommand(ClaudeCmdOptions(), label = "run").copy(cmd = "/opt/{{SAVED}}/claude"))
                .copy(onStop = listOf(capturingStep(label = "save", envName = "SAVED")))

        assertFalse(checks.checkCommandBindings(agent, errors))
        assertEquals(
            listOf("Agent 'a': command 'run' cmd references captured result '{{SAVED}}'; a captured result must not choose the executable"),
            errors,
        )
    }

    @Test
    fun `a cmd templated on an author env name loads beside a capture of another name`() {
        val agent =
            claudeAgent().copy(
                env = mapOf("CLAUDE_BIN" to "/usr/local/bin/claude"),
                commands =
                    listOf(
                        capturingStep(label = "fetch", envName = "TOOL"),
                        claudeCommand(ClaudeCmdOptions(), label = "run").copy(cmd = "{{CLAUDE_BIN}}"),
                    ),
            )

        assertTrue(checks.checkCommandBindings(agent, errors))
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `any captured name anywhere in an LLM cmd is refused, and an uncaptured one loads (CWE-78)`() =
        runTest {
            checkAll(
                Arb.pattern("[A-Za-z_][A-Za-z0-9_]{0,12}").filter { !ReservedEnvNames.isReserved(it) && it != "OTHER" },
                Arb.pattern("[a-z/ .-]{0,8}"),
                Arb.pattern("[a-z/ .-]{0,8}"),
                Arb.boolean(),
            ) { captured, prefix, suffix, inStop ->
                fun agentWithCmd(name: String) =
                    claudeAgent(claudeCommand(ClaudeCmdOptions(), label = "run").copy(cmd = "$prefix{{$name}}$suffix")).let {
                        val capture = capturingStep(label = "fetch", envName = captured)
                        if (inStop) it.copy(onStop = listOf(capture)) else it.copy(commands = listOf(capture) + it.commands)
                    }
                assertFalse(checks.checkCommandBindings(agentWithCmd(captured), mutableListOf()), "cmd '$prefix{{$captured}}$suffix'")
                assertTrue(checks.checkCommandBindings(agentWithCmd("OTHER"), mutableListOf()), "cmd '$prefix{{OTHER}}$suffix'")
            }
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
        // on the format and the step's phase and label on their arguments.
        val formats = mutableListOf<String>()
        val names = mutableListOf<String>()
        val kinds = mutableListOf<String>()
        val labels = mutableListOf<String>()
        verify(exactly = 2) { logger.warn(capture(formats), capture(names), capture(kinds), capture(labels)) }
        assertEquals(listOf("a", "a"), names)
        assertEquals(listOf("command", "command"), kinds)
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

        verify(exactly = 0) { logger.warn(any<String>(), any<Any>(), any<Any>(), any<Any>()) }
    }

    @Test
    fun `an onStop claude step that skips permissions with only allowedTools warns as a stop step`() {
        val stopStep =
            claudeCommand(
                ClaudeCmdOptions(dangerouslySkipPermissions = true, allowedTools = listOf("Read")),
                label = "ship",
            )
        val agent =
            claudeAgent(claudeCommand(ClaudeCmdOptions(), label = "run"))
                .copy(onStop = listOf(stopStep))

        checks.warnOnUncontainedClaudeTools(agent)

        val kinds = mutableListOf<String>()
        val labels = mutableListOf<String>()
        verify(exactly = 1) { logger.warn(any<String>(), any<Any>(), capture(kinds), capture(labels)) }
        assertEquals(listOf("ship"), labels)
        // The phase is what tells the operator to look under `onStop:`, not `commands:`.
        assertEquals(listOf("onStop step"), kinds)
    }

    @Test
    fun `the load error and the warning name an offending onStop step with the same phase tag`() {
        val agent =
            AgentFixtures.simpleAgent(name = "a").copy(
                commands = listOf(capturingStep(label = "run", envName = "OK")),
                onStop =
                    listOf(
                        capturingStep(label = "save", envName = "PATH"),
                        claudeCommand(
                            ClaudeCmdOptions(dangerouslySkipPermissions = true, allowedTools = listOf("Read")),
                            label = "ship",
                        ),
                    ),
            )

        assertFalse(checks.checkCommandBindings(agent, errors))
        assertTrue(errors.single().startsWith("Agent 'a': onStop step 'save'"), errors.toString())

        checks.warnOnUncontainedClaudeTools(agent)
        val kinds = mutableListOf<String>()
        verify(exactly = 1) { logger.warn(any<String>(), any<Any>(), capture(kinds), any<Any>()) }
        assertEquals(listOf("onStop step"), kinds)
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
    fun `an input templated into an onStop RunAgent target warns as a stop step`() {
        val agent =
            agentWith(Param(paramName = "target", envName = "TARGET"))
                .copy(onStop = listOf(Command.RunAgent(agentName = "{{TARGET}}", label = "hand-off")))

        checks.warnOnInputSteering(agent)

        val steered = mutableListOf<String>()
        verify(exactly = 1) { logger.warn(any<String>(), any<Any>(), capture(steered)) }
        assertEquals(
            listOf("agent onStop step 'hand-off' target uses input '{{TARGET}}'"),
            steered.single().split("; "),
        )
    }

    @Test
    fun `an input templated into an onStop claude option warns as a stop step`() {
        val agent =
            claudeAgent(claudeCommand(ClaudeCmdOptions(), label = "run"))
                .copy(
                    inputs = listOf(Param(paramName = "dir", envName = "EXTRA_DIR")),
                    onStop = listOf(claudeCommand(ClaudeCmdOptions(addDir = listOf("{{EXTRA_DIR}}")), label = "ship")),
                )

        checks.warnOnInputSteering(agent)

        val steered = mutableListOf<String>()
        verify(exactly = 1) { logger.warn(any<String>(), any<Any>(), capture(steered)) }
        assertEquals(
            listOf("claude onStop step 'ship' option 'addDir' uses input '{{EXTRA_DIR}}'"),
            steered.single().split("; "),
        )
    }

    @Test
    fun `a command RunAgent or claude step keeps the command wording`() {
        val agent =
            AgentFixtures.simpleAgent(name = "a").copy(
                commands =
                    listOf(
                        Command.RunAgent(agentName = "{{TARGET}}", label = "hand-off"),
                        claudeCommand(ClaudeCmdOptions(permissionMode = "{{MODE}}"), label = "verify"),
                    ),
                inputs = listOf(Param(paramName = "t", envName = "TARGET"), Param(paramName = "m", envName = "MODE")),
            )

        checks.warnOnInputSteering(agent)

        val steered = mutableListOf<String>()
        verify(exactly = 1) { logger.warn(any<String>(), any<Any>(), capture(steered)) }
        assertEquals(
            listOf(
                "agent command 'hand-off' target uses input '{{TARGET}}'",
                "claude command 'verify' option 'permissionMode' uses input '{{MODE}}'",
            ),
            steered.single().split("; "),
        )
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
