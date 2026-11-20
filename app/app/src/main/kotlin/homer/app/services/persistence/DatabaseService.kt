package homer.app.services.persistence

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.Logger
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.PrintWriter
import java.sql.Connection
import javax.sql.DataSource
import java.util.logging.Logger as JulLogger

/** Owns the Exposed `Database` over SQLite, the single source of truth for durable state. See ADR-0020. */
class DatabaseService(
    private val databasePath: String,
    private val logger: Logger,
    // Blocking JDBC runs here, off the main dispatcher (overridable in tests).
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Wraps the writer handle's JDBC connections; must happen at the DataSource because
    // Exposed discards the `setupConnection` hook's return value.
    private val wrapWriterConnection: (Connection) -> Connection = IDENTITY_CONNECTION_WRAPPER,
    // The writer handle's retry policy; production uses the single-attempt default.
    private val writerDatabaseConfig: DatabaseConfig = WRITER_DATABASE_CONFIG,
) {
    val database: Database by lazy { open() }

    @Volatile
    private var opened = false

    private fun open(): Database {
        val dataSource = writerDataSource()
        val db =
            Database.connect(
                if (wrapWriterConnection === IDENTITY_CONNECTION_WRAPPER) {
                    dataSource
                } else {
                    ConnectionWrappingDataSource(dataSource, wrapWriterConnection)
                },
                databaseConfig = writerDatabaseConfig,
            )
        opened = true
        logger.info("Opened SQLite database at {}", databasePath)
        return db
    }

    /** The writer handle's [DataSource]: the connection pragmas above, over the database file. */
    private fun writerDataSource(): DataSource =
        SQLiteDataSource(writerConfig()).apply {
            url = "jdbc:sqlite:$databasePath"
        }

    /** The write handle's pragmas, shared by [open] and [close]'s off-path checkpoint connection. */
    private fun writerConfig(): SQLiteConfig =
        SQLiteConfig().apply {
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(BUSY_TIMEOUT_MS)
            setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
        }

    private val readerConnectionsLazy = lazy { openReaderConnections() }

    /** Read connections kept open between transactions. Internal for tests. */
    internal val readerConnections: IdleConnectionCache by readerConnectionsLazy

    private val readDatabase: Database by lazy { Database.connect({ readerConnections.borrow() }) }

    private fun openReaderConnections(): IdleConnectionCache {
        // The writer handle creates the file and switches it to WAL; the reader relies on both.
        database
        val config =
            SQLiteConfig().apply {
                setBusyTimeout(BUSY_TIMEOUT_MS)
                setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED)
            }
        val dataSource =
            SQLiteDataSource(config).apply {
                url = "jdbc:sqlite:$databasePath"
            }
        return IdleConnectionCache(READER_IDLE_CONNECTIONS) {
            dataSource.connection.also { c -> c.createStatement().use { it.execute("PRAGMA query_only = ON") } }
        }
    }

    /** Creates tables if missing and stamps the schema version. Idempotent. */
    fun migrate() {
        transaction(database) {
            SchemaUtils.create(
                Processes,
                ProcessInputs,
                ProcessTags,
                Questions,
                RunMessages,
                Dispatches,
                DispatchGroupQuestions,
                Continuations,
                ContinuationWatches,
                PendingHooks,
                ResultTokens,
                CostEntries,
                ServerMeta,
            )
            rawStatement { st ->
                val current = st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                if (current < SCHEMA_VERSION) {
                    upgradeSchema(st, current)
                    st.execute("PRAGMA user_version = $SCHEMA_VERSION")
                    logger.info("Database schema version set to {} (was {})", SCHEMA_VERSION, current)
                }
            }
        }
    }

    @Volatile
    private var maintenance: Job? = null

    // Refreshes planner statistics; `0x10000` is load-bearing: a plain `PRAGMA optimize` only
    // considers tables this connection queried, and each transaction opens a new connection.
    fun optimize() {
        runCatching {
            transaction(database) { rawStatement { it.execute("PRAGMA optimize=0x10002") } }
        }.onFailure { logger.warn("PRAGMA optimize failed: {}", it.message) }
    }

    /** Runs [optimize] every [intervalMs] until [close]. */
    fun startMaintenance(
        scope: CoroutineScope,
        intervalMs: Long = OPTIMIZE_INTERVAL_MS,
    ) {
        maintenance =
            scope.launch(ioDispatcher) {
                while (isActive) {
                    delay(intervalMs)
                    optimize()
                }
            }
    }

    fun close() {
        maintenance?.cancel()
        // Never force-open during shutdown: if startup aborted before migrate()
        // touched the database, there is nothing to checkpoint.
        if (!opened) return
        // Before the checkpoint: an open reader connection would keep the WAL alive.
        if (readerConnectionsLazy.isInitialized()) readerConnections.close()
        // Flush the WAL into the main db on shutdown so the file is self-contained for inspection/backup.
        runCatching {
            checkpoint()
        }.onFailure { logger.warn("WAL checkpoint on shutdown failed: {}", it.message) }
        logger.info("Closed SQLite database at {}", databasePath)
    }

    // `PRAGMA wal_checkpoint(TRUNCATE)` on a short-lived autocommit connection, never inside a
    // transaction: `transaction_mode=IMMEDIATE` would get `SQLITE_LOCKED`. See ADR-0020.
    private fun checkpoint() {
        val dataSource = writerDataSource()
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                // A checkpoint blocked by another connection's lock reports busy != 0 in
                // the result row instead of throwing, so the failure would be silent.
                st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)").use { rs ->
                    if (rs.next() && rs.getInt(1) != 0) {
                        logger.warn("WAL checkpoint on shutdown blocked (busy) — the -wal file was not truncated")
                    }
                }
            }
        }
    }

    private val bounceWriterToAutoCommit = BounceWriterToAutoCommit(logger)

    /** Runs [block] inside one write-locked transaction on [ioDispatcher] (blocking JDBC). */
    suspend fun <T> query(block: JdbcTransaction.() -> T): T =
        withContext(ioDispatcher) {
            transaction(database) {
                registerInterceptor(bounceWriterToAutoCommit)
                block()
            }
        }

    // Runs a read-only [block] inside one DEFERRED, `query_only` transaction on [ioDispatcher];
    // a write inside it fails with `SQLITE_READONLY`. See ADR-0020.
    suspend fun <T> read(block: JdbcTransaction.() -> T): T =
        withContext(ioDispatcher) {
            transaction(readDatabase) { block() }
        }

    private fun <T> JdbcTransaction.rawStatement(block: (java.sql.Statement) -> T): T {
        val raw = connection.connection as java.sql.Connection
        return raw.createStatement().use(block)
    }

    /** Delegates every [DataSource] method to the real one and wraps the connection. */
    private class ConnectionWrappingDataSource(
        private val delegate: DataSource,
        private val wrap: (Connection) -> Connection,
    ) : DataSource {
        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        override fun getLogWriter(): PrintWriter = delegate.logWriter

        override fun setLogWriter(out: PrintWriter?) = delegate.setLogWriter(out)

        override fun setLoginTimeout(seconds: Int) = delegate.setLoginTimeout(seconds)

        override fun getLoginTimeout(): Int = delegate.loginTimeout

        override fun getParentLogger(): JulLogger = delegate.parentLogger

        override fun <T : Any?> unwrap(iface: Class<T>?): T = delegate.unwrap(iface)

        override fun isWrapperFor(iface: Class<*>?): Boolean = delegate.isWrapperFor(iface)
    }

    companion object {
        const val SCHEMA_VERSION = 34
        const val OPTIMIZE_INTERVAL_MS = 60 * 60 * 1000L
        private const val BUSY_TIMEOUT_MS = 5000

        private val IDENTITY_CONNECTION_WRAPPER: (Connection) -> Connection = { it }

        // One attempt, never a retry: a block here may have landed its data before throwing, and
        // the blocks are not idempotent. See ADR-0020.
        val WRITER_DATABASE_CONFIG: DatabaseConfig = DatabaseConfig { defaultMaxAttempts = 1 }

        // Ends the driver's post-commit re-`BEGIN IMMEDIATE` so the write lock ends with the
        // transaction, not with Exposed's connection teardown; holds no per-transaction state. See ADR-0020.
        private class BounceWriterToAutoCommit(
            private val logger: Logger,
        ) : StatementInterceptor {
            override fun afterCommit(transaction: Transaction) {
                val raw = (transaction as JdbcTransaction).connection.connection as java.sql.Connection
                // The commit has landed: a failure only keeps the lock until teardown, so it must not fail the write (ADR-0020).
                runCatching { raw.autoCommit = true }.onFailure {
                    logger.warn("Writer connection stayed in a transaction after a commit; the write landed: {}", it.message)
                }
            }
        }

        /** Read connections kept open between transactions; more open on demand, never waited for. */
        private const val READER_IDLE_CONNECTIONS = 4
    }
}
