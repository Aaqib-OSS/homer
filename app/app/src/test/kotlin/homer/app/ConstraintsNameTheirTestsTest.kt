package homer.app

import homer.app.fixtures.repoRoot
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Doc-drift backstop for the `Constraint:` bullets of the backend CLAUDE.md guides under `app/`
 * (app/CLAUDE.md § Key service responsibilities): each ends with `Enforced by `Class#test name`.`
 * naming a test in `:app` that fails when the constraint is broken. A bullet naming none, a class
 * no test source declares, or a function that is no `@Test` of that class fails here. That the
 * named test really fails on a break is the reviewer's check, made once when the line was written.
 */
class ConstraintsNameTheirTestsTest {
    private val guides: List<File> =
        File(repoRoot, "app")
            .walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", "node_modules") }
            .filter { it.isFile && it.name == "CLAUDE.md" }
            .toList()

    private val testSources: Map<String, String> =
        File(repoRoot, "app/app/src/test/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
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
