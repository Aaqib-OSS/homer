package homer.app.models

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Encoding [TokenConfig] round-trips through both decoders Homer reads config with (#238). */
class TokenConfigSerializerTest {
    private val scalar = TokenConfig("tok")
    private val full = TokenConfig("ci-tok", grants = listOf(Grant(listOf("deploy-*"), listOf(Action.RUN, Action.READ))))
    private val admin = TokenConfig("admin-tok", role = "admin")

    private fun <T> assertRoundTrips(
        serializer: KSerializer<T>,
        value: T,
    ) {
        assertEquals(value, Json.decodeFromString(serializer, Json.encodeToString(serializer, value)), "JSON")
        assertEquals(value, Yaml.default.decodeFromString(serializer, Yaml.default.encodeToString(serializer, value)), "YAML")
    }

    @Test
    fun `a token with only its token set round-trips through JSON and YAML`() = assertRoundTrips(TokenConfig.serializer(), scalar)

    @Test
    fun `a token with grants round-trips through JSON and YAML`() = assertRoundTrips(TokenConfig.serializer(), full)

    @Test
    fun `an admin token round-trips through JSON and YAML`() = assertRoundTrips(TokenConfig.serializer(), admin)

    @Test
    fun `a bearer config holding tokens round-trips through JSON and YAML`() =
        assertRoundTrips(AuthConfig.serializer(), AuthConfig.Bearer(mapOf("ci" to full, "plain" to scalar, "ops" to admin)))

    @Test
    fun `an app config with bearer auth round-trips through JSON and YAML`() {
        val config =
            AppConfig(
                server = Server(apiAuth = AuthConfig.Combined(listOf(AuthConfig.Bearer(mapOf("ci" to full, "plain" to scalar))))),
                agentsSearchPath = "agents",
                workspaceRootPath = "workspace",
                databasePath = "homer.db",
            )
        assertRoundTrips(AppConfig.serializer(), config)
    }

    @Test
    fun `a token encodes as its object form`() {
        val encoded = Json.encodeToJsonElement(TokenConfig.serializer(), scalar)
        assertEquals(JsonObject(mapOf("token" to JsonPrimitive("tok"))), encoded)
    }
}
