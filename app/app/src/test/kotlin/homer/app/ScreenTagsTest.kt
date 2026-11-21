package homer.app

import homer.app.fixtures.AgentFixtures
import homer.app.models.Param
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Read-time screen for a run's subject tags: a tag carrying a value that is secret for the run under the
 * ADR-0052 union (snapshot ∪ current flags) is dropped from the response. See ADR-0060.
 */
class ScreenTagsTest {
    private fun agentWith(vararg inputs: Param) = AgentFixtures.simpleAgent(name = "a").copy(inputs = inputs.toList())

    @Test
    fun `a tag containing a value secret in the run snapshot is dropped`() {
        val agent = agentWith(Param(paramName = "token", envName = "TOKEN", secret = false))

        val screened =
            screenTags(
                tags = listOf("mr:15", "decision:pre-tok-secret-post"),
                inputParams = mapOf("TOKEN" to "tok-secret", "MR_IID" to "15"),
                agent = agent,
                storedSecretKeys = setOf("TOKEN"),
            )

        assertEquals(listOf("mr:15"), screened)
    }

    @Test
    fun `marking an input secret now also screens tags already stored`() {
        // The remediation direction of ADR-0052: the operator discovers a value was
        // sensitive, flips `secret: true`, and tags already carrying it disappear.
        val agent = agentWith(Param(paramName = "ticket", envName = "TICKET", secret = true))

        val screened =
            screenTags(
                tags = listOf("ticket:CONFIDENTIAL-1", "mr:15"),
                inputParams = mapOf("TICKET" to "CONFIDENTIAL-1"),
                agent = agent,
                storedSecretKeys = emptySet(),
            )

        assertEquals(listOf("mr:15"), screened)
    }

    @Test
    fun `a renamed secret with null storedSecretKeys is screened`() {
        // Pre-v15 rows carry no snapshot and the agent no longer declares OLD_TOKEN: fail closed,
        // like redactInputParams, which redacts keys unknown to the current definition.
        val agent = agentWith(Param(paramName = "token", envName = "NEW_TOKEN", secret = true))

        val screened =
            screenTags(
                tags = listOf("mr:15", "decision:pre-tok-secret-value-post"),
                inputParams = mapOf("OLD_TOKEN" to "tok-secret-value"),
                agent = agent,
                storedSecretKeys = null,
            )

        assertEquals(listOf("mr:15"), screened)
    }

    @Test
    fun `a renamed key absent from both snapshot and definition is screened — non-null storedSecretKeys`() {
        // Non-null (post-v15) snapshot, but the renamed key is in neither it nor the current definition:
        // the `k !in allCurrentEnvNames` branch must still drop the tag.
        val agent = agentWith(Param(paramName = "token", envName = "NEW_TOKEN", secret = true))

        val screened =
            screenTags(
                tags = listOf("mr:15", "decision:pre-tok-secret-value-post"),
                inputParams = mapOf("OLD_TOKEN" to "tok-secret-value"),
                agent = agent,
                storedSecretKeys = emptySet(),
            )

        assertEquals(listOf("mr:15"), screened)
    }

    @Test
    fun `a missing agent definition fails closed on every input value`() {
        val screened =
            screenTags(
                tags = listOf("projectId:12345678", "kind:review"),
                inputParams = mapOf("PROJECT_ID" to "12345678"),
                agent = null,
                storedSecretKeys = null,
            )

        assertEquals(listOf("kind:review"), screened)
    }

    @Test
    fun `a secret shorter than the needle floor does not screen`() {
        // Same floor as the write-time guard: a 4-character secret cannot be protected
        // by matching and would strip nearly every numeric tag.
        val agent = agentWith(Param(paramName = "pin", envName = "PIN", secret = true))

        val screened =
            screenTags(
                tags = listOf("mr:1234"),
                inputParams = mapOf("PIN" to "1234"),
                agent = agent,
                storedSecretKeys = setOf("PIN"),
            )

        assertEquals(listOf("mr:1234"), screened)
    }

    @Test
    fun `tags unrelated to any secret pass through unchanged`() {
        val agent =
            agentWith(
                Param(paramName = "token", envName = "TOKEN", secret = true),
                Param(paramName = "mr", envName = "MR_IID"),
                Param(paramName = "project", envName = "PROJECT_ID"),
            )

        val screened =
            screenTags(
                tags = listOf("mr:15", "created_mr:4211", "project:12345678"),
                // PROJECT_ID is a known non-secret input absent from the snapshot —
                // it must pass through, unlike a key unknown to the definition.
                inputParams = mapOf("TOKEN" to "tok-secret", "MR_IID" to "15", "PROJECT_ID" to "12345678"),
                agent = agent,
                storedSecretKeys = setOf("TOKEN"),
            )

        assertEquals(listOf("mr:15", "created_mr:4211", "project:12345678"), screened)
    }
}
