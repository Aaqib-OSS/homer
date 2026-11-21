package homer.app.models

import kotlinx.serialization.descriptors.elementDescriptors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The members the sealed base declares for every variant: `env`, `extraUploads` and the alias-aware `validate`
 * (ADR-0042). Both variant maps are checked against the sealed descriptor, so a new variant fails until listed.
 */
class RunnerConfigBaseMembersTest {
    private val token = "t".repeat(32)

    /** Every variant, built with neither `env` nor `extraUploads` passed, so the constructor defaults apply. */
    private val defaults: Map<String, RunnerConfig> =
        mapOf(
            "ssh" to RunnerConfig.Ssh(host = "h", user = "u", auth = SshAuth.Agent, remoteWorkspaceRoot = "/ws"),
            "kubernetes" to RunnerConfig.Kubernetes(image = "img"),
            "external" to RunnerConfig.External(token = token),
        )

    /** Every variant, built with the given [env] and [extraUploads]. */
    private val builders: Map<String, (Map<String, String>, List<ExtraUploadSpec>) -> RunnerConfig> =
        mapOf(
            "ssh" to { env, extras ->
                RunnerConfig.Ssh(
                    host = "h",
                    user = "u",
                    auth = SshAuth.Agent,
                    remoteWorkspaceRoot = "/ws",
                    env = env,
                    extraUploads = extras,
                )
            },
            "kubernetes" to { env, extras -> RunnerConfig.Kubernetes(image = "img", env = env, extraUploads = extras) },
            "external" to { env, extras -> RunnerConfig.External(token = token, env = env, extraUploads = extras) },
        )

    /** One runner of each variant, typed as the base, all carrying [env]. */
    private fun withEnv(env: Map<String, String>): List<RunnerConfig> = builders.values.map { it(env, emptyList()) }

    /** One runner of each variant, typed as the base, all carrying [extraUploads]. */
    private fun withExtraUploads(extraUploads: List<ExtraUploadSpec>): List<RunnerConfig> =
        builders.values.map { it(emptyMap(), extraUploads) }

    @Test
    fun `every variant of the sealed base is listed here`() {
        val declared =
            RunnerConfig
                .serializer()
                .descriptor
                .getElementDescriptor(1)
                .elementDescriptors
                .map { it.serialName }
                .toSet()
        assertEquals(declared, defaults.keys, "a new RunnerConfig variant must be added to `defaults` and `builders`")
        assertEquals(declared, builders.keys, "a new RunnerConfig variant must be added to `defaults` and `builders`")
    }

    @Test
    fun `every variant exposes env and extraUploads through the base`() {
        val env = mapOf("BOX" to "1")
        val extras = listOf(ExtraUploadSpec("/app/scripts", ".homer/scripts", "SCRIPTS_DIR"))
        withEnv(env).forEach { assertEquals(env, it.env) }
        withExtraUploads(extras).forEach { assertEquals(extras, it.extraUploads) }
    }

    @Test
    fun `the base defaults both members to empty for every variant`() {
        defaults.forEach { (alias, runner) ->
            assertEquals(emptyMap(), runner.env, alias)
            assertEquals(emptyList(), runner.extraUploads, alias)
        }
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
