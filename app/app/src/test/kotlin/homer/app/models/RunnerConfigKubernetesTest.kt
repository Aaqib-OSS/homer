package homer.app.models

import com.charleskorn.kaml.Yaml
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunnerConfigKubernetesTest {
    private val yaml = Yaml.default

    private fun k8s(
        extras: List<ExtraUploadSpec> = emptyList(),
        excludes: List<String> = emptyList(),
    ) = RunnerConfig.Kubernetes(
        image = "img",
        syncExcludes = excludes,
        extraUploads = extras,
    )

    @Test
    fun `kubernetes runner refuses an env key or extraUploads env that is not a shell identifier, naming runner and key`() {
        val keyError = assertFailsWith<IllegalArgumentException> { k8s().copy(env = mapOf("FOO-BAR" to "1")).validate("pods") }
        assertTrue(keyError.message!!.contains("runner 'pods'") && keyError.message!!.contains("'FOO-BAR'"))

        val withUpload = k8s(extras = listOf(ExtraUploadSpec(local = "/a", remote = ".homer/x", env = "1DIR")))
        val uploadError = assertFailsWith<IllegalArgumentException> { withUpload.validate("pods") }
        assertTrue(uploadError.message!!.contains("'1DIR'"))
    }

    @Test
    fun `syncExcludes and extraUploads default empty and yaml round-trip`() {
        val cfg =
            yaml.decodeFromString(
                RunnerConfig.serializer(),
                """!<kubernetes>
namespace: homer
image: registry/builder:1
syncExcludes: [".git", ".gradle", "build"]
extraUploads:
  - local: /app/scripts
    remote: .homer/scripts
    env: SCRIPTS_DIR""",
            )
        val k8s = assertIs<RunnerConfig.Kubernetes>(cfg)
        assertEquals(listOf(".git", ".gradle", "build"), k8s.syncExcludes)
        assertEquals(ExtraUploadSpec("/app/scripts", ".homer/scripts", "SCRIPTS_DIR"), k8s.extraUploads.single())
        val bare =
            yaml.decodeFromString(
                RunnerConfig.serializer(),
                """!<kubernetes>
image: img""",
            )
        assertEquals(emptyList(), assertIs<RunnerConfig.Kubernetes>(bare).syncExcludes)
        assertEquals(emptyList(), assertIs<RunnerConfig.Kubernetes>(bare).extraUploads)
    }

    @Test
    fun `validateExtraUploads enforces the ADR-0042 rules naming the alias`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                k8s(extras = listOf(ExtraUploadSpec("/a", "workdir"))).validateExtraUploads("build-box")
            }
        assertTrue(ex.message!!.contains("runner 'build-box'"))
        assertFailsWith<IllegalArgumentException> {
            k8s(
                extras =
                    listOf(
                        ExtraUploadSpec("/a", ".homer/x"),
                        ExtraUploadSpec("/b", ".homer/x"),
                    ),
            ).validateExtraUploads("build-box")
        }
    }

    @Test
    fun `AppConfig init validates kubernetes extraUploads naming the alias`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                AppConfig(
                    agentsSearchPath = "a",
                    workspaceRootPath = "w",
                    databasePath = "d",
                    runners = mapOf("build-box" to k8s(extras = listOf(ExtraUploadSpec("/a", "artifacts/x")))),
                )
            }
        assertTrue(ex.message!!.contains("runner 'build-box'"))
    }

    /** The soft deadline of ADR-0108: a `gracefulStopSeconds` on a `maxRunSeconds`-bounded runner. */
    @Test
    fun `gracefulStopSeconds round-trips and defaults to no soft deadline`() {
        val cfg =
            yaml.decodeFromString(
                RunnerConfig.serializer(),
                """!<kubernetes>
image: img
maxRunSeconds: 3600
gracefulStopSeconds: 300""",
            )
        val k8s = assertIs<RunnerConfig.Kubernetes>(cfg)
        assertEquals(300L, k8s.gracefulStopSeconds)
        assertEquals(3600L, k8s.maxRunSeconds)

        // Absent = no soft deadline, which is what keeps a run that never opted in unchanged.
        assertNull(k8s().gracefulStopSeconds)
        assertEquals(86_400L, k8s().maxRunSeconds)
    }

    @Test
    fun `validateWorkload refuses a gracefulStopSeconds it cannot honour`() {
        // Zero would stop every run the moment it started.
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                k8s().copy(maxRunSeconds = 600, gracefulStopSeconds = 0).validateWorkload("build-box")
            }.message!!.contains("must be >= 1"),
        )

        // A grace that swallows the whole run leaves the hard deadline nothing to bound.
        assertTrue(
            assertFailsWith<IllegalArgumentException> {
                k8s().copy(maxRunSeconds = 600, gracefulStopSeconds = 600).validateWorkload("build-box")
            }.message!!.contains("must be less than maxRunSeconds"),
        )

        // Both ends that are legal: one second before the hard deadline, and the bare minimum.
        k8s().copy(maxRunSeconds = 600, gracefulStopSeconds = 599).validateWorkload("build-box")
        k8s().copy(maxRunSeconds = 600, gracefulStopSeconds = 1).validateWorkload("build-box")
    }

    @Test
    fun `AppConfig init reports a workload violation before an extraUploads one`() {
        // The kubernetes rules fire in the order they always have — the workload
        // bounds first, then extraUploads — so a config breaking both keeps naming
        // the workload one. See ADR-0108.
        val ex =
            assertFailsWith<IllegalArgumentException> {
                AppConfig(
                    agentsSearchPath = "a",
                    workspaceRootPath = "w",
                    databasePath = "d",
                    runners =
                        mapOf(
                            "build-box" to
                                k8s(extras = listOf(ExtraUploadSpec("/a", "workdir")))
                                    .copy(gracefulStopSeconds = 0),
                        ),
                )
            }
        assertTrue(ex.message!!.contains("gracefulStopSeconds must be >= 1"), ex.message!!)
    }

    @Test
    fun `AppConfig init validates the graceful stop bound naming the alias`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                AppConfig(
                    agentsSearchPath = "a",
                    workspaceRootPath = "w",
                    databasePath = "d",
                    runners = mapOf("build-box" to k8s().copy(gracefulStopSeconds = 0)),
                )
            }
        assertTrue(ex.message!!.contains("runner 'build-box'"))
        assertTrue(ex.message!!.contains("gracefulStopSeconds must be >= 1"), ex.message!!)
    }
}
