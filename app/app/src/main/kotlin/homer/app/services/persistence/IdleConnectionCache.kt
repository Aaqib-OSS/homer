package homer.app.services.persistence

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps up to [maxIdle] read connections open between transactions: a cache, not a bound, so with none idle
 * a new one opens and no caller waits. An idle connection holds no transaction, so it pins no WAL snapshot.
 * Readers only: a kept writer holds the write lock after every commit. See ADR-0020.
 */
internal class IdleConnectionCache(
    private val maxIdle: Int,
    private val open: () -> Connection,
) : AutoCloseable {
    private val idle = ConcurrentLinkedDeque<Connection>()
    private val idleCount = AtomicInteger()

    @Volatile
    private var closed = false

    /** Connections opened so far; reuse keeps this below the number of transactions. */
    val opened = AtomicInteger()

    fun borrow(): Connection {
        while (true) {
            val cached = idle.pollFirst()
            if (cached == null) {
                opened.incrementAndGet()
                return lease(open())
            }
            idleCount.decrementAndGet()
            if (!cached.isClosed) return lease(cached)
        }
    }

    override fun close() {
        closed = true
        while (true) {
            val c = idle.pollFirst() ?: break
            idleCount.decrementAndGet()
            runCatching { c.close() }
        }
    }

    private fun giveBack(c: Connection) {
        // Commit whatever the driver re-began after the caller's commit, so the idle
        // connection holds no transaction and no snapshot.
        val clean = !closed && !c.isClosed && runCatching { if (!c.autoCommit) c.autoCommit = true }.isSuccess
        if (clean && idleCount.incrementAndGet() <= maxIdle) {
            idle.addFirst(c)
            // close() may have drained the cache between the check and the add.
            if (closed) close()
            return
        }
        if (clean) idleCount.decrementAndGet()
        runCatching { c.close() }
    }

    /**
     * A handle whose close() returns [c] here, once, and which refuses every other call
     * afterwards: [c] may already serve another transaction. A proxy rather than a
     * `Connection by c` class, because only a proxy can guard all of Connection's methods.
     */
    private fun lease(c: Connection): Connection {
        val returned = AtomicBoolean(false)
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            when (method.name) {
                "close" -> {
                    if (returned.compareAndSet(false, true)) giveBack(c)
                    null
                }

                "isClosed" -> {
                    returned.get() || c.isClosed
                }

                else -> {
                    if (returned.get()) throw SQLException("connection already returned to the read cache")
                    try {
                        method.invoke(c, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        } as Connection
    }
}
