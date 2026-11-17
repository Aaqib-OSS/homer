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
