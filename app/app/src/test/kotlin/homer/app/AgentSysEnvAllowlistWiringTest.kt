package homer.app

import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.OpenTelemetryExport
import homer.app.models.Server
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/** The real server module hands `agentSysEnvAllowlist` to agent loading (ADR-0037). */
@ExtendWith(ResetDrainingExtension::class)
class AgentSysEnvAllowlistWiringTest {
    @TempDir
    lateinit var agentsDir: File

    @TempDir
    lateinit var workspaceDir: File

    private fun hostRefAgent(
        name: String,
        variable: String,
    ) {
        File(agentsDir, name).mkdirs()
        File(agentsDir, "$name/agent.yaml").writeText(
            "name: $name\nenv:\n  V: \"@SYSENV:$variable\"\ncommands:\n  - !<cmd>\n    cmd: \"echo hi\"\n",
        )
    }

    @Test
    fun `an agent naming a listed host variable loads and one naming an unlisted variable does not`() =
        testApplication {
            hostRefAgent("listed", "PATH")
            hostRefAgent("unlisted", "HOME")
            val config =
                AppConfig(
                    server = Server(apiAuth = AuthConfig.Unsecured),
                    agentsSearchPath = agentsDir.absolutePath,
                    workspaceRootPath = workspaceDir.absolutePath,
                    databasePath = File(workspaceDir, "homer.db").absolutePath,
                    openTelemetryExport = OpenTelemetryExport.LOG,
                    agentSysEnvAllowlist = listOf("PATH"),
                )
            application { configureServer(config) }

            val response = client.get("/api/v1/agents")

            assertEquals(HttpStatusCode.OK, response.status)
            val names =
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .getValue("agents")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("name")
                            .jsonPrimitive.content
                    }
            assertEquals(listOf("listed"), names)
        }
}
