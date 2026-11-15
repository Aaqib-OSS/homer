package homer.app.models

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewRunRecordTest {
    @Test
    fun `toString names the input keys but never their values`() {
        val text = NewRunRecord(agentName = "deploy", inputParams = mapOf("API_TOKEN" to "tok-secret-value")).toString()

        assertFalse("tok-secret-value" in text)
        assertTrue("API_TOKEN" in text)
    }
}
