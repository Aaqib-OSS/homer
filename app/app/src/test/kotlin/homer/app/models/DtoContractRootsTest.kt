package homer.app.models

import homer.app.fixtures.repoRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Root discovery for the console DTO contract (ADR-0082): every `@Serializable` body a route in `routes/`
 * responds with must be a root in [DtoContract] — or reachable from one — or listed in
 * [DtoContract.routeBodiesNotMirrored] with a reason. Without this a new response body stays outside the
 * `dto-contract` check and can drift from its console mirror unnoticed.
 *
 * The scan reads source, not types. A `respond` argument is judged by the constructor it builds, by the
 * declared return types of the function it calls, or by the declarations binding the name it passes —
 * a lambda parameter included, through the call chain the lambda is an argument of. An SSE body is
 * judged the same way: the `data:` of every `sendEvent` call, and the frame constructors inside every
 * function that builds an [SseFrame] for `sendFrame`. It never guesses: an argument or payload it
 * cannot read is a finding of its own ([unreadRouteBodies], [unreadSsePayloads]), so no body passes
 * unjudged.
 *
 * Comments are not code: every source is read through [withoutComments], so a commented-out call is
 * no call site and a bracket inside a comment does not split an argument.
 */
class DtoContractRootsTest {
    @Test
    fun `every serializable body a route responds with is a root or listed as not mirrored`() {
        assertEquals(
            emptyList(),
            unrootedRouteBodies(
                routeSources(),
                serializableTypes(),
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
    fun `every serializable body a route streams as an sse payload is a root or listed as not mirrored`() {
        assertEquals(
            emptyList(),
            unrootedSsePayloads(
                routeSources(),
                serializableTypes(),
                exportedNames(),
                DtoContract.routeBodiesNotMirrored,
            ),
        )
    }

    @Test
    fun `an sse payload the scan cannot read is a finding, not a silent pass`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        sendEvent(this, "agent.reload", buildJsonObject { put("added", "x") }.toString())
                    }
                    """.trimIndent(),
            )
        // A hand-built JSON payload names no @Serializable type: it is unread, a finding of its own.
        assertEquals(
            listOf("FixtureRoutes.kt: cannot read the type of the `agent.reload` SSE payload"),
            unreadSsePayloads(sources, setOf("AgentReloadFrame")),
        )
        assertEquals(emptyList(), unrootedSsePayloads(sources, setOf("AgentReloadFrame"), setOf("AgentReloadFrame"), emptyMap()))
    }

    @Test
    fun `a respond call in a comment is not a call site`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        // call.respond(CommentedOut())
                        /* call.respond(AlsoCommented(msg = "no (such) run")) */
                        /* /* nested */ call.respond(NestedCommented()) */
                        call.respond(LiveResponse("http://example.com/(live)"))
                    }
                    """.trimIndent(),
            )
        assertEquals(emptyList(), unreadRouteBodies(sources, emptyMap()))
        assertEquals(
            listOf("FixtureRoutes.kt: LiveResponse is responded with but is not a DtoContract root"),
            unrootedRouteBodies(
                sources,
                setOf("CommentedOut", "AlsoCommented", "NestedCommented", "LiveResponse"),
                emptySet(),
                emptyMap(),
                emptyMap(),
            ),
        )
    }

    @Test
    fun `a sendEvent call in a comment is not a payload`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        // sendEvent(this, "agent.reload", buildJsonObject { put("added", "x") }.toString())
                    }
                    """.trimIndent(),
            )
        assertEquals(emptyList(), unreadSsePayloads(sources, setOf("AgentReloadFrame")))
    }

    @Test
    fun `a streamed frame constructor is judged like a respond argument`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """sendEvent(this, "agent.reload", frameData(AgentReloadFrame.serializer(), AgentReloadFrame(added, updated, removed)))""",
            )
        assertEquals(
            emptyList(),
            unrootedSsePayloads(sources, setOf("AgentReloadFrame"), setOf("AgentReloadFrame"), emptyMap()),
        )
        assertEquals(
            listOf("FixtureRoutes.kt: AgentReloadFrame is streamed as an SSE payload but is not a DtoContract root"),
            unrootedSsePayloads(sources, setOf("AgentReloadFrame"), emptySet(), emptyMap()),
        )
    }

    @Test
    fun `a streamed payload built inside an sse frame builder is judged too`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    internal fun fixtureFrame(event: FixtureEvent): SseFrame =
                        when (event) {
                            is FixtureEvent.Changed ->
                                SseFrame("fixture", frameData(FixtureFrame.serializer(), FixtureFrame(event.id)))
                        }
                    """.trimIndent(),
            )
        assertEquals(
            listOf("FixtureRoutes.kt: FixtureFrame is streamed as an SSE payload but is not a DtoContract root"),
            unrootedSsePayloads(sources, setOf("FixtureFrame"), emptySet(), emptyMap()),
        )
        assertEquals(
            emptyList(),
            unrootedSsePayloads(sources, setOf("FixtureFrame"), setOf("FixtureFrame"), emptyMap()),
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
    fun `an elvis is judged by each operand, and a jump operand hands back no value`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        val claim = domain.deliverClaim(instance, request) ?: return@post call.respond(HttpStatusCode.NoContent)
                        call.respond(HttpStatusCode.OK, claim)
                    }
                    """.trimIndent(),
                "FallbackRoutes.kt" to
                    """
                    fun Route.fallback() {
                        val claim = domain.deliverClaim(instance, request) ?: Fallback()
                        call.respond(claim)
                    }
                    """.trimIndent(),
            )
        val returns = mapOf("deliverClaim" to setOf("ClaimResponse"))
        assertEquals(emptyList(), unreadRouteBodies(sources, returns))
        assertEquals(
            listOf(
                "FallbackRoutes.kt: ClaimResponse is responded with but is not a DtoContract root",
                "FallbackRoutes.kt: Fallback is responded with but is not a DtoContract root",
                "FixtureRoutes.kt: ClaimResponse is responded with but is not a DtoContract root",
            ),
            unrootedRouteBodies(sources, setOf("ClaimResponse", "Fallback"), emptySet(), emptyMap(), returns),
        )
    }

    @Test
    fun `a respond argument elvis is judged by each operand, a constructor one included`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        call.respond(Fallback() ?: domain.build(id))
                        call.respondNoStore(HttpStatusCode.OK, domain.find(id) ?: domain.other(id) ?: Fallback())
                        val claim = domain.deliverClaim(instance, request) ?: throw NotFoundException("no claim")
                        call.respond(claim)
                    }
                    """.trimIndent(),
            )
        val returns =
            mapOf(
                "build" to setOf("Built"),
                "deliverClaim" to setOf("ClaimResponse"),
                "find" to setOf("Found"),
                "other" to setOf("Other"),
            )
        assertEquals(emptyList(), unreadRouteBodies(sources, returns))
        assertEquals(
            listOf("Built", "ClaimResponse", "Fallback", "Found", "Other").map {
                "FixtureRoutes.kt: $it is responded with but is not a DtoContract root"
            },
            unrootedRouteBodies(sources, setOf("Built", "ClaimResponse", "Fallback", "Found", "Other"), emptySet(), emptyMap(), returns),
        )
    }

    @Test
    fun `an elvis splits only at the top level, never inside a literal or brackets`() {
        assertEquals(listOf("a()", "b()", "C()"), elvisOperands("a() ?: b() ?: C()"))
        assertEquals(listOf("a()", "\"x ?: y\""), elvisOperands("a() ?: \"x ?: y\""))
        assertEquals(listOf("a('?')", "':'"), elvisOperands("a('?') ?: ':'"))
        assertEquals(listOf("(a() ?: b())"), elvisOperands("(a() ?: b())"))
        assertEquals(listOf("f(a ?: b) { c ?: d }", "e[x ?: y]"), elvisOperands("f(a ?: b) { c ?: d } ?: e[x ?: y]"))
        assertEquals(listOf("a?.b()"), elvisOperands("a?.b()"))
    }

    @Test
    fun `an elvis operand the scan cannot read keeps the whole binding unread`() {
        val sources =
            mapOf(
                "FixtureRoutes.kt" to
                    """
                    fun Route.fixture() {
                        val claim = domain.deliverClaim(instance, request) ?: whatever()
                        call.respond(claim)
                    }
                    """.trimIndent(),
            )
        assertEquals(
            listOf("FixtureRoutes.kt: cannot read the type of `claim`"),
            unreadRouteBodies(sources, mapOf("deliverClaim" to setOf("ClaimResponse"))),
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
    fun `every listed body names a serializable class the main sources declare`() {
        val types = serializableTypes()
        assertEquals(
            emptyList(),
            DtoContract.routeBodiesNotMirrored.keys.filterNot { it in types },
            "a listed body that no main source declares as @Serializable is a stale exemption",
        )
    }

    @Test
    fun `a serializable type outside the models package is judged too`() {
        // RunnerOp is responded with by POST /sessions/{sid}/next and declared in
        // homer.app.runners.external: a scan reading models/ alone would pass it unjudged.
        assertTrue("RunnerOp" in serializableTypes())
    }

    @Test
    fun `a nested serializable type is judged too`() {
        // Agent.Regex (Agent.kt) is @Serializable and nested in its outer class: a filter on
        // `$`-carrying class files would drop every nested body from the judgement.
        assertTrue("Regex" in serializableTypes())
    }
}

/** The `@Serializable` types a route responds with that no root reaches and no exemption covers. */
private fun unrootedRouteBodies(
    sources: Map<String, String>,
    serializableTypes: Set<String>,
    roots: Set<String>,
    exempt: Map<String, String>,
    returns: Map<String, Set<String>>,
): List<String> =
    sources
        .flatMap { (file, source) -> respondedTypeNames(withoutComments(source), returns).map { file to it } }
        .filter { (_, name) -> name in serializableTypes && name !in roots && name !in exempt }
        .map { (file, name) -> "$file: $name is responded with but is not a DtoContract root" }
        .sorted()

/** The respond arguments whose type the scan cannot read: each is a body the contract does not cover. */
private fun unreadRouteBodies(
    sources: Map<String, String>,
    returns: Map<String, Set<String>>,
): List<String> =
    sources
        .flatMap { (file, source) -> unreadArguments(withoutComments(source), returns).map { file to it } }
        .map { (file, argument) -> "$file: cannot read the type of `$argument`" }
        .sorted()

/**
 * The `@Serializable` types every SSE payload in [sources] streams that no root reaches and no
 * exemption covers. The payloads are read from source like the respond arguments: the `data:` of a
 * `sendEvent` call, and the frame constructors inside every function that builds an [SseFrame] —
 * the builders `sendFrame` hands the wire to. A hand-built JSON payload names no such type; it is
 * an unread finding of its own, so an SSE body cannot pass unjudged either.
 */
private fun unrootedSsePayloads(
    sources: Map<String, String>,
    serializableTypes: Set<String>,
    roots: Set<String>,
    exempt: Map<String, String>,
): List<String> =
    sources
        .flatMap { (file, source) -> ssePayloadTypes(withoutComments(source), serializableTypes).map { file to it } }
        .filter { (_, name) -> name !in roots && name !in exempt }
        .map { (file, name) -> "$file: $name is streamed as an SSE payload but is not a DtoContract root" }
        .sorted()

/** The SSE payloads of [sources] the scan cannot read: each is a body the contract does not cover. */
private fun unreadSsePayloads(
    sources: Map<String, String>,
    serializableTypes: Set<String>,
): List<String> =
    sources
        .flatMap { (file, source) -> unreadSsePayload(withoutComments(source), serializableTypes).map { file to it } }
        .map { (file, event) -> "$file: cannot read the type of the `$event` SSE payload" }
        .sorted()

/** The `@Serializable` payload types of [source]: the `sendEvent` payloads plus every `SseFrame` builder's frames. */
private fun ssePayloadTypes(
    source: String,
    serializableTypes: Set<String>,
): List<String> =
    sendEventPayloads(source, serializableTypes)
        .flatMap { it.types }
        .plus(sseFrameBuilders(source).flatMap { body -> constructorsIn(body).filter { it in serializableTypes } })
        .distinct()
        .sorted()

/** The event names of the `sendEvent` payloads of [source] the scan cannot read. */
private fun unreadSsePayload(
    source: String,
    serializableTypes: Set<String>,
): List<String> =
    sendEventPayloads(source, serializableTypes)
        .filter { !it.readable }
        .map { it.event }
        .distinct()
        .sorted()

/** One `sendEvent` payload: the event it carries, the `@Serializable` types it constructs, and whether it is read at all. */
private class SsePayload(
    val event: String,
    val types: Set<String>,
    val readable: Boolean,
)

/** Every `sendEvent` call in [source] but its declaration, as the payload's [SsePayload]. */
private fun sendEventPayloads(
    source: String,
    serializableTypes: Set<String>,
): List<SsePayload> =
    SEND_EVENT
        .findAll(source)
        .filterNot { call ->
            // The declaration of `sendEvent` itself is not a call site.
            val line = source.substring(0, call.range.first).substringAfterLast('\n')
            RESPOND_DECLARATION.containsMatchIn(line)
        }.mapNotNull { call ->
            val arguments = callArguments(source, call.range.last + 1)
            if (arguments.size < 3) return@mapNotNull null
            val event = arguments[1].trim().removeSurrounding("\"")
            val payload = arguments[2]
            // A string-literal payload (a hand-written `{}`) carries no typed body; a member
            // passthrough (`frame.data`) names no call, so it is read, with no type to judge.
            if (payload.trim().startsWith("\"")) {
                SsePayload(event, emptySet(), readable = true)
            } else {
                val types = constructorsIn(payload).filter { it in serializableTypes }.toSet()
                val namesACall = payload.any { it == '(' || it == '{' }
                SsePayload(event, types, readable = types.isNotEmpty() || !namesACall)
            }
        }.toList()

/** The bodies of every function in [source] whose declared return type names `SseFrame`. */
private fun sseFrameBuilders(source: String): List<String> =
    FUNCTION
        .findAll(source)
        .mapNotNull { declaration ->
            val open = source.indexOf('(', declaration.range.last + 1)
            if (open < 0) return@mapNotNull null
            val close = endOfCall(source, open) ?: return@mapNotNull null
            val declared = declaredReturnType(source, close) ?: return@mapNotNull null
            if ("SseFrame" !in typeNames(declared)) return@mapNotNull null
            functionBody(source, close)
        }.toList()

/** The block or expression body a declaration opens after its parameter list closes at [close]. */
private fun functionBody(
    source: String,
    close: Int,
): String {
    var i = close + 1
    while (i < source.length && source[i].isWhitespace()) i++
    if (i >= source.length) return ""
    if (source[i] != '{') return expressionAt(source, i)
    var depth = 0
    var j = i
    while (j < source.length) {
        when (source[j]) {
            '"' -> {
                j = endOfLiteral(source, j) - 1
            }

            '{' -> {
                depth++
            }

            '}' -> {
                depth--
                if (depth == 0) return source.substring(i, j + 1)
            }
        }
        j++
    }
    return source.substring(i)
}

/** The capitalized constructors [text] builds; a template's braces and a string's text are not code. */
private fun constructorsIn(text: String): Set<String> =
    CONSTRUCTOR_CALL
        .findAll(text)
        .map { it.groupValues[1] }
        .toSet()

/** Every Kotlin source in `routes/`, by file name. */
private fun routeSources(): Map<String, String> =
    repoRoot
        .resolve("app/app/src/main/kotlin/homer/app/routes")
        .listFiles { file -> file.extension == "kt" }
        .orEmpty()
        .associate { it.name to it.readText() }

/**
 * Every `@Serializable` type the main sources declare, from their compiled classes. The whole tree,
 * not `homer.app.models` alone: a route may respond with a wire type another package declares — the
 * external runner's protocol DTOs are one — and such a body is judged like any other. Nested classes
 * included: a nested type shares its outer class's file (`Outer$Nested.class`), so a `$` filter would
 * drop every nested body from the judgement.
 */
private fun serializableTypes(): Set<String> {
    val models = File(ErrorResponse::class.java.getResource("ErrorResponse.class")!!.toURI()).parentFile
    val root = generateSequence(models) { it.parentFile }.first { it.name == "homer" }.parentFile
    return root
        .walkTopDown()
        .filter { it.extension == "class" }
        .mapNotNull { file ->
            val path = file.relativeTo(root).path
            val binaryName = path.removeSuffix(".class").replace(File.separatorChar, '.')
            // The simple name is the last `$` segment; an anonymous or compiler-generated one
            // (`Foo$1`, `Foo$$serializer`) starts with a digit or names no annotated class anyway.
            val lastSegment = binaryName.substringAfterLast('$')
            if (lastSegment.isEmpty() || lastSegment[0].isDigit()) return@mapNotNull null
            val type = runCatching { Class.forName(binaryName, false, DtoContract::class.java.classLoader) }.getOrNull()
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
    for (source in sources.map(::withoutComments)) {
        for (declaration in FUNCTION.findAll(source)) {
            val open = source.indexOf('(', declaration.range.last + 1)
            if (open < 0) continue
            val name = NAME_BEFORE.find(source.substring(declaration.range.last + 1, open))?.groupValues?.get(1) ?: continue
            val close = endOfCall(source, open) ?: continue
            val declared = declaredReturnType(source, close) ?: continue
            returns.getOrPut(name) { mutableSetOf() } += unwrappedTypeNames(declared)
        }
    }
    return returns
}

/**
 * The type names a declared return type names, with a leading `Result<` envelope dropped: the
 * caller reads the wrapped value (`fold`'s `onSuccess` lambda among them), never the envelope —
 * judging a lambda by it would report the envelope's own simple name as a route body.
 */
private fun unwrappedTypeNames(declared: String): Set<String> =
    typeNames(
        if (declared.startsWith("Result<")) {
            declared.substringAfter('<').substringBeforeLast('>')
        } else {
            declared
        },
    )

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
    val operands = elvisOperands(expression)
    if (operands.size > 1) {
        return operands.flatMap { argumentTypes(source, it, returns) ?: return null }.toSet()
    }
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
    val operands = elvisOperands(expression)
    if (operands.size > 1) {
        return operands.flatMap { expressionTypes(source, it, returns, depth) ?: return null }.toSet()
    }
    if (JUMP.containsMatchIn(expression)) return emptySet()
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

/**
 * The operands of the top-level `?:` chain in [expression], trimmed: `a() ?: return@post b()` is
 * `a()` and `return@post b()`. One operand, [expression] itself, when it has no top-level elvis.
 */
private fun elvisOperands(expression: String): List<String> {
    val operands = mutableListOf<String>()
    var depth = 0
    var start = 0
    var i = 0
    while (i < expression.length) {
        when {
            expression[i] == '"' || expression[i] == '\'' -> {
                i = endOfLiteral(expression, i)
                continue
            }

            expression[i] in "([{" -> {
                depth++
            }

            expression[i] in ")]}" -> {
                depth--
            }

            depth == 0 && expression.startsWith("?:", i) -> {
                operands += expression.substring(start, i).trim()
                start = i + 2
                i++
            }
        }
        i++
    }
    return operands + expression.substring(start).trim()
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

/**
 * [source] with every comment blanked to spaces, newlines kept: comment text is not code, so its
 * `respond` calls are no call sites and its brackets split no argument. String literals are copied
 * verbatim — a `//` inside one is text — and offsets are preserved, so an index into [source] is an
 * index into the result.
 */
private fun withoutComments(source: String): String {
    val out = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        when {
            source[i] == '"' || source[i] == '\'' -> {
                val end = endOfLiteral(source, i)
                out.append(source, i, end)
                i = end
            }

            source.startsWith("//", i) -> {
                while (i < source.length && source[i] != '\n') {
                    out.append(' ')
                    i++
                }
            }

            source.startsWith("/*", i) -> {
                val end = endOfBlockComment(source, i)
                while (i < end) {
                    out.append(if (source[i] == '\n') '\n' else ' ')
                    i++
                }
            }

            else -> {
                out.append(source[i])
                i++
            }
        }
    }
    return out.toString()
}

/** The index just past the block comment opening at [start]; Kotlin block comments nest. */
private fun endOfBlockComment(
    source: String,
    start: Int,
): Int {
    var depth = 0
    var i = start
    while (i < source.length) {
        when {
            source.startsWith("/*", i) -> {
                depth++
                i += 2
            }

            source.startsWith("*/", i) -> {
                depth--
                i += 2
                if (depth == 0) return i
            }

            else -> {
                i++
            }
        }
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

private val SEND_EVENT = Regex("""\bsendEvent\s*\(""")

private val CONSTRUCTOR_CALL = Regex("""\b([A-Z]\w*)\s*\(""")

/** A `respond` declaration — `fun ApplicationCall.respondNoStore(…)` — rather than a call to one. */
private val RESPOND_DECLARATION = Regex("""\bfun\s+[\w.<>?, ]*$""")

private val FUNCTION = Regex("""\bfun\s""")

private val NAME_BEFORE = Regex("""(\w+)\s*$""")

private val CALL = Regex("""(\w+)\s*\(""")

private val CAPITALIZED = Regex("""\b[A-Z]\w*""")

private val IDENTIFIER = Regex("""[A-Za-z_]\w*""")

/** An expression that jumps (`return`, `return@label`, `throw`) and so hands back no value. */
private val JUMP = Regex("""^(?:return\b|throw\b)""")

private val CONSTRUCTOR = Regex("""^\s*([A-Z]\w*)\s*\(""")

/** What a line can end on and still need the next one: `val x =` then the expression. */
private val CONTINUED_BY = setOf('=', '.', ',', '(', '[', '{', '+', '-', '*', '/', '%', '&', '|', '?', ':', '<', '>')

/** How far an initializer is followed into the values it names: `val a = b` and back stops at three. */
private const val MAX_DEPTH = 3
