package homer.architecture

import com.lemonappdev.konsist.api.KoModifier
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoFunctionDeclaration
import com.lemonappdev.konsist.api.declaration.KoInterfaceDeclaration
import com.lemonappdev.konsist.api.provider.KoBaseProvider
import com.lemonappdev.konsist.api.provider.KoFunctionProvider
import com.lemonappdev.konsist.api.provider.KoParametersProvider
import com.lemonappdev.konsist.api.provider.KoPropertyProvider
import com.lemonappdev.konsist.api.provider.modifier.KoModifierProvider
import kotlin.test.Test

/**
 * No pass-through function in the main code of :app, agentdebug and homer-runner: a function whose whole
 * body is one call forwarding its own parameters, unchanged and in order, is a layer that adds nothing.
 * A forward to a non-public member of the same class or
 * object, or through a non-public property of it, is encapsulation, not a finding. It restates root CLAUDE.md §2 ("No abstractions for
 * single-use code"), which app/CLAUDE.md "Architecture tests" records.
 */
class PassThroughTest {
    @Test
    fun `main code has no pass-through function`() {
        val violations =
            Sources.modules.flatMap { module ->
                val scope = Sources.main(module)
                val portMethods = portMethods(scope)
                checkedFunctions(scope).mapNotNull { fn ->
                    val target = passThroughTarget(fn, portMethods)
                    target?.let { violation(fn, "${fn.name} only forwards its parameters to $it; call it directly") }
                }
            }
        assertNoViolations("No pass-through function (root CLAUDE.md §2)", violations)
    }
}

/** The functions the rule checks: every named function, nested ones included, local ones (inside a body) not. */
internal fun checkedFunctions(scope: KoScope): List<KoFunctionDeclaration> = scope.functions(includeNested = true, includeLocal = false)

/** Interface name to its member names, for every interface of [scope]. */
internal fun portMethods(scope: KoScope): Map<String, Set<String>> =
    scope.interfaces(includeNested = true).associate { it.name to it.functions().map { f -> f.name }.toSet() }

/**
 * The callee text when [fn] is a pass-through, else null. Exempt: an `override` or `operator` function, a function
 * without parameters, one with a parameter default (it supplies something), one without a body, an interface member (a default method included), a function of a class
 * implementing an interface of [ports] (name to member names, ADR-0083) that declares a member of that name — by
 * name over every implemented interface, since Konsist does not resolve which member a function overrides — and a
 * forward whose callee, or any receiver before it, is a non-public (private, protected or internal) member of the
 * enclosing class or object, its companion or a parent class, a constructor property included, or a private top-level
 * function or property of the same file — encapsulation, not a layer.
 */
internal fun passThroughTarget(
    fn: KoFunctionDeclaration,
    ports: Map<String, Set<String>>,
): String? {
    if (fn.hasOverrideModifier || fn.hasOperatorModifier || fn.numParameters == 0) return null
    val owner = fn.containingDeclaration
    if (owner is KoInterfaceDeclaration) return null
    if (owner is KoClassDeclaration && owner.parents().any { ports[it.name.substringBefore("<")]?.contains(fn.name) == true }) return null
    val parameters = (fn as KoParametersProvider).parameters
    if (parameters.any { it.hasDefaultValue() }) return null
    val forwarded = parameters.map { if (it.hasModifier(KoModifier.VARARG)) "*${it.name}" else it.name }
    val target = forwardedCall(fn.text, forwarded) ?: return null
    val hidden = nonPublicMembers(owner) + privateFileMembers(fn)
    return target.takeUnless { reachedNames(target).any { it in hidden } }
}

private val TYPE_ARGUMENTS = Regex("""<.*>$""")

/** The names a callee reaches: each receiver of the chain and, for a bare call, the callee, `this`, `?.`, `!!` and `<T>` stripped. */
private fun reachedNames(callee: String): List<String> {
    val parts = callee.replace(THIS, "").split('.').map { it.replace(TYPE_ARGUMENTS, "").trimEnd('?', '!') }
    return if (parts.size > 1) parts.dropLast(1) else parts
}

/** Names of the private top-level functions and properties of the file declaring [fn]. */
private fun privateFileMembers(fn: KoFunctionDeclaration): Set<String> {
    val file = fn.containingFile
    val publicFunctions =
        file
            .functions(includeNested = false)
            .filterNot { it.hasModifier(KoModifier.PRIVATE) }
            .map { it.name }
            .toSet()
    return (
        file.functions(includeNested = false).filter { it.hasModifier(KoModifier.PRIVATE) }.map { it.name } +
            file.properties(includeNested = false).filter { it.hasModifier(KoModifier.PRIVATE) }.map { it.name }
    ).toSet() - publicFunctions
}

private fun KoModifierProvider.isNonPublic() =
    hasModifier(KoModifier.PRIVATE) || hasModifier(KoModifier.INTERNAL) || hasModifier(KoModifier.PROTECTED)

/** Names of the non-public properties (constructor properties included) and functions of [owner], its companion and its parent classes. */
private fun nonPublicMembers(owner: KoBaseProvider?): Set<String> {
    val sources =
        listOfNotNull(owner) +
            (
                (owner as? KoClassDeclaration)
                    ?.let {
                        listOfNotNull(it.companionObject) +
                            it.parentClasses(indirectParents = true).mapNotNull { parent -> parent.sourceDeclaration?.asClassDeclaration() }
                    }.orEmpty()
            )
    val properties = sources.flatMap { (it as? KoPropertyProvider)?.properties(includeNested = false).orEmpty() }
    val functions = sources.flatMap { (it as? KoFunctionProvider)?.functions(includeNested = false).orEmpty() }
    val constructorParameters = sources.flatMap { (it as? KoClassDeclaration)?.primaryConstructor?.parameters.orEmpty() }
    val publicFunctions = functions.filterNot { it.isNonPublic() }.map { it.name }.toSet()
    return (
        properties.filter { it.isNonPublic() }.map { it.name } +
            constructorParameters.filter { it.isNonPublic() }.map { it.name } +
            functions.filter { it.isNonPublic() }.map { it.name }
    ).toSet() - publicFunctions
}

private val THIS = Regex("""^this(@\w+)?\.""")
private val RETURN = Regex("""^return\b""")
private val CALLEE = Regex("""[A-Za-z_][\w.?!@]*(<[^()]*>)?""")
private val NAMED_ARG = Regex("""^[A-Za-z_]\w*\s*=(?!=)\s*""")

/**
 * The callee when the body of the function declared by [functionText] is exactly one call
 * (`{ return x(…) }`, `{ x(…) }` or `= x(…)`, a receiver chain allowed) whose arguments are [parameters], each
 * passed unchanged and in order, else null.
 */
internal fun forwardedCall(
    functionText: String,
    parameters: List<String>,
): String? {
    val code = KotlinText.codeOnly(functionText)
    val funAt = Regex("""\bfun\b""").find(code)?.range?.last ?: return null
    val start = KotlinText.bodyStart(code, funAt) ?: return null
    val body =
        if (code[start] == '=') {
            code.substring(start + 1).trim()
        } else {
            val end = KotlinText.closingBrace(code, start)
            code
                .substring(start + 1, end)
                .trim()
                .replace(RETURN, "")
                .trim()
        }
    val callee = CALLEE.matchAt(body, 0) ?: return null
    val open = callee.range.last + 1
    if (open >= body.length || body[open] != '(') return null
    val close = matchingParen(body, open)
    if (close != body.length - 1) return null
    val args = splitArguments(body.substring(open + 1, close)).map { it.trim().replace(NAMED_ARG, "") }
    return if (args == parameters) body.substring(0, open) else null
}

private fun matchingParen(
    text: String,
    open: Int,
): Int {
    var depth = 0
    for (i in open until text.length) {
        when (text[i]) {
            '(' -> depth++
            ')' -> if (--depth == 0) return i
        }
    }
    return -1
}

private fun splitArguments(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val parts = mutableListOf<String>()
    var depth = 0
    var from = 0
    text.forEachIndexed { i, c ->
        when (c) {
            '(', '[', '{' -> {
                depth++
            }

            ')', ']', '}' -> {
                depth--
            }

            ',' -> {
                if (depth == 0) {
                    parts += text.substring(from, i)
                    from = i + 1
                }
            }
        }
    }
    parts += text.substring(from)
    return parts.filter { it.isNotBlank() }
}
