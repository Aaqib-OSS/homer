package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.DatabaseConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [Connection] whose `commit()` lands the write and then throws `SQLITE_BUSY`, as sqlite-jdbc's
 * re-`BEGIN IMMEDIATE` inside `commit()` can (ADR-0020). Exposed replays the `query { }` block under the
 * default `maxAttempts`; the single-attempt writer handle runs it once and the failure propagates.
 */
internal class PostCommitFailureConnection(
    private val real: Connection,
) : InvocationHandler {
    val commits = AtomicInteger(0)

    override fun invoke(
        proxy: Any,
        method: Method,
        args: Array<out Any?>?,
    ): Any? {
        val result = delegate(real, method, args)
        // After the delegation, never before: the commit has landed.
        if (method.name == "commit") {
            commits.incrementAndGet()
            throw SQLException("database is locked")
        }
        return result
    }

    companion object {
        /**
         * Wraps [connection] so every `commit()` lands and then throws. A fresh
         * Exposed transaction opens a fresh connection, so this fires on every
         * attempt rather than only the first.
         */
        fun wrap(connection: Connection): Connection =
            Proxy.newProxyInstance(
                PostCommitFailureConnection::class.java.classLoader,
                arrayOf(Connection::class.java),
                PostCommitFailureConnection(connection),
            ) as Connection

        /** Exposed's own default writer config — the one the post-commit failure replays a block under. */
        fun multiAttemptDatabaseConfig(maxAttempts: Int = 3): DatabaseConfig = DatabaseConfig { defaultMaxAttempts = maxAttempts }
    }
}
