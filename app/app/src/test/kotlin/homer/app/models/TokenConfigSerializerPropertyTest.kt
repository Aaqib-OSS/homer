package homer.app.models

import com.charleskorn.kaml.Yaml
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TokenConfigSerializerPropertyTest {
    private val text = Arb.string(1..30)
    private val grant = Arb.bind(Arb.list(text, 1..3), Arb.list(Arb.enum<Action>(), 1..5), ::Grant)
    private val tokenConfig =
        Arb.bind(
            text.filter { it.isNotBlank() },
            Arb.constant("admin").orNull(),
            Arb.list(grant, 0..3),
            Arb.list(text, 0..3).orNull(),
            ::TokenConfig,
        )

    @Test
    fun `decoding an encoded token config returns it in JSON and YAML`() =
        runTest {
            checkAll(tokenConfig) { config ->
                val json = Json.encodeToString(TokenConfig.serializer(), config)
                assertEquals(config, Json.decodeFromString(TokenConfig.serializer(), json), "JSON: $json")
                val yaml = Yaml.default.encodeToString(TokenConfig.serializer(), config)
                assertEquals(config, Yaml.default.decodeFromString(TokenConfig.serializer(), yaml), "YAML: $yaml")
            }
        }
}
