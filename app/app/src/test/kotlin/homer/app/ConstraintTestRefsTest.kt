package homer.app

import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConstraintTestRefsTest {
    private fun item(vararg lines: String) = ConstraintTestRefs.items("f.md", lines.toList())

    @Test
    fun `a bullet runs over its wrapped lines until a blank line`() {
        val items =
            item(
                "intro",
                "- **Constraint:** first line",
                "  second line. Enforced by `FooTest`.",
                "",
                "prose after",
            )

        assertEquals(1, items.size)
        assertEquals(2, items.single().line)
        assertEquals("- **Constraint:** first line second line. Enforced by `FooTest`.", items.single().text)
    }

    @Test
    fun `a bullet ends at the next bullet and a nested one is found too`() {
        val items =
            item(
                "- **`A`** — owns a thing.",
                "  - **Constraint:** one. Enforced by `ATest`.",
                "  - **Constraint:** two. Enforced by `ATest#x`.",
                "- **`B`** — owns another.",
            )

        assertEquals(listOf(2, 3), items.map { it.line })
        assertEquals("- **Constraint:** one. Enforced by `ATest`.", items[0].text)
    }

    @Test
    fun `prose that mentions the marker without opening a bullet is not an item`() {
        assertEquals(emptyList(), item("each marked `Constraint:` in the text", "a **Constraint:** mid-line"))
    }

    @Test
    fun `a class ref, a method ref and a list of refs parse`() {
        assertEquals(listOf(TestRef("FooTest", null)), ConstraintTestRefs.refs("x. Enforced by `FooTest`."))
        assertEquals(
            listOf(TestRef("FooTest", "does the thing (really)")),
            ConstraintTestRefs.refs("x. Enforced by `FooTest#does the thing (really)`."),
        )
        assertEquals(
            listOf(TestRef("A", "m"), TestRef("B", null), TestRef("C", "n")),
            ConstraintTestRefs.refs("x. Enforced by `A#m`, `B` and `C#n`."),
        )
    }

    @Test
    fun `a line naming no test, or naming it malformed, yields no refs`() {
        for (
        text in
        listOf(
            "x. ADR-0075.",
            "x. Enforced by FooTest.",
            "x. Enforced by `FooTest` in prose, then more.",
            "x. Enforced by `Foo Test`.",
            "x. Enforced by `FooTest#`.",
            "x. Enforced by `#method`.",
            "x. Enforced by `A#m`, `bad name`.",
            "x. Enforced by `FooTest`",
        )
        ) {
            assertEquals(emptyList(), ConstraintTestRefs.refs(text), text)
        }
    }

    @Test
    fun `a method name may hold hash signs after the first`() {
        assertEquals(TestRef("A", "m # n"), ConstraintTestRefs.parseRef("A#m # n"))
        assertNull(ConstraintTestRefs.parseRef("A B#m"))
    }

    private val sources =
        mapOf(
            "FooTest.kt" to
                """
                class FooTest {
                    @Test
                    fun `does the thing`() {}

                    @ParameterizedTest(
                        name = "x",
                    )
                    fun `runs for each`() {}

                    fun `helper that is no test`() {}
                }
                """.trimIndent(),
            "Other.kt" to "object Bar { fun `looks like a test`() {} }",
        )

    @Test
    fun `a ref resolves to a declared class and an annotated function`() {
        assertNull(ConstraintTestRefs.unresolved(TestRef("FooTest", null), sources))
        assertNull(ConstraintTestRefs.unresolved(TestRef("FooTest", "does the thing"), sources))
        assertNull(ConstraintTestRefs.unresolved(TestRef("FooTest", "runs for each"), sources))
    }

    @Test
    fun `a ref to a missing class, a missing function or an unannotated one is unresolved`() {
        assertEquals("no test class FooTes", ConstraintTestRefs.unresolved(TestRef("FooTes", null), sources))
        assertEquals(
            "FooTest has no @Test function `nope`",
            ConstraintTestRefs.unresolved(TestRef("FooTest", "nope"), sources),
        )
        assertEquals(
            "FooTest has no @Test function `helper that is no test`",
            ConstraintTestRefs.unresolved(TestRef("FooTest", "helper that is no test"), sources),
        )
        assertEquals(
            "Bar has no @Test function `looks like a test`",
            ConstraintTestRefs.unresolved(TestRef("Bar", "looks like a test"), sources),
        )
    }

    @Test
    fun `whatever the names, a line built in the documented format parses back to the same refs`() =
        runTest {
            val identifier = Arb.string(1..12, Codepoint.alphanumeric())
            val methodName = Arb.string(1..30, Codepoint.alphanumeric())
            checkAll(Arb.list(identifier, 1..4), Arb.list(methodName, 1..4)) { classes, methods ->
                val expected = classes.zip(methods) { c, m -> TestRef("C$c", m) }
                val tail = expected.joinToString(", ") { "`${it.className}#${it.method}`" }

                assertEquals(expected, ConstraintTestRefs.refs("- **Constraint:** anything. Enforced by $tail."))
            }
        }
}
