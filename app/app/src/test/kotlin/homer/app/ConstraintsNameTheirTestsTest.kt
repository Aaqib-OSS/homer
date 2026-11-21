package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Doc-drift backstop for the `Constraint:` bullets of the backend CLAUDE.md guides under `app/`: each ends with
 * `Enforced by `Class#test name`.` naming a test of `:app`, `:architecture`, `:homer-runner` or `agentdebug`. A bullet
 * naming none, an undeclared class or a non-`@Test` function fails; that the test fails on a break is review's check.
 */
class ConstraintsNameTheirTestsTest {
    private val guides: List<File> =
        File(repoRoot, "app")
            .walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", "node_modules") }
            .filter { it.isFile && it.name == "CLAUDE.md" }
            .toList()

    private val testSources: Map<String, String> =
        // Exactly the trees guardedRepoFiles declares (app/app is a test input already).
        listOf("app", "architecture", "homer-runner", "agentdebug")
            .map { File(repoRoot, "app/$it/src/test/kotlin") }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
            .associate { it.path to it.readText() }

    private fun relative(file: File) = file.relativeTo(repoRoot).path

    @Test
    fun `the backend guides and the test sources were found`() {
        assertTrue(guides.any { relative(it) == "app/CLAUDE.md" }, "app/CLAUDE.md not found under ${repoRoot.path}")
        assertTrue(testSources.size > 100, "read only ${testSources.size} test sources")
    }

    @Test
    fun `every constraint names the test that enforces it`() {
        val items = guides.flatMap { ConstraintTestRefs.items(relative(it), it.readLines()) }
        assertTrue(items.isNotEmpty(), "found no `Constraint:` bullet in $guides")
        val unnamed = items.filter { ConstraintTestRefs.refs(it.text).isEmpty() }.map { "${it.file}:${it.line}" }
        assertTrue(
            unnamed.isEmpty(),
            "Constraint bullets that do not end with Enforced by `Class#test name`.: $unnamed. " +
                "Name the test that fails when the constraint is broken, writing it first if none does.",
        )
    }

    @Test
    fun `every named test exists`() {
        val missing =
            guides
                .flatMap { ConstraintTestRefs.items(relative(it), it.readLines()) }
                .flatMap { item ->
                    ConstraintTestRefs.refs(item.text).mapNotNull { ref ->
                        ConstraintTestRefs.unresolved(ref, testSources)?.let { "${item.file}:${item.line}: $it" }
                    }
                }
        assertTrue(missing.isEmpty(), "Constraint bullets naming a test that does not exist:\n${missing.joinToString("\n")}")
    }

    @Test
    fun `every constraint marker opens a bullet`() {
        val stray =
            guides.flatMap { guide ->
                val opened = ConstraintTestRefs.items(relative(guide), guide.readLines()).size
                val marked = guide.readText().split(ConstraintTestRefs.MARKER).size - 1
                if (opened == marked) emptyList() else listOf("${relative(guide)}: $marked markers, $opened bullets")
            }
        assertTrue(stray.isEmpty(), "A ${ConstraintTestRefs.MARKER} not at the start of a `- ` bullet is not checked: $stray")
    }
}
