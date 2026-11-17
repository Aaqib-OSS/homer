package homer.architecture

import com.lemonappdev.konsist.api.Konsist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The pass-through matcher on the fixture functions in `src/test/resources/passthrough/Fixtures.kt` (each named for
 * its case), so its limits hold without the real sources.
 */
class PassThroughMatcherTest {
    @Test
    fun `forwarding every parameter in order is a finding, in each body form`() {
        assertEquals("target", target("inOrder"))
        assertEquals("target", target("blockReturn"))
        assertEquals("target", target("blockStatement"))
        assertEquals("repo.save", target("receiverChain"))
        assertEquals("target", target("namedInOrder"))
    }

    @Test
    fun `reordered, transformed, extra, missing or named out of order arguments are no finding`() {
        listOf("reordered", "namedOutOfOrder", "transformed", "extra", "missing", "chained", "plus", "twoStatements").forEach {
            assertNull(target(it), it)
        }
    }

    @Test
    fun `an override, an operator, a parameterless function and a port method are no finding`() {
        listOf("overridden", "plus2", "parameterless", "abstractFn").forEach { assertNull(target(it), it) }
        val ports = mapOf("Port" to setOf("portMember"))
        assertNull(target("portMember", ports))
        assertEquals("target", target("portNonMember", ports))
    }

    @Test
    fun `a forward through a private property is encapsulation, through anything else it is a finding`() {
        listOf("privateProperty", "privateConstructorProperty", "privateObjectProperty").forEach { assertNull(target(it), it) }
        assertEquals("m.get", target("publicConstructorProperty"))
        assertEquals("m.get", target("publicProperty"))
        assertEquals("a.get", target("parameterReceiver"))
        assertEquals("target", target("topLevelCall"))
    }

    @Test
    fun `a forward to a non-public member, an internal property or an interface member is no finding`() {
        listOf("internalProperty", "privateFunctionTarget", "internalFunctionTarget", "interfaceDefault").forEach {
            assertNull(target(it), it)
        }
        assertEquals("g", target("publicFunctionTarget"))
    }

    @Test
    fun `return needs a word boundary, a labelled this is read, a vararg is spread, a default value exempts`() {
        assertEquals("returnX", target("wordBoundary"))
        assertEquals("this@Outer.g", target("labelledThis"))
        assertEquals("target", target("varargSpread"))
        assertNull(target("varargNotSpread"))
        assertNull(target("withDefault"))
    }

    @Test
    fun `an extension receiver and a type argument are findings, a private file member target is not`() {
        assertEquals("target", target("extensionReceiver"))
        assertEquals("target<Int>", target("genericCallee"))
        assertNull(target("viaFileHelper"))
        assertNull(target("viaFileState"))
    }

    private fun target(
        name: String,
        ports: Map<String, Set<String>> = emptyMap(),
    ): String? {
        val fn = fixtures.functions(includeNested = true).single { it.name == name }
        return passThroughTarget(fn, ports)
    }

    private companion object {
        val fixtures = Konsist.scopeFromFile("architecture/src/test/resources/passthrough/Fixtures.kt")
    }
}
