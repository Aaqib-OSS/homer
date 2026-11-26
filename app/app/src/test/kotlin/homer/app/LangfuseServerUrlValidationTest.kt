package homer.app

import homer.app.exceptions.ConfigurationException
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `langfuse.serverUrl` receives the Langfuse keys on every prompt fetch and OTLP export, in every export mode. */
@ExtendWith(ResetDrainingExtension::class)
class LangfuseServerUrlValidationTest {
    @TempDir
    lateinit var dir: File

    private fun config(
        url: String,
        export: OpenTelemetryExport,
    ) = AppConfig(
        server = Server(apiAuth = AuthConfig.Unsecured),
        agentsSearchPath = File(dir, "agents").absolutePath,
        workspaceRootPath = File(dir, "workspace").absolutePath,
        databasePath = File(dir, "homer.db").absolutePath,
        langfuse = LangfuseConfig(serverUrl = url, publicKey = PUBLIC_KEY, secretKey = SECRET_KEY),
        openTelemetryExport = export,
    )

    @Test
    fun `boot is refused before any wiring for plaintext http to a remote host in every export mode (CWE-319)`() {
        OpenTelemetryExport.entries.forEach { export ->
            val ex =
                assertFailsWith<ConfigurationException>("export=$export") {
                    testApplication {
                        application { configureServer(config("http://langfuse.example.com:3000", export)) }
                        startApplication()
                    }
                }
            assertTrue(ex.message!!.contains("langfuse.serverUrl"), "export=$export: ${ex.message}")
            assertTrue(ex.message!!.contains("https://"), "export=$export: ${ex.message}")
            assertFalse(File(dir, "homer.db").exists(), "export=$export: boot got past validation")
        }
    }

    @Test
    fun `the refusal names neither the keys nor the URL's userinfo or query (CWE-319)`() {
        val url = "http://user:url-password@langfuse.example.com/?token=url-token"
        val ex = assertFailsWith<ConfigurationException> { validateLangfuseServerUrl(config(url, OpenTelemetryExport.LOG)) }
        val message = ex.message!!
        listOf(PUBLIC_KEY, SECRET_KEY, "url-password", "url-token", "user:").forEach {
            assertFalse(message.contains(it), "message leaks '$it': $message")
        }
        assertTrue(message.contains("http://langfuse.example.com"), message)
    }

    @Test
    fun `https, loopback http and a blank URL are accepted in every export mode`() {
        val accepted =
            listOf(
                "",
                "https://cloud.langfuse.com",
                "https://user:pw@langfuse.example.com/base",
                "https://homer_internal:8443",
                "http://localhost:3000",
                "http://127.0.0.1:3000/",
                "http://[::1]:3000",
                "HTTP://LOCALHOST",
            )
        OpenTelemetryExport.entries.forEach { export ->
            accepted.forEach { validateLangfuseServerUrl(config(it, export)) }
        }
    }

    @Test
    fun `a URL that will not parse or names another scheme is refused (CWE-319)`() {
        listOf(
            " http://langfuse.example.com",
            "http://langfuse.example.com ",
            "langfuse.example.com",
            "ftp://langfuse.example.com",
            "https:///no-host",
            "http://localhost@langfuse.example.com",
            "http://localhost.langfuse.example.com",
        ).forEach { url ->
            assertFailsWith<ConfigurationException>(url) { validateLangfuseServerUrl(config(url, OpenTelemetryExport.LOG)) }
        }
    }

    @Test
    fun `plaintext http is refused for any non-loopback host whatever the userinfo, port or path (CWE-319)`() =
        runTest {
            checkAll(hosts, Arb.element("", "user@", "localhost@", "u:p@", "127.0.0.1:80@"), Arb.int(1..65535), paths) {
                host,
                userinfo,
                port,
                path,
                ->
                val url = "http://$userinfo$host:$port$path"
                OpenTelemetryExport.entries.forEach { export ->
                    assertFailsWith<ConfigurationException>(url) { validateLangfuseServerUrl(config(url, export)) }
                }
            }
        }

    @Test
    fun `https to any host and http to a loopback host are accepted whatever the port or path`() =
        runTest {
            checkAll(hosts, Arb.element("localhost", "127.0.0.1", "[::1]"), Arb.int(1..65535), paths) { host, loopback, port, path ->
                validateLangfuseServerUrl(config("https://$host:$port$path", OpenTelemetryExport.LOG))
                validateLangfuseServerUrl(config("http://$loopback:$port$path", OpenTelemetryExport.LANGFUSE))
            }
        }

    private companion object {
        const val PUBLIC_KEY = "pk-lf-public-value"
        const val SECRET_KEY = "sk-lf-secret-value"
        val hosts = Arb.pattern("[a-z][a-z0-9-]{0,8}[a-z0-9](\\.[a-z]{2,5}){1,2}")
        val paths = Arb.pattern("(/[a-z0-9]{1,6}){0,3}/?")
    }
}
