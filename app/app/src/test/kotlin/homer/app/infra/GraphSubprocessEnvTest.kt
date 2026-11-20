package homer.app.infra

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** The environment a `homer_langgraph` inspect or state subprocess gets (ADR-0027 amendment of 2026-11-20). */
class GraphSubprocessEnvTest {
    @TempDir
    lateinit var tempDir: Path

    private val secrets =
        mapOf(
            "AWS_SECRET_ACCESS_KEY" to "server-aws-secret",
            "HOMER_DB_PASSWORD" to "server-db-password",
            "HOMER_WORKERS_TOKEN" to "server-runner-token",
        )

    @Test
    fun `a secret-shaped server variable is not in the subprocess env`() {
        val env = graphSubprocessEnv(secrets + ("PATH" to "/usr/bin"), emptyMap())

        secrets.keys.forEach { assertNull(env[it], "$it must not reach the subprocess") }
        assertEquals("/usr/bin", env["PATH"])
    }

    @Test
    fun `a server PYTHONPATH and PYTHONWARNINGS are dropped`() {
        val env = graphSubprocessEnv(mapOf("PYTHONPATH" to "/srv/server-only", "PYTHONWARNINGS" to "error"), emptyMap())

        assertEquals(mapOf("PYTHONWARNINGS" to "ignore"), env)
    }

    @Test
    fun `every allowlisted server variable passes through`() {
        val allowlisted =
            mapOf(
                "PATH" to "/usr/local/bin:/usr/bin",
                "HOME" to "/home/homer",
                "LANG" to "C.UTF-8",
                "LC_ALL" to "C.UTF-8",
                "LC_CTYPE" to "en_US.UTF-8",
                "TMPDIR" to "/var/tmp",
                "VIRTUAL_ENV" to "/opt/venv",
                "PYTHONHOME" to "/opt/python",
            )

        assertEquals(allowlisted + ("PYTHONWARNINGS" to "ignore"), graphSubprocessEnv(allowlisted + secrets, emptyMap()))
    }

    @Test
    fun `the agent env overlays the allowlisted server env`() {
        val env =
            graphSubprocessEnv(
                mapOf("HOME" to "/home/homer", "PATH" to "/usr/bin"),
                mapOf("HOME" to "/agent/home", "PYTHONPATH" to "/app/agents/demo", "PYTHONWARNINGS" to "default"),
            )

        assertEquals(
            mapOf("HOME" to "/agent/home", "PATH" to "/usr/bin", "PYTHONPATH" to "/app/agents/demo", "PYTHONWARNINGS" to "default"),
            env,
        )
    }

    @Test
    fun `an agent may declare a variable whose name the allowlist drops from the server`() {
        val env = graphSubprocessEnv(secrets, mapOf("HOMER_DB_PASSWORD" to "agent-declared"))

        assertEquals("agent-declared", env["HOMER_DB_PASSWORD"])
        assertNull(env["AWS_SECRET_ACCESS_KEY"])
    }

    @Test
    fun `a real subprocess sees only the allowlisted server env and the agent env`() =
        runBlocking {
            val python =
                executable(
                    """printf '%s|%s|%s|%s\n' "${'$'}{HOMER_DB_PASSWORD-unset}" "${'$'}{LC_ALL-unset}" """ +
                        """"${'$'}{PYTHONPATH-unset}" "${'$'}{PYTHONWARNINGS-unset}"""",
                )

            val outcome =
                runBoundedGraphSubprocess(
                    python = python.toString(),
                    args = listOf("inspect", "--module", "pkg.mod", "--json"),
                    env = mapOf("PYTHONPATH" to "/app/agents/demo"),
                    timeout = 10.seconds,
                    serverEnv = secrets + mapOf("LC_ALL" to "C.UTF-8", "PATH" to "/usr/bin:/bin"),
                )

            val exited = outcome as GraphSubprocessOutcome.Exited
            assertEquals("unset|C.UTF-8|/app/agents/demo|ignore", exited.stdout.trim())
        }

    @Test
    fun `the production default reads the real server env through the allowlist only`() =
        runBlocking {
            var childEnv: Map<String, String> = emptyMap()

            runBoundedGraphSubprocess(
                python = executable("exit 0").toString(),
                args = listOf("state", "--module", "pkg.mod", "--thread", "thread-1"),
                env = mapOf("HOMER_LG_STATE_DIR" to "/srv/state"),
                timeout = 10.seconds,
                startProcess = { builder -> builder.start().also { childEnv = builder.environment().toMap() } },
            )

            assertEquals(graphSubprocessEnv(System.getenv(), mapOf("HOMER_LG_STATE_DIR" to "/srv/state")), childEnv)
        }

    @Test
    fun `property - the subprocess env is exactly the allowlisted server env overlaid by the agent env`() =
        runTest {
            checkAll(serverEnvs, agentEnvs) { serverEnv, agentEnv ->
                val env = graphSubprocessEnv(serverEnv, agentEnv)

                agentEnv.forEach { (name, value) -> assertEquals(value, env[name], "agent $name must win") }
                serverEnv.filterKeys { it !in agentEnv }.forEach { (name, value) ->
                    if (allowedByOracle(name)) {
                        assertEquals(value, env[name], "allowlisted $name must pass through")
                    } else {
                        assertFalse(name in env && name != "PYTHONWARNINGS", "server $name must not pass through")
                    }
                }
                val expectedNames = serverEnv.keys.filter(::allowedByOracle) + agentEnv.keys + "PYTHONWARNINGS"
                assertEquals(expectedNames.toSet(), env.keys)
                assertEquals(agentEnv["PYTHONWARNINGS"] ?: "ignore", env["PYTHONWARNINGS"])
            }
        }

    /** Independent of the production matcher: the documented names plus the `LC_` locale prefix. */
    private fun allowedByOracle(name: String): Boolean =
        name in setOf("PATH", "HOME", "LANG", "TMPDIR", "VIRTUAL_ENV", "PYTHONHOME") || name.startsWith("LC_")

    private val names: Arb<String> =
        Arb.choice(
            Arb.element(
                "PATH",
                "HOME",
                "LANG",
                "LC_ALL",
                "LC_",
                "LC_CTYPE",
                "TMPDIR",
                "VIRTUAL_ENV",
                "PYTHONHOME",
                "PYTHONPATH",
                "PYTHONWARNINGS",
                "path",
                "PATHX",
                "XPATH",
                "LCALL",
                "lc_all",
                "HOME_",
                "AWS_SECRET_ACCESS_KEY",
                "HOMER_DB_PASSWORD",
            ),
            Arb.list(Arb.element("A", "Z", "_", "C", "L", "P", "1"), 1..12).map { it.joinToString("") },
        )

    private val values: Arb<String> = Arb.string(0..16)

    private val serverEnvs: Arb<Map<String, String>> = Arb.map(names, values, minSize = 0, maxSize = 12)

    private val agentEnvs: Arb<Map<String, String>> = Arb.map(names, values, minSize = 0, maxSize = 6)

    /** Writes an executable `python` stand-in into the per-test temp dir. */
    private fun executable(body: String): Path {
        val script = tempDir.resolve("python")
        script.writeText("#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        return script
    }
}
