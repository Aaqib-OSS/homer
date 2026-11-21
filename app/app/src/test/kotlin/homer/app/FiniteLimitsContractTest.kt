package homer.app

import homer.app.models.Agent
import homer.app.models.AppConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Finite limits (ADR-0117, ADR-0047 rule 4): a limit-named property of the config and
 * agent models is non-nullable and has a default. Today's violations are in [EXEMPTIONS]; the map
 * only shrinks, so an entry for a property that complies or no longer exists fails as stale.
 */
@OptIn(ExperimentalSerializationApi::class)
class FiniteLimitsContractTest {
    @Test
    fun `every limit-named property is non-nullable with a default or exempted`() {
        val problems = limitProperties().filterValues { it != null }.filterKeys { it !in EXEMPTIONS }
        if (problems.isNotEmpty()) {
            fail(
                "Limit-named properties must be non-nullable with a default (ADR-0117):\n" +
                    problems.entries.joinToString("\n") { "  - ${it.key}: ${it.value}" },
            )
        }
    }

    @Test
    fun `every exemption still names a violating property`() {
        val stale = staleExemptions(EXEMPTIONS, limitProperties())
        assertTrue(stale.isEmpty(), "Exemptions for a property that complies or no longer exists, remove them: $stale")
    }

    @Test
    fun `an exemption for a compliant or missing property is reported stale`() {
        val properties = mapOf("A.compliant" to null, "A.violating" to "nullable")
        val exemptions = mapOf("A.compliant" to "r", "A.missing" to "r", "A.violating" to "r")
        assertEquals(setOf("A.compliant", "A.missing"), staleExemptions(exemptions, properties))
    }

    @Test
    fun `every exemption states a one-line reason`() {
        val bad = EXEMPTIONS.filter { it.value.isBlank() || '\n' in it.value }.keys
        assertTrue(bad.isEmpty(), "Exemption reasons must be one non-blank line: $bad")
    }

    @Test
    fun `the selector matches limit names and nothing else`() {
        val limits =
            listOf(
                "maxQueuedRuns",
                "max",
                "maxLength",
                "concurrency",
                "runnerClaimTimeoutSec",
                "idleTimeoutSeconds",
                "defaultTtlSec",
                "jobTtlSecondsAfterFinished",
                "timeoutSec",
                "costLimits",
                "retryLimit",
            )
        val others = listOf("name", "concurrent", "ttlish", "timeouts", "limited", "globalUsdPerMin", "minTurns")
        limits.forEach { assertTrue(isLimitName(it), "$it should be limit-named") }
        others.forEach { assertFalse(isLimitName(it), "$it should not be limit-named") }
    }

    @Test
    fun `the walk reaches the roots, sealed variants, command options and nested config`() {
        val seen = limitProperties().keys
        val expected =
            listOf(
                "Agent.maxQueuedRuns",
                "kubernetes.concurrency",
                "ssh.concurrency",
                "LocalRunnerConfig.concurrency",
                "external.maxInstances",
                "ClaudeCmdOptions.maxTurns",
                "DispatchConfig.maxPending",
            )
        assertTrue(seen.containsAll(expected), "missing from the walk: ${expected - seen}")
    }

    @Test
    fun `a compliant limit reports no problem and a nullable or undefaulted one does`() {
        assertEquals(null, describe(nullable = false, optional = true))
        assertTrue(describe(nullable = true, optional = true) != null)
        assertTrue(describe(nullable = false, optional = false) != null)
        assertTrue(describe(nullable = true, optional = false) != null)
    }

    private fun staleExemptions(
        exemptions: Map<String, String>,
        properties: Map<String, String?>,
    ): Set<String> = exemptions.keys - properties.filterValues { it != null }.keys

    /** `Owner.property` → null when it complies, else what is missing. */
    private fun limitProperties(): Map<String, String?> {
        val found = sortedMapOf<String, String?>()
        val visited = mutableSetOf<String>()
        listOf(AppConfig.serializer().descriptor, Agent.serializer().descriptor).forEach { collect(it, found, visited) }
        return found
    }

    private fun collect(
        descriptor: SerialDescriptor,
        found: MutableMap<String, String?>,
        visited: MutableSet<String>,
    ) {
        if (descriptor.isInline) return collect(descriptor.getElementDescriptor(0), found, visited)
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> {
                if (!visited.add(descriptor.serialName)) return
                val owner = descriptor.serialName.removeSuffix("?").substringAfterLast('.')
                for (i in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(i)
                    val child = descriptor.getElementDescriptor(i)
                    if (isLimitName(name)) {
                        found["$owner.$name"] = describe(child.isNullable, descriptor.isElementOptional(i))
                    }
                    collect(child, found, visited)
                }
            }

            PolymorphicKind.SEALED -> {
                if (descriptor.elementsCount >= 2) {
                    descriptor.getElementDescriptor(1).elementDescriptors.forEach { collect(it, found, visited) }
                }
            }

            StructureKind.LIST -> {
                collect(descriptor.getElementDescriptor(0), found, visited)
            }

            StructureKind.MAP -> {
                collect(descriptor.getElementDescriptor(1), found, visited)
            }

            else -> {
                // primitives, enums and contextual serializers have no properties
            }
        }
    }

    private fun describe(
        nullable: Boolean,
        optional: Boolean,
    ): String? =
        when {
            nullable && !optional -> "nullable and without a default"
            nullable -> "nullable"
            !optional -> "without a default"
            else -> null
        }

    private companion object {
        private val SEGMENTS = setOf("limit", "limits", "timeout", "ttl")
        private val CAMEL = Regex("(?<=[a-z0-9])(?=[A-Z])")

        fun isLimitName(name: String): Boolean {
            val parts = name.split(CAMEL).map { it.lowercase() }
            return name == "concurrency" || name.startsWith("max") || parts.any { it in SEGMENTS }
        }

        /** Nullable limit-named properties. Key `Owner.property` → why `null` is safe. */
        val EXEMPTIONS =
            mapOf(
                "Agent.runnerClaimTimeoutSec" to "null inherits the finite server.runnerClaimTimeoutSec",
                "ClaudeCmdOptions.maxBudgetUsd" to "opt-in spend cap with no bound by default; removed by #347",
                "ClaudeCmdOptions.maxMcpOutputTokens" to "unset leaves the Claude CLI's own output cap in force; removed by #347",
                "ClaudeCmdOptions.maxTurnsExitCode" to "an exit code, not a bound: the name matches the pattern by accident",
                "Param.max" to "opt-in numeric value constraint on a caller's input, not a resource bound",
                "Param.maxLength" to "opt-in length constraint on a caller's input, not a resource bound",
                "kubernetes.jobTtlSecondsAfterFinished" to "null falls back to the finite effectiveJobTtlSeconds of 3600",
                "ssh.idleTimeoutSeconds" to "null disables MINA's idle disconnect; every command's finite timeout bounds the session",
            )
    }
}
