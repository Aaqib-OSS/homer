package homer.app.infra

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.EnvConstants
import homer.app.fixtures.TestFixtures
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

class AgentScannerTest {
    private val root: Path = Files.createTempDirectory("agent-scanner-test")

    @AfterEach
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    private fun scanner(
        maxConfigFileBytes: Long = 1024 * 1024,
        sysEnvResolver: SysEnvResolver = SysEnvResolver(),
        allowed: Set<String> = emptySet(),
        logger: Logger = TestFixtures.mockLogger(),
    ): AgentScanner {
        val inputChecks = AgentInputChecks(logger, ParamValidator())
        val validator =
            AgentDefinitionValidator(logger, setOf("local"), { emptyList() }, AgentInputResolver(), InputSchemaValidator(), inputChecks)
        return AgentScanner(
            root.toString(),
            logger,
            sysEnvResolver,
            AgentSysEnvAllowlist(allowed),
            maxConfigFileBytes,
            validator,
            inputChecks,
        )
    }

    private fun sysEnvYaml(
        name: String,
        value: String,
    ) = agentYaml(name) + "env:\n  TOKEN: \"$value\"\n"

    /** A host environment holding [HOST_SECRET] under `REPO_TOKEN`, recording every name it is asked for. */
    private class RecordingHost {
        val reads = mutableListOf<String>()
        val resolver = SysEnvResolver { name -> reads.add(name).let { mapOf("REPO_TOKEN" to HOST_SECRET)[name] } }
    }

    private fun define(
        rel: String,
        yaml: String,
    ) {
        val dir = Files.createDirectories(root.resolve(rel))
        Files.writeString(dir.resolve("agent.yaml"), yaml)
    }

    private fun agentYaml(name: String) = "name: $name\ncommands:\n  - !<cmd>\n    cmd: [\"echo\"]\n"

    private fun hookedYaml(
        name: String,
        target: String,
    ) = agentYaml(name) + "hooks:\n  - FINISHED: $target\n"

    @Test
    fun `loads nested agents keyed by their relative directory and injects HMR_AGENT_DIR`() {
        define("parent", agentYaml("parent"))
        define("parent/nodes/child", agentYaml("child"))

        val scan = scanner().scan()

        assertEquals(mapOf("parent" to "parent", "child" to "parent/nodes/child"), scan.sourceDirs)
        assertEquals(setOf("parent", "parent/nodes/child"), scan.presentDirNames)
        assertEquals(
            root
                .resolve("parent/nodes/child")
                .toAbsolutePath()
                .normalize()
                .toString(),
            scan.parsed["child"]?.env?.get(EnvConstants.AGENT_DIR),
        )
    }

    @Test
    fun `a name declared by two directories fails every claimant`() {
        define("one", agentYaml("dup"))
        define("two", agentYaml("dup"))

        val scan = scanner().scan()

        assertTrue(scan.parsed.isEmpty())
        assertEquals(setOf("one", "two"), scan.presentDirNames)
        assertTrue(scan.errors.all { "declared by multiple directories" in it })
    }

    @Test
    fun `a definition over the size cap is skipped with an error`() {
        define("big", agentYaml("big"))

        val scan = scanner(maxConfigFileBytes = 8).scan()

        assertTrue(scan.parsed.isEmpty())
        assertEquals(1, scan.errors.size)
    }

    @Test
    fun `a missing search path scans as empty`() {
        root.toFile().deleteRecursively()
        assertEquals(AgentScan(emptyMap(), emptyMap(), emptySet(), emptyList()), scanner().scan())
    }

    @Test
    fun `an agent whose hook names an unknown agent is skipped and the rest still load`() {
        define("declarer", hookedYaml("declarer", "ghost"))
        define("plain", agentYaml("plain"))

        val scan = scanner().scan()

        assertEquals(setOf("plain"), scan.parsed.keys)
        // The definition is skipped, not the directory: its files are still on disk.
        assertEquals(setOf("declarer", "plain"), scan.presentDirNames)
        assertTrue(
            scan.errors.any { "Agent 'declarer': hook target 'ghost' is not a loaded agent" in it },
            scan.errors.toString(),
        )
    }

    @Test
    fun `a hook cycle rejects every agent on it`() {
        define("a", hookedYaml("a", "b"))
        define("b", hookedYaml("b", "a"))

        val scan = scanner().scan()

        assertTrue(scan.parsed.isEmpty())
        assertEquals(2, scan.errors.count { "hook cycle" in it }, scan.errors.toString())
    }

    @Test
    fun `a valid hook chain loads and keeps its target`() {
        define("first", hookedYaml("first", "second"))
        define("second", agentYaml("second"))

        val scan = scanner().scan()

        assertEquals(setOf("first", "second"), scan.parsed.keys)
        assertEquals(
            listOf("second"),
            scan.parsed
                .getValue("first")
                .hooks
                .map { it.agent },
        )
        assertEquals(emptyList(), scan.errors)
    }

    @Test
    fun `an env naming an unlisted host variable is skipped, naming it, never reading or logging its value`() {
        define("leaky", sysEnvYaml("leaky", "Bearer @SYSENV:REPO_TOKEN"))
        define("plain", agentYaml("plain"))
        val host = RecordingHost()
        val logback = LoggerFactory.getLogger("agent-scanner-sysenv-denial") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logback.addAppender(appender)

        val scan =
            try {
                scanner(sysEnvResolver = host.resolver, allowed = setOf("OTHER"), logger = logback).scan()
            } finally {
                logback.detachAppender(appender)
            }

        assertEquals(setOf("plain"), scan.parsed.keys)
        assertEquals(setOf("leaky", "plain"), scan.presentDirNames)
        assertEquals(emptyList(), host.reads)
        val error = scan.errors.single()
        assertTrue("Agent 'leaky' in leaky" in error && "'REPO_TOKEN'" in error && "agentSysEnvAllowlist" in error, error)
        val logged = appender.list.map { it.formattedMessage }
        assertTrue(logged.any { "'REPO_TOKEN'" in it }, logged.toString())
        assertFalse((scan.errors + logged).any { HOST_SECRET in it }, "the value must appear in no error or log line")
    }

    @Test
    fun `an unlisted variable that is unset is refused exactly like a set one, so the refusal is no existence probe`() {
        define("unset", sysEnvYaml("unset", "@SYSENV:NEVER_SET"))
        define("set", sysEnvYaml("set", "@SYSENV:REPO_TOKEN"))
        val host = RecordingHost()

        val scan = scanner(sysEnvResolver = host.resolver).scan()

        assertTrue(scan.parsed.isEmpty())
        assertEquals(emptyList(), host.reads)
        val (setError, unsetError) = scan.errors.sorted().let { it[0] to it[1] }
        assertEquals(
            setError.replace("set", "X").replace("REPO_TOKEN", "V"),
            unsetError.replace("unset", "X").replace("NEVER_SET", "V"),
        )
    }

    @Test
    fun `a listed host variable resolves into the agent env`() {
        define("listed", sysEnvYaml("listed", "Bearer @SYSENV:REPO_TOKEN"))
        val host = RecordingHost()

        val scan = scanner(sysEnvResolver = host.resolver, allowed = setOf("REPO_TOKEN")).scan()

        assertEquals(emptyList(), scan.errors)
        assertEquals("Bearer $HOST_SECRET", scan.parsed.getValue("listed").env["TOKEN"])
    }

    @Test
    fun `an env with no @SYSENV reference loads under an empty allowlist`() {
        define("literal", sysEnvYaml("literal", "SYSENV:REPO_TOKEN @SYSENV:1BAD"))

        val scan = scanner(sysEnvResolver = RecordingHost().resolver).scan()

        assertEquals(emptyList(), scan.errors)
        assertEquals("SYSENV:REPO_TOKEN @SYSENV:1BAD", scan.parsed.getValue("literal").env["TOKEN"])
    }

    private companion object {
        const val HOST_SECRET = "host-secret-value-9f2c"
    }
}
