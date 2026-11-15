package homer.app.models

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The members the sealed base declares for every variant: `env`, `extraUploads`
 * and the alias-aware `validate`. Dispatch sites read them through
 * [RunnerConfig] instead of repeating a `when` arm per variant. See ADR-0042.
 */
class RunnerConfigBaseMembersTest {
    private val token = "t".repeat(32)

    /** One runner of each variant, typed as the base, all carrying [env]. */
    private fun withEnv(env: Map<String, String>): List<RunnerConfig> =
        listOf(
            RunnerConfig.Ssh(host = "h", user = "u", auth = SshAuth.Agent, remoteWorkspaceRoot = "/ws", env = env),
            RunnerConfig.Kubernetes(image = "img", env = env),
            RunnerConfig.External(token = token, env = env),
        )

    /** One runner of each variant, typed as the base, all carrying [extraUploads]. */
    private fun withExtraUploads(extraUploads: List<ExtraUploadSpec>): List<RunnerConfig> =
        listOf(
            RunnerConfig.Ssh(host = "h", user = "u", auth = SshAuth.Agent, remoteWorkspaceRoot = "/ws", extraUploads = extraUploads),
            RunnerConfig.Kubernetes(image = "img", extraUploads = extraUploads),
            RunnerConfig.External(token = token, extraUploads = extraUploads),
        )

    @Test
    fun `every variant exposes env and extraUploads through the base`() {
        val env = mapOf("BOX" to "1")
        val extras = listOf(ExtraUploadSpec("/app/scripts", ".homer/scripts", "SCRIPTS_DIR"))
        withEnv(env).forEach { assertEquals(env, it.env) }
        withExtraUploads(extras).forEach { assertEquals(extras, it.extraUploads) }
    }

    @Test
    fun `the base defaults both members to empty for every variant`() {
        assertEquals(emptyMap(), RunnerConfig.Kubernetes(image = "img").env)
        assertEquals(emptyList(), RunnerConfig.Kubernetes(image = "img").extraUploads)
        assertEquals(emptyMap(), RunnerConfig.External(token = token).env)
        assertEquals(emptyList(), RunnerConfig.External(token = token).extraUploads)
    }

    @Test
    fun `validate refuses a reserved env key on every variant, naming the alias`() {
        withEnv(mapOf("HMR_WORK_DIR" to "/tmp/evil")).forEach { runner ->
            val ex = assertFailsWith<IllegalArgumentException> { runner.validate("box") }
            assertTrue(ex.message!!.contains("runner 'box'"), ex.message)
            assertTrue(ex.message!!.contains("HMR_"), ex.message)
        }
    }

    @Test
    fun `validate refuses a reserved extraUploads remote on every variant, naming the alias`() {
        withExtraUploads(listOf(ExtraUploadSpec("/a", "workdir"))).forEach { runner ->
            val ex = assertFailsWith<IllegalArgumentException> { runner.validate("box") }
            assertTrue(ex.message!!.contains("runner 'box'"), ex.message)
            assertTrue(ex.message!!.contains("workdir"), ex.message)
        }
    }

    @Test
    fun `validate accepts a well-formed runner of every variant through the base`() {
        withEnv(mapOf("BOX" to "1")).forEach { it.validate("box") }
        withExtraUploads(listOf(ExtraUploadSpec("/a", ".homer/scripts"))).forEach { it.validate("box") }
    }

    @Test
    fun `AppConfig init reaches the shared rules through the base for every variant`() {
        withEnv(mapOf("HMR_PROCESS_ID" to "7")).forEach { runner ->
            val ex =
                assertFailsWith<IllegalArgumentException> {
                    AppConfig(
                        agentsSearchPath = "a",
                        workspaceRootPath = "w",
                        databasePath = "d",
                        runners = mapOf("box" to runner),
                    )
                }
            assertTrue(ex.message!!.contains("runner 'box'"), ex.message)
        }
    }
}
