package homer.app.infra

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [AgentDefinitionValidator.checkAgentName]'s gate over generated names (ADR-0047 rule 3): a name is
 * accepted exactly when it matches [AgentDirs.AGENT_NAME_PATTERN], and a refused one is reported —
 * the name reaches the filesystem, the API path and the run's identity, so it may not carry a
 * separator or anything else the pattern excludes. The example cases live in
 * `AgentDefinitionValidatorTest`; these drive the shapes its author did not list (root CLAUDE.md §6).
 */
class AgentDefinitionValidatorPropertyTest {
    private val logger = TestFixtures.mockLogger()
    private val validator =
        AgentDefinitionValidator(
            logger,
            runnerAliases = setOf("local"),
            runnerTagSets = { error("registry unavailable") },
            inputResolver = AgentInputResolver(),
            inputSchemaValidator = InputSchemaValidator(),
            inputChecks = AgentInputChecks(logger, ParamValidator()),
        )

    /** The allowed characters plus the ones a name must not carry: separators, blanks, quoting. */
    private val names: Arb<String> =
        Arb
            .list(Arb.element('a', 'Z', '0', '_', '-', ' ', '.', '/', '\\', '$', '\'', '\n', 'é'), 0..8)
            .map { it.joinToString("") }

    @Test
    fun `a name is accepted exactly when the agent-name pattern matches it`() {
        runTest {
            checkAll(300, names) { name ->
                val errors = mutableListOf<String>()
                val accepted = validator.checkAgentName(AgentFixtures.simpleAgent(name = name), "dir", errors)

                if (AgentDirs.AGENT_NAME_PATTERN.matches(name)) {
                    assertEquals(name, accepted, "'$name' matches the pattern and must be accepted")
                    assertEquals(emptyList(), errors, "an accepted name must report no error")
                } else {
                    assertNull(accepted, "'$name' does not match the pattern and must be refused")
                    assertEquals(listOf("Invalid agent name '$name' in dir"), errors)
                }
            }
        }
    }

    /** The spellings a traversal, a path or a blank line would take: refused, one error each. */
    @Test
    fun `a name that is a path or is blank is refused`() {
        for (name in listOf("..", ".", "../escape", "a/b", "a\\b", "a b", "", " ", "a\nb", "é")) {
            val errors = mutableListOf<String>()
            assertNull(
                validator.checkAgentName(AgentFixtures.simpleAgent(name = name), "dir", errors),
                "'$name' must be refused",
            )
            assertEquals(1, errors.size, "'$name' must be reported once")
        }
    }
}
