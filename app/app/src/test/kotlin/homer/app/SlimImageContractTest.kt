package homer.app

import homer.app.fixtures.DockerfileStages
import homer.app.fixtures.DockerfileStages.Companion.apkPackages
import homer.app.fixtures.DockerfileStages.Stage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards what the slim server image promises (ADR-0094): the tools the server itself runs, and none
 * of the agent toolchain, which only the full `runner` stage built `FROM slim` adds.
 */
class SlimImageContractTest {
    private val dockerfile = DockerfileStages.app

    /** The agent toolchain: absent from the slim stage, installed by the full one. */
    private val toolchain = setOf("git", "git-lfs", "nodejs", "npm", "openssh-client", "glab", "jq", "unzip")

    @Test
    fun `the slim stage installs what the server runs and no agent toolchain`() {
        val slim = dockerfile.stage("slim")
        val packages = apkPackages(slim)
        val required = setOf("bash", "curl", "su-exec", "python3", "py3-pip")
        assertTrue(
            packages.containsAll(required),
            "the slim stage installs $packages; ADR-0094 promises $required (shell, entrypoint privilege drop, " +
                "health check, the server-side homer_langgraph inspect and state calls).",
        )
        assertTrue(
            packages.intersect(toolchain).isEmpty(),
            "the slim stage installs ${packages.intersect(toolchain)}, agent toolchain the slim image promises not " +
                "to ship (ADR-0094); add it to the full `runner` stage instead.",
        )
        assertTrue(slim.instructions.none { it.contains("npm install") }, "the slim stage must not install npm packages (ADR-0094).")
        assertTrue(
            slim.instructions.any { it.startsWith("RUN pip install") && it.contains("homer_langgraph") },
            "the slim stage no longer installs homer_langgraph, which the server runs for LangGraph inspect and state.",
        )
    }

    @Test
    fun `the image installs the Python runtime from the hash-pinned lock and never resolves`() {
        val slim = dockerfile.stage("slim")
        assertTrue(
            pipInstalls(slim).any { it.contains("--require-hashes") && it.contains("--no-deps") && it.endsWith("requirements.lock") },
            "the slim stage no longer installs homer_langgraph's dependencies from requirements.lock (ADR-0114).",
        )
        val resolving = dockerfile.stages.flatMap(::resolvingPipInstalls)
        assertTrue(
            resolving.isEmpty(),
            "`$resolving` resolves Python dependencies at build time, which on musl ends on old langgraph releases; " +
                "install them from requirements.lock with --require-hashes --no-deps (ADR-0114).",
        )
    }

    @Test
    fun `a pip install without --no-deps counts as resolving`() {
        val stage =
            DockerfileStages(
                "FROM a AS slim\nRUN pip install --break-system-packages /src && pip install --no-deps /x",
            ).stage("slim")
        assertEquals(listOf("pip install --break-system-packages /src"), resolvingPipInstalls(stage))
    }

    private fun pipInstalls(stage: Stage): List<String> =
        stage.instructions
            .filter { it.startsWith("RUN ") }
            .flatMap { it.removePrefix("RUN ").split(Regex("""&&|;""")) }
            .map { it.trim() }
            .filter { Regex("""(^|\s)pip3? install\s""").containsMatchIn(it) }

    private fun resolvingPipInstalls(stage: Stage): List<String> =
        pipInstalls(stage).filterNot { "--no-deps" in it.split(Regex("""\s+""")) }

    @Test
    fun `the full image is the slim one plus the agent toolchain`() {
        val runner = dockerfile.stage("runner")
        assertEquals("slim", runner.base.lowercase(), "the full `runner` stage must be built FROM slim so it stays a superset.")
        val missing = toolchain - apkPackages(runner)
        assertTrue(missing.isEmpty(), "the full `runner` stage no longer installs $missing, which DOCKER.md says it adds.")
        val install = runner.instructions.firstOrNull { it.contains("npm install -g") }.orEmpty()
        assertTrue(
            install.contains("@anthropic-ai/claude-code") && install.contains("@openai/codex"),
            "the full `runner` stage no longer installs the Claude Code and Codex CLIs (found `$install`).",
        )
    }
}
