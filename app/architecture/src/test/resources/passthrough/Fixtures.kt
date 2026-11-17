// Fixture sources for PassThroughMatcherTest: the matcher reads these, nothing compiles them.
// Each case is a function named for what it shows; the test looks it up by name.

fun inOrder(a: Int, b: String) = target(a, b)

fun blockReturn(a: Int, b: String): Int {
    return target(a, b)
}

fun blockStatement(a: Int) {
    target(a)
}

fun receiverChain(a: Int, b: Int) = repo.save(a, b)

fun <T> namedInOrder(a: T, b: List<T>) = target(a = a, b = b)

fun reordered(a: Int, b: Int) = target(b, a)

fun namedOutOfOrder(a: Int, b: Int) = target(b = b, a = a)

fun transformed(a: Int) = target(a + 1)

fun extra(a: Int) = target(a, 1)

fun missing(a: Int, b: Int) = target(a)

fun chained(a: Int) = target(a).also { }

fun plus(a: Int) = target(a) + 1

fun twoStatements(a: Int) {
    log(a)
    target(a)
}

fun parameterless() = target()

class Overrides {
    override fun overridden(a: Int) = target(a)

    operator fun plus2(a: Int) = target(a)
}

abstract class Abstract {
    abstract fun abstractFn(a: Int)
}

class Implements : Port {
    fun portMember(a: Int) = target(a)

    fun portNonMember(a: Int) = target(a)
}

class PrivateProperty {
    private val m = M()

    fun privateProperty(a: Int) {
        m.remove(a)
    }

    fun topLevelCall(a: Int) = target(a)

    fun parameterReceiver(a: A) = a.get(a)
}

class PrivateConstructorProperty(private val m: M) {
    fun privateConstructorProperty(a: Int) = this.m.get(a)
}

object PrivateObjectProperty {
    private val m = M()

    fun privateObjectProperty(a: Int) = m.get(a)
}

class PublicConstructorProperty(val m: M) {
    fun publicConstructorProperty(a: Int) = m.get(a)
}

class PublicProperty {
    val m = M()

    fun publicProperty(a: Int) = m.get(a)
}

class InternalProperty {
    internal val m = M()

    fun internalProperty(a: Int) = m.get(a)
}

class PrivateFunction {
    fun privateFunctionTarget(a: Int) = g(a)

    private fun g(a: Int) = a
}

class InternalFunction {
    fun internalFunctionTarget(a: Int) = this.g(a)

    internal fun g(a: Int) = a
}

class PublicFunction {
    fun publicFunctionTarget(a: Int) = g(a)

    fun g(a: Int) = a
}

interface WithDefault {
    fun interfaceDefault(a: Int) = g(a)

    fun g(a: Int) = a
}

// `return` needs a word boundary: this calls `returnX`, not `X`.
fun wordBoundary(a: Int) {
    returnX(a)
}

class Outer {
    fun g(a: Int) = a

    inner class Inner {
        fun labelledThis(a: Int) = this@Outer.g(a)
    }
}

// A vararg parameter is forwarded by spreading it.
fun varargSpread(vararg a: Int) = target(*a)

fun varargNotSpread(vararg a: Int) = target(a)

// A default value is something the wrapper adds.
fun withDefault(a: Int = 5) = target(a)

// As implemented: the extension receiver is not a parameter, and a type argument stays in the callee.
fun String.extensionReceiver(a: Int) = target(a)

fun genericCallee(a: Int) = target<Int>(a)

// A private top-level function or property of the same file is encapsulation at file level.
private fun fileHelper(a: Int) = a

private val fileState = mutableListOf<Int>()

fun viaFileHelper(a: Int) = fileHelper(a)

fun viaFileState(a: Int) = fileState.add(a)
