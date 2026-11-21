package homer.app

import homer.app.fixtures.DockerfileStages
import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the console image's package-manager split: the release `runner` stage ships no npm, npx,
 * corepack or yarn, every release and scan build targets it, and only the dev overlay builds `dev`, which keeps npm.
 */
class ConsoleImageContractTest {
    private val dockerfile = DockerfileStages(File(repoRoot, "console/Dockerfile").readText())

    /** What the release stage removes: the node image's package managers and the trees they run from. */
    private val packageManagers =
        listOf(
            "/usr/local/lib/node_modules",
            "/usr/local/bin/npm",
            "/usr/local/bin/npx",
            "/usr/local/bin/corepack",
            "/usr/local/bin/yarn",
            "/usr/local/bin/yarnpkg",
            "/opt/yarn-v*",
        )

    private fun removed(stage: DockerfileStages.Stage): Set<String> =
        stage.instructions
            .filter { it.startsWith("RUN ") }
            .flatMap { it.removePrefix("RUN ").split(Regex("""&&|;""")) }
            .map { it.trim().split(Regex("""\s+""")) }
            .filter { it.size > 2 && it[0] == "rm" }
            .flatMap { tokens -> tokens.drop(1).filterNot { it.startsWith("-") } }
            .toSet()

    @Test
    fun `the release stage is the last one, built from dev, and removes every package manager`() {
        val runner = dockerfile.stages.last()
        assertEquals("runner", runner.name, "console/Dockerfile's last stage must be `runner`, the npm-free release image.")
        assertEquals("dev", runner.base.lowercase(), "the console `runner` stage must be built FROM `dev`.")
        val missing = packageManagers - removed(runner)
        assertTrue(missing.isEmpty(), "the console `runner` stage no longer removes $missing, so the release image ships them.")
        val lastUser = runner.instructions.lastOrNull { it.startsWith("USER ") }
        assertEquals("USER nextjs", lastUser, "the console `runner` stage must end as nextjs, not root.")
    }

    @Test
    fun `a release stage that leaves a package manager in place is caught`() {
        val stage =
            DockerfileStages(
                "FROM node AS dev\nFROM dev AS runner\nRUN rm -rf /usr/local/bin/npm /usr/local/bin/npx",
            ).stage("runner")
        assertEquals(packageManagers.toSet() - setOf("/usr/local/bin/npm", "/usr/local/bin/npx"), packageManagers.toSet() - removed(stage))
    }

    @Test
    fun `the dev stage keeps npm for the dev overlay, which targets it`() {
        val dev = dockerfile.stage("dev")
        assertTrue(!dockerfile.isStage(dev.base), "the console `dev` stage must start from the node image, which ships npm.")
        assertTrue(
            removed(dev).intersect(packageManagers.toSet()).isEmpty(),
            "the console `dev` stage removes npm, which the dev overlay runs.",
        )
        val overlay = File(repoRoot, "docker-compose.dev.yml").readText()
        assertTrue(
            Regex("""(?m)^  frontend:\n(?:    .*\n|\n)*?    build:\n      target: dev\n""").containsMatchIn(overlay),
            "docker-compose.dev.yml's frontend no longer builds `target: dev`, so `npm install && npm run dev` runs in the npm-free image.",
        )
    }

    @Test
    fun `the release and the nightly scan build the console runner target`() {
        val release = File(repoRoot, ".github/workflows/release.yml").readText()
        assertTrue(
            Regex("""dockerfile: console/Dockerfile\s+target: runner\b""").containsMatchIn(release),
            "release.yml no longer builds console/Dockerfile with `target: runner`.",
        )
        val nightly = File(repoRoot, ".github/workflows/nightly.yml").readText()
        assertTrue(
            "-f console/Dockerfile --target runner" in nightly,
            "nightly.yml's image scan no longer builds the console `runner` target, the image a release ships.",
        )
    }
}
