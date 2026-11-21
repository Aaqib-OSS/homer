package homer.app.infra

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.pattern
import io.kotest.property.arbitrary.printableAscii
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `@SYSENV:` gate an agent definition passes before its `env` is resolved (ADR-0037). */
class AgentSysEnvAllowlistPropertyTest {
    private val names = Arb.choice(Arb.element("REPO_TOKEN", "HOME", "_x1", "A"), Arb.pattern("[A-Za-z_][A-Za-z0-9_]{0,5}"))
    private val piece =
        Arb.choice(
            Arb.string(0..6, Codepoint.printableAscii()),
            names.map { "@SYSENV:$it" },
            Arb.constant("@SYSENV:"),
        )
    private val values = Arb.list(piece, 0..4).map { it.joinToString("") }
    private val envs = Arb.map(Arb.pattern("[A-Z]{1,4}"), values, 0, 4)

    @Test
    fun `the gate refuses exactly when resolving would read an unlisted variable, and names only those`() =
        runTest {
            checkAll(envs, Arb.set(names, 0..4)) { env, allowed ->
                val reads = mutableSetOf<String>()
                SysEnvResolver { name -> reads.add(name).let { HOST_VALUE } }.resolveMapValues(env)
                val unlisted = reads - allowed

                val refusal = AgentSysEnvAllowlist(allowed).refusal(env)

                assertEquals(unlisted.isEmpty(), refusal == null, "env $env, allowed $allowed: $refusal")
                unlisted.forEach { assertTrue("'$it'" in refusal.orEmpty(), "refusal must name '$it': $refusal") }
                allowed.forEach { assertFalse("'$it'" in refusal.orEmpty(), "refusal names allowed '$it': $refusal") }
                assertFalse(HOST_VALUE in refusal.orEmpty(), "a refusal never carries a value")
            }
        }

    private companion object {
        const val HOST_VALUE = "hv\u0000secret"
    }
}
