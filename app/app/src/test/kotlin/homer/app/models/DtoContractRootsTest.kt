package homer.app.models

import homer.app.fixtures.repoRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Root discovery for the console DTO contract (ADR-0082): every `@Serializable` body a route in `routes/`
 * responds with must be a root in [DtoContract] — or reachable from one — or listed in
 * [DtoContract.routeBodiesNotMirrored] with a reason. Without this a new response body stays outside the
 * `dto-contract` check and can drift from its console mirror unnoticed.
 */
class DtoContractRootsTest {
    @Test
    fun `every serializable body a route responds with is a root or listed as not mirrored`() {
        assertEquals(
            emptyList(),
            unrootedRouteBodies(
                routeSources(),
                serializableModels(),
                exportedNames(),
                DtoContract.routeBodiesNotMirrored,
            ),
        )
    }

    @Test
    fun `a route body that is not a root fails, naming the type`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        call.respond(Foo(id = 1))
                        call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "no (such) run"))
                    }
                    """.trimIndent(),
            )
        // ErrorResponse is a root, Foo is not: the status argument of the two-argument form is read
        // through, and a parenthesis inside a string does not end the call early.
        assertEquals(
            listOf("FixtureRoutes.kt: Foo is responded with but is not a DtoContract root"),
            unrootedRouteBodies(sources, setOf("Foo", "ErrorResponse"), setOf("ErrorResponse"), emptyMap()),
        )
    }

    @Test
    fun `a listed body is no finding, whatever it is`() {
        val sources = mapOf("FixtureRoutes.kt" to "call.respond(Foo(id = 1))")
        assertEquals(
            emptyList(),
            unrootedRouteBodies(sources, setOf("Foo"), emptySet(), mapOf("Foo" to "a helper reads it")),
        )
    }

    @Test
    fun `every listed body names a serializable model class`() {
        val models = serializableModels()
        assertEquals(
            emptyList(),
            DtoContract.routeBodiesNotMirrored.keys.filterNot { it in models },
            "a listed body that homer.app.models does not declare is a stale exemption",
        )
    }
}

/** The `@Serializable` model types a route responds with that no root reaches and no exemption covers. */
private fun unrootedRouteBodies(
    sources: Map<String, String>,
    serializableModels: Set<String>,
    roots: Set<String>,
    exempt: Map<String, String>,
): List<String> =
    sources
        .flatMap { (file, source) -> respondedTypeNames(source).map { file to it } }
        .filter { (_, name) -> name in serializableModels && name !in roots && name !in exempt }
        .map { (file, name) -> "$file: $name is responded with but is not a DtoContract root" }
        .sorted()

/** Every Kotlin source in `routes/`, by file name. */
private fun routeSources(): Map<String, String> =
    repoRoot
        .resolve("app/app/src/main/kotlin/homer/app/routes")
        .listFiles { file -> file.extension == "kt" }
        .orEmpty()
        .associate { it.name to it.readText() }

/** Every `@Serializable` class the `homer.app.models` package declares, from its compiled classes. */
private fun serializableModels(): Set<String> {
    val classes = File(ErrorResponse::class.java.getResource("ErrorResponse.class")!!.toURI()).parentFile
    return classes
        .listFiles { file -> file.extension == "class" && '$' !in file.name }
        .orEmpty()
        .mapNotNull { file ->
            val type =
                runCatching {
                    Class.forName("homer.app.models.${file.nameWithoutExtension}", false, DtoContract::class.java.classLoader)
                }.getOrNull()
            type?.takeIf { it.isAnnotationPresent(Serializable::class.java) }?.simpleName
        }.toSet()
}

/** The classes [DtoContract] exports: the roots and everything reachable from them. */
private fun exportedNames(): Set<String> =
    DtoContract
        .export()
        .getValue("classes")
        .jsonObject.keys

private val RESPOND_CALL = Regex("""\brespond(?:NoStore)?\s*\(""")

private val CONSTRUCTOR = Regex("""^\s*([A-Z]\w*)\s*\(""")

/** The types constructed in the arguments of every `respond`/`respondNoStore` call in [source]. */
private fun respondedTypeNames(source: String): List<String> =
    RESPOND_CALL
        .findAll(source)
        .flatMap { call ->
            callArguments(source, call.range.last + 1).mapNotNull { CONSTRUCTOR.find(it)?.groupValues?.get(1) }
        }.toList()

/** The top-level arguments of the call whose `(` is at [from]; a parenthesis inside a literal is text. */
private fun callArguments(
    source: String,
    from: Int,
): List<String> {
    val arguments = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var i = from
    while (i < source.length) {
        when (source[i]) {
            '"', '\'' -> {
                val end = endOfLiteral(source, i)
                current.append(source, i, end)
                i = end
            }

            '(' -> {
                depth++
                current.append(source[i])
                i++
            }

            ')' -> {
                if (depth == 0) {
                    arguments += current.toString()
                    return arguments
                }
                depth--
                current.append(source[i])
                i++
            }

            ',' -> {
                if (depth == 0) {
                    arguments += current.toString()
                    current.clear()
                } else {
                    current.append(source[i])
                }
                i++
            }

            else -> {
                current.append(source[i])
                i++
            }
        }
    }
    return arguments
}

/** The index just past the string or char literal starting at [start]; an unterminated one ends its line. */
private fun endOfLiteral(
    source: String,
    start: Int,
): Int {
    if (source.startsWith("\"\"\"", start)) {
        val end = source.indexOf("\"\"\"", start + 3)
        return if (end < 0) source.length else end + 3
    }
    val quote = source[start]
    var i = start + 1
    while (i < source.length) {
        when {
            source[i] == '\\' -> i++
            source[i] == quote -> return i + 1
            source[i] == '\n' -> return i
        }
        i++
    }
    return source.length
}
