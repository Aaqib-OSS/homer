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
 *
 * The scan reads source, not types. A `respond` argument is judged by the constructor it builds, by the
 * declared return types of the function it calls, or by the declarations binding the name it passes —
 * a lambda parameter included, through the call chain the lambda is an argument of. It never guesses:
 * an argument it cannot read is [unreadRouteBodies], a finding of its own, so no body passes unjudged.
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
                functionReturns(),
            ),
        )
    }

    @Test
    fun `every body a route responds with is read, whatever form the argument takes`() {
        assertEquals(
            emptyList(),
            unreadRouteBodies(routeSources(), functionReturns()),
            "a respond argument the scan cannot read is a body the contract does not cover",
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
            unrootedRouteBodies(sources, setOf("Foo", "ErrorResponse"), setOf("ErrorResponse"), emptyMap(), emptyMap()),
        )
    }

    @Test
    fun `a called function's declared return type is judged like a constructor`() {
        val sources = mapOf("FixtureRoutes.kt" to "fun Route.fixture() = call.respond(maintenance.snapshot())")
        assertEquals(
            listOf("FixtureRoutes.kt: MaintenanceResponse is responded with but is not a DtoContract root"),
            unrootedRouteBodies(
                sources,
                setOf("MaintenanceResponse"),
                emptySet(),
                emptyMap(),
                mapOf("snapshot" to setOf("MaintenanceResponse")),
            ),
        )
    }

    @Test
    fun `a name a val binds is judged by what the val holds`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        val status = langGraphRunStatusService.status(processInfo, agent)
                        call.respond(status)
                    }
                    """.trimIndent(),
            )
        val returns = mapOf("status" to setOf("LangGraphRunStatus"))
        assertEquals(
            emptyList(),
            unrootedRouteBodies(sources, setOf("LangGraphRunStatus"), setOf("LangGraphRunStatus"), emptyMap(), returns),
        )
        assertEquals(
            listOf("FixtureRoutes.kt: LangGraphRunStatus is responded with but is not a DtoContract root"),
            unrootedRouteBodies(sources, setOf("LangGraphRunStatus"), emptySet(), emptyMap(), returns),
        )
    }

    @Test
    fun `a lambda parameter is judged by the call chain it is an argument of`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        artifactService.listArtifacts(processId).fold(
                            onFailure = { e -> call.respond(HttpStatusCode.NotFound, ErrorResponse(msg = "${'$'}{e.message}")) },
                            onSuccess = { listing -> call.respond(listing) },
                        )
                    }
                    """.trimIndent(),
            )
        assertEquals(
            listOf("FixtureRoutes.kt: ArtifactListingResponse is responded with but is not a DtoContract root"),
            unrootedRouteBodies(
                sources,
                setOf("ArtifactListingResponse"),
                emptySet(),
                emptyMap(),
                mapOf("listArtifacts" to setOf("ArtifactListingResponse")),
            ),
        )
    }

    @Test
    fun `an argument the scan cannot read is a finding, not a silent pass`() {
        val sources = mapOf("FixtureRoutes.kt" to "fun Route.fixture() = call.respond(whatever())")
        assertEquals(emptyList(), unrootedRouteBodies(sources, emptySet(), emptySet(), emptyMap(), emptyMap()))
        assertEquals(
            listOf("FixtureRoutes.kt: cannot read the type of `whatever()`"),
            unreadRouteBodies(sources, emptyMap()),
        )
    }

    @Test
    fun `the helper that wraps respond is a call site, not a declaration`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to "internal suspend fun ApplicationCall.respondNoStore(body: Any) { respond(body) }",
            )
        assertEquals(emptyList(), unreadRouteBodies(sources, emptyMap()))
    }

    @Test
    fun `a listed body is no finding, whatever it is`() {
        val sources = mapOf("FixtureRoutes.kt" to "call.respond(Foo(id = 1))")
        assertEquals(
            emptyList(),
            unrootedRouteBodies(sources, setOf("Foo"), emptySet(), mapOf("Foo" to "a helper reads it"), emptyMap()),
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
    returns: Map<String, Set<String>>,
): List<String> =
    sources
        .flatMap { (file, source) -> respondedTypeNames(source, returns).map { file to it } }
        .filter { (_, name) -> name in serializableModels && name !in roots && name !in exempt }
        .map { (file, name) -> "$file: $name is responded with but is not a DtoContract root" }
        .sorted()

/** The respond arguments whose type the scan cannot read: each is a body the contract does not cover. */
private fun unreadRouteBodies(
    sources: Map<String, String>,
    returns: Map<String, Set<String>>,
): List<String> =
    sources
        .flatMap { (file, source) -> unreadArguments(source, returns).map { file to it } }
        .map { (file, argument) -> "$file: cannot read the type of `$argument`" }
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

/** The declared return types of every `fun` in the main sources — see [functionReturns]. */
private fun functionReturns(): Map<String, Set<String>> =
    functionReturns(
        repoRoot
            .resolve("app/app/src/main/kotlin")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .map { it.readText() }
            .toList(),
    )

/**
 * Every `fun` in [sources], by name, with the capitalized names in its declared return type: a
 * `fun inspect(agent: Agent): List<LangGraphGraphInfo>` gives `inspect` → `LangGraphGraphInfo`. A route
 * that responds with a call's result (`respond(service.inspect(agent))`) is judged against these.
 * The lookup is by name, so every function of that name contributes and no caller is missed; a function
 * whose return type is inferred or a bare type parameter contributes no name of its own.
 */
private fun functionReturns(sources: List<String>): Map<String, Set<String>> {
    val returns = mutableMapOf<String, MutableSet<String>>()
    for (source in sources) {
        for (declaration in FUNCTION.findAll(source)) {
            val open = source.indexOf('(', declaration.range.last + 1)
            if (open < 0) continue
            val name = NAME_BEFORE.find(source.substring(declaration.range.last + 1, open))?.groupValues?.get(1) ?: continue
            val close = endOfCall(source, open) ?: continue
            val declared = declaredReturnType(source, close) ?: continue
            returns.getOrPut(name) { mutableSetOf() } += typeNames(declared)
        }
    }
    return returns
}

/** The types constructed in the arguments of every `respond`/`respondNoStore` call in [source]. */
private fun respondedTypeNames(
    source: String,
    returns: Map<String, Set<String>>,
): List<String> =
    respondCalls(source)
        .flatMap { from -> callArguments(source, from) }
        .mapNotNull { argument -> argumentTypes(source, argument, returns) }
        .flatten()
        .distinct()
        .sorted()

/** The arguments of every `respond`/`respondNoStore` call in [source] whose type the scan cannot read. */
private fun unreadArguments(
    source: String,
    returns: Map<String, Set<String>>,
): List<String> =
    respondCalls(source)
        .flatMap { from -> callArguments(source, from) }
        .filter { argument -> argument.isNotBlank() && argumentTypes(source, argument, returns) == null }
        .map { it.trim() }
        .distinct()
        .sorted()

/** The index just past the `(` of every `respond`/`respondNoStore` call in [source]. */
private fun respondCalls(source: String): List<Int> =
    RESPOND_CALL
        .findAll(source)
        .filterNot { call ->
            // The declaration of a helper that wraps `respond` is not a call site.
            val line = source.substring(0, call.range.first).substringAfterLast('\n')
            RESPOND_DECLARATION.containsMatchIn(line)
        }.map { it.range.last + 1 }
        .toList()

/**
 * The type names the respond argument [argument] can hand back, or null when the scan cannot read it:
 * empty for an argument that carries no body (the status of the two-argument form).
 */
private fun argumentTypes(
    source: String,
    argument: String,
    returns: Map<String, Set<String>>,
): Set<String>? {
    val expression = argument.trim()
    return when {
        expression.isEmpty() || expression.startsWith("HttpStatusCode") -> emptySet()
        CONSTRUCTOR.containsMatchIn(expression) -> typeNames(CONSTRUCTOR.find(expression)!!.groupValues[1])
        else -> expressionTypes(source, expression, returns, 0)
    }
}

/**
 * The type names the expression [expression] can hand back: the call it makes, or the declarations
 * binding the bare name it is. Null when it is neither — a call the main sources do not declare, say.
 */
private fun expressionTypes(
    source: String,
    expression: String,
    returns: Map<String, Set<String>>,
    depth: Int,
): Set<String>? {
    val call = callee(expression)
    if (call != null) {
        if (call.first().isUpperCase()) return setOf(call)
        return returns[call] ?: return null
    }
    return boundTypes(source, expression, returns, depth)
}

/**
 * The type names the bare name [name] is bound to anywhere in [source]: a `val`/`var` (its declared type,
 * or its initializer read in turn), a parameter, or a lambda parameter — whose type comes from the call
 * chain the lambda is an argument of. Null when nothing in [source] binds it.
 */
private fun boundTypes(
    source: String,
    name: String,
    returns: Map<String, Set<String>>,
    depth: Int,
): Set<String>? {
    if (depth > MAX_DEPTH || !IDENTIFIER.matches(name)) return null
    val types = mutableSetOf<String>()
    var read = false
    typedBinding(name).findAll(source).forEach { binding ->
        types += typeNames(binding.groupValues[1])
        read = true
    }
    inferredBinding(name).findAll(source).forEach { binding ->
        val value = expressionAt(source, binding.range.last + 1)
        expressionTypes(source, value, returns, depth + 1)?.let {
            types += it
            read = true
        }
    }
    parameter(name).findAll(source).forEach { declaration ->
        types += declaration.groupValues[1]
        read = true
    }
    lambdaParameter(name).findAll(source).forEach { lambda ->
        lambdaCallers(source, lambda.range.first).forEach { called ->
            returns[called]?.let {
                types += it
                read = true
            }
        }
    }
    return if (read) types else null
}

/**
 * The names of the calls the lambda opening at [brace] is an argument of: the chain ending in the `(`
 * whose argument list holds it, `artifactService.listArtifacts(processId).fold` included.
 */
private fun lambdaCallers(
    source: String,
    brace: Int,
): List<String> {
    val open = enclosingCall(source, brace) ?: return emptyList()
    val lineStart = source.lastIndexOf('\n', open).let { if (it < 0) 0 else it + 1 }
    return callees(source.substring(lineStart, open))
}

/** The index of the `(` whose argument list holds the `{` at [brace], or null when it opens a block. */
private fun enclosingCall(
    source: String,
    brace: Int,
): Int? {
    var depth = 0
    var i = brace - 1
    while (i >= 0) {
        when (source[i]) {
            ')', ']', '}' -> {
                depth++
            }

            '(', '[' -> {
                if (depth == 0) return if (source[i] == '(') i else null
                depth--
            }

            '{' -> {
                if (depth == 0) return null
                depth--
            }

            else -> {}
        }
        i--
    }
    return null
}

/** The name of the outermost call in [expression] (`a.b(x).c(y)` is `c`), or null when it makes none. */
private fun callee(expression: String): String? {
    var depth = 0
    var found: String? = null
    var i = 0
    while (i < expression.length) {
        when (expression[i]) {
            '"', '\'' -> {
                i = endOfLiteral(expression, i)
            }

            '(', '[', '{' -> {
                if (depth == 0) found = nameBefore(expression, i) ?: found
                depth++
                i++
            }

            ')', ']', '}' -> {
                depth--
                i++
            }

            else -> {
                i++
            }
        }
    }
    return found
}

/** The identifier just before the `(` at [open], or null when the call is not made through a plain name. */
private fun nameBefore(
    expression: String,
    open: Int,
): String? = NAME_BEFORE.find(expression.substring(0, open))?.groupValues?.get(1)

/** The names of every call made in [text]. */
private fun callees(text: String): List<String> = CALL.findAll(text).map { it.groupValues[1] }.toList()

/** The expression that starts at [from], up to the end of its statement. */
private fun expressionAt(
    source: String,
    from: Int,
): String {
    var i = from
    while (i < source.length && source[i].isWhitespace()) i++
    val start = i
    var depth = 0
    while (i < source.length) {
        when (source[i]) {
            '"', '\'' -> {
                i = endOfLiteral(source, i)
            }

            '(', '[', '{' -> {
                depth++
                i++
            }

            ')', ']', '}' -> {
                if (depth == 0) return source.substring(start, i)
                depth--
                i++
            }

            '\n' -> {
                if (depth == 0 && !continues(source, i)) return source.substring(start, i)
                i++
            }

            else -> {
                i++
            }
        }
    }
    return source.substring(start)
}

/** Whether the line ending at [newline] carries on: it ends on a token the next line completes. */
private fun continues(
    source: String,
    newline: Int,
): Boolean = source.take(newline).lastOrNull { !it.isWhitespace() } in CONTINUED_BY

/** The declared return type after the parameter list that closes at [close], or null when there is none. */
private fun declaredReturnType(
    source: String,
    close: Int,
): String? {
    var i = close + 1
    while (i < source.length && source[i].isWhitespace()) i++
    if (source.getOrNull(i) != ':') return null
    val start = ++i
    while (i < source.length && source[i] !in "={(\n") i++
    return source.substring(start, i).trim()
}

/** The index of the `)` closing the `(` at [open], or null when the file ends first. */
private fun endOfCall(
    source: String,
    open: Int,
): Int? {
    var depth = 0
    var i = open
    while (i < source.length) {
        when (source[i]) {
            '"', '\'' -> {
                i = endOfLiteral(source, i)
            }

            '(' -> {
                depth++
                i++
            }

            ')' -> {
                depth--
                if (depth == 0) return i
                i++
            }

            else -> {
                i++
            }
        }
    }
    return null
}

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

            '(', '[', '{' -> {
                depth++
                current.append(source[i])
                i++
            }

            ')', ']', '}' -> {
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

/** Every capitalized word in [text]: `Result<ArtifactListingResponse>` names both. */
private fun typeNames(text: String): Set<String> = CAPITALIZED.findAll(text).map { it.value }.toSet()

private fun typedBinding(name: String) = Regex("""\b(?:val|var)\s+$name\s*:\s*([^=\n]+?)\s*=""")

private fun inferredBinding(name: String) = Regex("""\b(?:val|var)\s+$name\s*=\s*""")

private fun parameter(name: String) = Regex("""(?<![\w.])$name\s*:\s*([A-Z]\w*)""")

private fun lambdaParameter(name: String) = Regex("""[{(,]\s*(?:\w+\s*,\s*)*$name\s*(?:,\s*\w+\s*)*->""")

private val RESPOND_CALL = Regex("""\brespond(?:NoStore)?\s*\(""")

/** A `respond` declaration — `fun ApplicationCall.respondNoStore(…)` — rather than a call to one. */
private val RESPOND_DECLARATION = Regex("""\bfun\s+[\w.<>?, ]*$""")

private val FUNCTION = Regex("""\bfun\s""")

private val NAME_BEFORE = Regex("""(\w+)\s*$""")

private val CALL = Regex("""(\w+)\s*\(""")

private val CAPITALIZED = Regex("""\b[A-Z]\w*""")

private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

private val CONSTRUCTOR = Regex("""^\s*([A-Z]\w*)\s*\(""")

/** What a line can end on and still need the next one: `val x =` then the expression. */
private val CONTINUED_BY = setOf('=', '.', ',', '(', '[', '{', '+', '-', '*', '/', '%', '&', '|', '?', ':', '<', '>')

/** How far an initializer is followed into the values it names: `val a = b` and back stops at three. */
private const val MAX_DEPTH = 3
