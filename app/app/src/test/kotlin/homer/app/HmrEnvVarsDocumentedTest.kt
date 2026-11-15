package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertTrue

/**
 * Doc-drift backstop for app/CLAUDE.md § System environment variables: every `HMR_*` name in
 * [EnvConstants] is either named in `app/AGENT.md` or listed in [internalOnly] with the reason an
 * agent author does not need it. An internal entry that names no constant any more, or a name
 * AGENT.md now documents, is stale and fails too.
 */
class HmrEnvVarsDocumentedTest {
    /** Names Homer sets for its own consumers only, each with the one-line reason. */
    private val internalOnly =
        mapOf(
            "HMR_LG_RECOVER" to
                "Recover marker set only by the resume route for the Python LangGraph runtime, which documents it (ADR-0044).",
        )

    private val hmrConstants: Set<String> =
        EnvConstants::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .filter { Regex("HMR_[A-Z0-9_]+").matches(it) }
            .toSet()

    private val agentMd = File(repoRoot, "app/AGENT.md").readText()

    private fun documented(name: String) = Regex("""(?<![A-Za-z0-9_])$name(?![A-Za-z0-9_])""").containsMatchIn(agentMd)

    @Test
    fun `every HMR constant is documented in AGENT md or listed as internal`() {
        assertTrue(hmrConstants.isNotEmpty(), "Read no HMR_* constant from EnvConstants.")
        val undocumented = hmrConstants.filterNot { it in internalOnly || documented(it) }.sorted()
        assertTrue(
            undocumented.isEmpty(),
            "HMR_* constants neither named in app/AGENT.md nor listed as internal-only: $undocumented. " +
                "Document each in AGENT.md § Workspace Environment Variables, or add it to internalOnly with a reason.",
        )
    }

    @Test
    fun `every internal-only entry is a current undocumented HMR constant`() {
        val gone = internalOnly.keys.filterNot { it in hmrConstants }.sorted()
        assertTrue(gone.isEmpty(), "internalOnly names no current EnvConstants value: $gone. Remove the stale entries.")
        val nowDocumented = internalOnly.keys.filter { documented(it) }.sorted()
        assertTrue(nowDocumented.isEmpty(), "internalOnly entries now named in app/AGENT.md: $nowDocumented. Remove them.")
    }
}
