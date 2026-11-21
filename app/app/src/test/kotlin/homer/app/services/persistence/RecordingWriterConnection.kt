package homer.app.services.persistence

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records the raw SQL of every statement on the writer handle's connections (`wrapWriterConnection` seam),
 * which `SqlStatementRecorder` misses because [SchemaMigrations] runs its DDL through `rawStatement`.
 * One instance per test, so parallel runs cannot mix statements.
 */
internal class RecordingWriterConnection {
    /** Every raw statement's SQL, in execution order. */
    val statements = CopyOnWriteArrayList<String>()

    /** The `wrapWriterConnection` value that records through this instance. */
    val wrap: (Connection) -> Connection = { connection ->
        Proxy.newProxyInstance(
            RecordingWriterConnection::class.java.classLoader,
            arrayOf(Connection::class.java),
            Handler(connection, statements),
        ) as Connection
    }

    /** The recorded statements whose SQL contains [fragment] — the ones a migration step issues. */
    fun matching(fragment: String): List<String> = statements.filter { fragment in it }

    /** A [Connection] that delegates to `real` and records what the statements it hands out run. */
    private class Handler(
        private val real: Connection,
        private val statements: MutableList<String>,
    ) : InvocationHandler {
        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            val result = delegate(real, method, args)
            return if (method.name == "createStatement") recording(result as Statement) else result
        }

        private fun recording(statement: Statement): Statement =
            Proxy.newProxyInstance(
                RecordingWriterConnection::class.java.classLoader,
                arrayOf(Statement::class.java),
                InvocationHandler { _, method, args ->
                    val sql = args?.firstOrNull() as? String
                    val result = delegate(statement, method, args)
                    if (sql != null && method.name.startsWith("execute")) statements += sql
                    result
                },
            ) as Statement
    }
}
