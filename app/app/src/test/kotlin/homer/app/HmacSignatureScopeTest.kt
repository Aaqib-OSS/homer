package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.models.Action
import homer.app.models.Agent
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Command
import homer.app.models.Grant
import homer.app.models.HmacKeyConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Param
import homer.app.models.ParamSource
import homer.app.models.Server
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An HMAC key whose signature does not bind the request path (ADR-0010) while its grants reach more than one agent
 * (`role: admin`, a `*` agent pattern, or two or more exact agents, per ADR-0037) is replayable across them, so it
 * must fail startup rather than run.
 */
class HmacSignatureScopeTest {
    private fun configWith(apiAuth: AuthConfig) =
        AppConfig(
            server = Server(apiAuth = apiAuth),
            agentsSearchPath = "/tmp/agents",
            workspaceRootPath = "/tmp/workspace",
            databasePath = "/tmp/homer-test.db",
            langfuse = LangfuseConfig(serverUrl = "http://localhost", publicKey = "pk", secretKey = "sk"),
            openTelemetryExport = OpenTelemetryExport.LOG,
        )

    private fun hmac(cfg: HmacKeyConfig) = AuthConfig.Hmac(keys = mapOf("k" to cfg))

    @Test
    fun `body-only template with role admin throws`() {
        val ex =
            assertFailsWith<ConfigurationException> {
                validateHmacSignatureScope(configWith(hmac(HmacKeyConfig(secret = "s", role = "admin"))))
            }
        assertTrue(ex.message?.contains("{{path}}") == true, "message must name the fix")
    }

    @Test
    fun `body-only template with a wildcard-agents grant throws`() {
        val ex =
            assertFailsWith<ConfigurationException> {
                validateHmacSignatureScope(
                    configWith(
                        hmac(
                            HmacKeyConfig(
                                secret = "s",
                                grants = listOf(Grant(agents = listOf("*"), actions = listOf(Action.RUN))),
                            ),
                        ),
                    ),
                )
            }
        assertTrue(ex.message?.contains("{{path}}") == true, "message must name the fix")
    }

    @Test
    fun `body-only template inside Combined with role admin throws`() {
        val combined = AuthConfig.Combined(schemes = listOf(hmac(HmacKeyConfig(secret = "s", role = "admin"))))
        assertFailsWith<ConfigurationException> {
            validateHmacSignatureScope(configWith(combined))
        }
    }

    @Test
    fun `body-only template with a narrow (non-wildcard) grant does not throw`() {
        validateHmacSignatureScope(
            configWith(
                hmac(
                    HmacKeyConfig(
                        secret = "s",
                        grants = listOf(Grant(agents = listOf("handle-webhook"), actions = listOf(Action.RUN))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `body-only template reaching two distinct exact agents throws`() {
        // No wildcard and not admin, but the key can RUN two exact agents — a body-only
        // signature captured for one replays against the other.
        val ex =
            assertFailsWith<ConfigurationException> {
                validateHmacSignatureScope(
                    configWith(
                        hmac(
                            HmacKeyConfig(
                                secret = "s",
                                grants = listOf(Grant(agents = listOf("agent-a", "agent-b"), actions = listOf(Action.RUN))),
                            ),
                        ),
                    ),
                )
            }
        assertTrue(ex.message?.contains("{{path}}") == true, "message must name the fix")
    }

    @Test
    fun `path-bound template reaching two distinct exact agents does not throw`() {
        validateHmacSignatureScope(
            configWith(
                hmac(
                    HmacKeyConfig(
                        secret = "s",
                        payloadTemplate = "{{method}}.{{path}}.{{body}}",
                        grants = listOf(Grant(agents = listOf("agent-a", "agent-b"), actions = listOf(Action.RUN))),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `path-bound template with role admin does not throw`() {
        validateHmacSignatureScope(
            configWith(
                hmac(HmacKeyConfig(secret = "s", role = "admin", payloadTemplate = "{{method}}.{{path}}.{{body}}")),
            ),
        )
    }

    @Test
    fun `non-hmac auth does not throw`() {
        validateHmacSignatureScope(configWith(AuthConfig.Unsecured))
    }

    @Test
    fun `warns for an HMAC key without a timestampHeader`() {
        val log = mockk<Logger>(relaxed = true)
        warnOnHmacReplayWindow(configWith(hmac(HmacKeyConfig(secret = "s"))), log)
        verify { log.warn(match<String> { it.contains("timestampHeader") }, "k") }
    }

    @Test
    fun `does not warn when timestampHeader is set`() {
        val log = mockk<Logger>(relaxed = true)
        warnOnHmacReplayWindow(
            configWith(
                hmac(
                    HmacKeyConfig(
                        secret = "s",
                        timestampHeader = "X-Ts",
                        payloadTemplate = "{{timestamp}}.{{body}}",
                    ),
                ),
            ),
            log,
        )
        verify(exactly = 0) { log.warn(any<String>(), any<String>()) }
    }

    // ---- warnOnUnsignedQueryInputs ----

    private fun agentWithInput(
        name: String,
        src: ParamSource,
    ) = Agent(
        name = name,
        commands = listOf(Command.Cmd(cmd = listOf("echo", "ok"), timeout = 60)),
        inputs = listOf(Param(paramName = "p", envName = "P", src = src)),
    )

    @Test
    fun `warns when a reachable agent reads query inputs and the template omits query`() {
        val log = mockk<Logger>(relaxed = true)
        warnOnUnsignedQueryInputs(
            configWith(
                hmac(
                    HmacKeyConfig(
                        secret = "s",
                        grants = listOf(Grant(agents = listOf("q-agent"), actions = listOf(Action.RUN))),
                    ),
                ),
            ),
            agents = listOf(agentWithInput("q-agent", ParamSource.QUERY)),
            log = log,
        )
        verify { log.warn(match<String> { it.contains("{{query}}") }, "k", match<String> { it.contains("q-agent") }) }
    }

    @Test
    fun `does not warn when the template already binds query`() {
        val log = mockk<Logger>(relaxed = true)
        warnOnUnsignedQueryInputs(
            configWith(
                hmac(
                    HmacKeyConfig(
                        secret = "s",
                        payloadTemplate = "{{path}}.{{query}}.{{body}}",
                        grants = listOf(Grant(agents = listOf("q-agent"), actions = listOf(Action.RUN))),
                    ),
                ),
            ),
            agents = listOf(agentWithInput("q-agent", ParamSource.QUERY)),
            log = log,
        )
        verify(exactly = 0) { log.warn(any<String>(), any<String>(), any<String>()) }
    }

    @Test
    fun `does not warn when the reachable agent has no query-src input`() {
        val log = mockk<Logger>(relaxed = true)
        warnOnUnsignedQueryInputs(
            configWith(
                hmac(
                    HmacKeyConfig(
                        secret = "s",
                        grants = listOf(Grant(agents = listOf("body-agent"), actions = listOf(Action.RUN))),
                    ),
                ),
            ),
            agents = listOf(agentWithInput("body-agent", ParamSource.BODY)),
            log = log,
        )
        verify(exactly = 0) { log.warn(any<String>(), any<String>(), any<String>()) }
    }

    @Test
    fun `does not warn when the key cannot run the query-reading agent`() {
        val log = mockk<Logger>(relaxed = true)
        warnOnUnsignedQueryInputs(
            configWith(
                hmac(
                    HmacKeyConfig(
                        secret = "s",
                        grants = listOf(Grant(agents = listOf("other"), actions = listOf(Action.RUN))),
                    ),
                ),
            ),
            agents = listOf(agentWithInput("q-agent", ParamSource.QUERY)),
            log = log,
        )
        verify(exactly = 0) { log.warn(any<String>(), any<String>(), any<String>()) }
    }

    // ---- validateSessionSecretStrength ----

    private fun password(
        sessionSecret: String,
        sessionSecretPrevious: String? = null,
    ) = AuthConfig.Password(
        users = mapOf("admin" to homer.app.models.PasswordUserConfig(passwordHash = "hash")),
        sessionSecret = sessionSecret,
        sessionSecretPrevious = sessionSecretPrevious,
        cookieSecure = false,
    )

    @Test
    fun `short sessionSecret throws`() {
        val ex =
            assertFailsWith<ConfigurationException> {
                validateSessionSecretStrength(configWith(password("too-short")))
            }
        assertTrue(ex.message?.contains("32") == true, "message must state the minimum length")
    }

    @Test
    fun `short sessionSecretPrevious throws`() {
        assertFailsWith<ConfigurationException> {
            validateSessionSecretStrength(
                configWith(password("a".repeat(32), sessionSecretPrevious = "short-rotation-key")),
            )
        }
    }

    @Test
    fun `a 32-char sessionSecret passes`() {
        validateSessionSecretStrength(configWith(password("a".repeat(32), sessionSecretPrevious = "b".repeat(40))))
    }

    @Test
    fun `non-password auth does not throw for session secret strength`() {
        validateSessionSecretStrength(configWith(AuthConfig.Unsecured))
    }
}
