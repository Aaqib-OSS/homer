package homer.app

import homer.app.infra.SysEnvResolver
import homer.app.models.AppConfig
import homer.app.models.RunnerConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The per-alias secret-name sets startup derives from each runner's `env`, read
 * through the base member rather than a per-variant `when`. Same contract as
 * `globalEnv`: a value pulled from the host env is a credential. See ADR-0047 §7.
 */
class ApplicationRunnerEnvSecretNamesTest {
    @TempDir
    lateinit var dir: File

    private val hostEnv =
        SysEnvResolver {
            mapOf(
                "SSH_TOKEN" to "ssh-secret",
                "K8S_TOKEN" to "k8s-secret",
                "EXT_TOKEN" to "ext-secret",
            )[it]
        }

    /** One runner of each variant, so the sets are asserted for every type. */
    private fun config(): AppConfig {
        val file = File(dir, "config.json")
        file.writeText(
            """
            {
              "agentsSearchPath": "agents",
              "workspaceRootPath": "ws",
              "databasePath": "homer.db",
              "runners": {
                "mac": {"type": "ssh", "host": "mac.example.com", "user": "runner",
                        "auth": {"type": "agent"}, "remoteWorkspaceRoot": "/tmp/homer",
                        "env": {"SSH_HEADER": "Bearer @SYSENV:SSH_TOKEN", "PLAIN": "literal"}},
                "k8s": {"type": "kubernetes", "image": "ubuntu:24.04",
                        "env": {"K8S_TOKEN": "@SYSENV:K8S_TOKEN"}},
                "ext": {"type": "external", "token": "external-domain-token-0123456789",
                        "env": {"EXT_HEADER": "Bearer @SYSENV:EXT_TOKEN"}}
              }
            }
            """.trimIndent(),
        )
        return assertNotNull(loadStartupConfig(arrayOf(file.absolutePath), hostEnv, hardenJvm = {}))
    }

    private fun envOf(
        config: AppConfig,
        alias: String,
    ): Map<String, String> = assertNotNull(config.runners[alias]).env

    @Test
    fun `startup records the referencing key and the host variable it names, per alias and variant`() {
        val resolved = config()

        // The value is a composite, so the key alone is not enough: output holding the
        // bare token would match no needle. Both names travel.
        assertEquals(setOf("SSH_HEADER", "SSH_TOKEN"), resolved.sysEnvBackedRunnerEnvNames.getValue("mac"))
        assertEquals(setOf("EXT_HEADER", "EXT_TOKEN"), resolved.sysEnvBackedRunnerEnvNames.getValue("ext"))
        // The value is the placeholder itself, so the key carries the credential.
        assertEquals(setOf("K8S_TOKEN"), resolved.sysEnvBackedRunnerEnvNames.getValue("k8s"))

        // A literal value is deliberately not masked (SERVER.md, content-egress note).
        assertFalse("PLAIN" in resolved.sysEnvBackedRunnerEnvNames.getValue("mac"))
        assertEquals(setOf("mac", "k8s", "ext"), resolved.sysEnvBackedRunnerEnvNames.keys)
    }

    @Test
    fun `startup resolves every variant's env through the base member and leaves no placeholder`() {
        val resolved = config()

        assertEquals("Bearer ssh-secret", envOf(resolved, "mac")["SSH_HEADER"])
        assertEquals("literal", envOf(resolved, "mac")["PLAIN"])
        assertEquals("k8s-secret", envOf(resolved, "k8s")["K8S_TOKEN"])
        assertEquals("Bearer ext-secret", envOf(resolved, "ext")["EXT_HEADER"])
        listOf("mac", "k8s", "ext").forEach { alias ->
            assertTrue(
                envOf(resolved, alias).values.none { it.contains("@SYSENV:") },
                "no @SYSENV: literal may remain for '$alias'",
            )
        }
        assertTrue(resolved.runners.getValue("ext") is RunnerConfig.External)
    }
}
