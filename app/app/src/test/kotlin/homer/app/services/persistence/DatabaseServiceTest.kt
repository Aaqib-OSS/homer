package homer.app.services.persistence

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.io.path.exists
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

class DatabaseServiceTest {
    private val logger = LoggerFactory.getLogger(DatabaseServiceTest::class.java)

    private companion object {
        /** The writer handle's own `busy_timeout`, so a lock left held fails rather than hangs. */
        const val BUSY_TIMEOUT_MS = 5_000
    }

    /** Attaches a started [ListAppender] to the test's logback logger; detach it in `finally`. */
    private fun attachListAppender(): Pair<LogbackLogger, ListAppender<ILoggingEvent>> {
        val logbackLogger = logger as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logbackLogger.addAppender(appender)
        return logbackLogger to appender
    }

    @Test
    fun `a read does not wait for a writer holding the write lock`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
            writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            writer.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('WORKING', 'uncommitted', '[]', '[]')",
                )
            }
            var count = -1L
            val elapsedMs = measureTimeMillis { count = svc.read { Processes.selectAll().count() } }
            writer.createStatement().use { it.execute("ROLLBACK") }

            // A read that queued for the write lock would sit out the whole 5 s busy_timeout and then fail.
            assertTrue(elapsedMs < 2_000, "read waited $elapsedMs ms behind the writer")
            assertEquals(0L, count, "read must see the last committed state, not the writer's pending row")
        }
    }

    @Test
    fun `a read refuses to write`(
        @TempDir dir: Path,
    ) = runTest {
        val svc = DatabaseService(dir.resolve("homer.db").toString(), logger)
        svc.migrate()
        assertFailsWith<ExposedSQLException> { svc.read { Processes.deleteAll() } }
    }

    @Test
    fun `the migrated schema declares no foreign key`(
        @TempDir dir: Path,
    ) = runTest {
        // Pins ADR-0020's amendment: no `foreign_keys` pragma is set and no table
        // declares a foreign key, so declaring a `REFERENCES` later must fail here.
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()

        val schemaSql =
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT name, sql FROM sqlite_master WHERE sql IS NOT NULL").use { rs ->
                        generateSequence { if (rs.next()) rs.getString("name") to rs.getString("sql") else null }
                            .toList()
                    }
                }
            }

        assertTrue(schemaSql.isNotEmpty(), "migrate() created no schema objects")
        val withForeignKey = schemaSql.filter { (_, sql) -> sql.contains("REFERENCES", ignoreCase = true) }
        assertTrue(withForeignKey.isEmpty(), "schema declares a foreign key: ${withForeignKey.map { it.first }}")
    }

    @Test
    fun `a second writer takes the lock immediately after a query transaction commits`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()

        // Probe at the after-commit seam, where the writer connection is still open: a check after
        // query { } returns would always pass. Only query { } releases the lock early (ADR-0020).
        val probe =
            object : StatementInterceptor {
                var failure: String? = null

                override fun afterCommit(transaction: Transaction) {
                    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { second ->
                        second.createStatement().use { it.execute("PRAGMA busy_timeout = $BUSY_TIMEOUT_MS") }
                        try {
                            second.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                            second.createStatement().use {
                                it.execute(
                                    "INSERT INTO processes (status, agent_name, history, executions) " +
                                        "VALUES ('WORKING', 'second', '[]', '[]')",
                                )
                            }
                            second.createStatement().use { it.execute("COMMIT") }
                        } catch (e: Exception) {
                            failure = e.message
                        }
                    }
                }
            }

        repeat(3) { i ->
            svc.query {
                registerInterceptor(probe)
                exec(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('WORKING', 'committed-$i', '[]', '[]')",
                )
            }
        }

        assertNull(probe.failure, "second writer could not take the lock after commit: ${probe.failure}")
        assertEquals(6L, svc.read { Processes.selectAll().count() }, "both writers' rows must have landed")
    }

    @Test
    fun `concurrent writers still serialize on the write lock`(
        @TempDir dir: Path,
    ) = runTest {
        val svc = DatabaseService(dir.resolve("homer.db").toString(), logger)
        svc.migrate()

        // Every write transaction begins with BEGIN IMMEDIATE (ADR-0020): N concurrent writers all
        // commit, serialized, and none waits past busy_timeout.
        val elapsed =
            measureTimeMillis {
                withContext(Dispatchers.IO) {
                    (1..8)
                        .map {
                            async {
                                svc.query {
                                    exec(
                                        "INSERT INTO processes (status, agent_name, history, executions) " +
                                            "VALUES ('WORKING', 'writer-$it', '[]', '[]')",
                                    )
                                }
                            }
                        }.awaitAll()
                }
            }

        assertTrue(elapsed < BUSY_TIMEOUT_MS, "8 serialized writers took $elapsed ms, past busy_timeout")
        assertEquals(8L, svc.read { Processes.selectAll().count() })
    }

    @Test
    fun `close truncates the WAL`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        val wal = dir.resolve("homer.db-wal")

        // The second connection's read snapshot keeps the -wal alive, so the assert cannot pass without
        // close()'s checkpoint; it is released before close() so the checkpoint is not pinned.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { keeper ->
            keeper.autoCommit = false
            keeper.createStatement().use { it.executeQuery("SELECT count(*) FROM processes").use { rs -> rs.next() } }
            svc.query {
                exec(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('WORKING', 'wal', '[]', '[]')",
                )
            }
            assertTrue(Files.size(wal) > 0, "the committed write should still be in the -wal before close()")
            keeper.commit()
            svc.close()
            assertEquals(0L, Files.size(wal), "close() must truncate the WAL, not leave it for teardown")
        }
    }

    /** One row appended per block execution, so a replay shows up as a longer [attempts] list. */
    private fun appendCost(attempts: MutableList<Int>): JdbcTransaction.() -> Unit =
        {
            attempts += attempts.size + 1
            CostEntries.insert {
                it[agentName] = "a"
                it[timestampSec] = 1L
                it[costUsd] = 1.0
            }
        }

    @Test
    fun `a commit that throws after it landed replays the write block under Exposed's default config`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("homer.db").toString()
        DatabaseService(path, logger).migrate()
        val attempts = mutableListOf<Int>()
        // Reproduces the post-commit SQLITE_BUSY replay against Exposed's own default (maxAttempts = 3): the commit
        // lands and still throws, so the block runs three times and appends three rows.
        val db =
            DatabaseService(
                path,
                logger,
                wrapWriterConnection = PostCommitFailureConnection::wrap,
                writerDatabaseConfig = PostCommitFailureConnection.multiAttemptDatabaseConfig(),
            ).database

        assertFailsWith<SQLException> { transaction(db) { appendCost(attempts)() } }

        assertEquals(listOf(1, 2, 3), attempts, "the default config must re-run the block on a commit-phase failure")
        assertEquals(3L, countRows(path, "cost_entries"), "each attempt landed its own row")
    }

    @Test
    fun `a commit that throws after it landed runs the write block exactly once on the writer handle`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("homer.db").toString()
        DatabaseService(path, logger).migrate()
        val failing = DatabaseService(path, logger, wrapWriterConnection = PostCommitFailureConnection::wrap)
        val attempts = mutableListOf<Int>()

        val thrown = assertFailsWith<SQLException> { failing.query(appendCost(attempts)) }

        assertEquals("database is locked", thrown.message, "the commit failure propagates unchanged")
        assertEquals(listOf(1), attempts, "the writer handle must run the block once")
        assertEquals(1L, countRows(path, "cost_entries"), "exactly one row may land")
    }

    /** Wraps a writer connection so the first `setAutoCommit(true)` after a commit throws instead of running. */
    private fun failingAutoCommitBounce(connection: java.sql.Connection): java.sql.Connection {
        var committed = false
        var failed = false
        return java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(java.sql.Connection::class.java),
        ) { _, method, args ->
            if (method.name == "setAutoCommit" && args?.single() == true && committed && !failed) {
                failed = true
                throw SQLException("injected autocommit failure")
            }
            delegate(connection, method, args).also { if (method.name == "commit") committed = true }
        } as java.sql.Connection
    }

    @Test
    fun `a failed autocommit bounce after a commit is logged and the write still succeeds`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("homer.db").toString()
        DatabaseService(path, logger).migrate()
        val (logbackLogger, appender) = attachListAppender()
        try {
            val svc = DatabaseService(path, logbackLogger, wrapWriterConnection = ::failingAutoCommitBounce)
            val attempts = mutableListOf<Int>()

            svc.query(appendCost(attempts))

            assertEquals(1L, countRows(path, "cost_entries"), "the committed write landed")
            val warnings = appender.list.filter { it.formattedMessage.contains("stayed in a transaction after a commit") }
            assertEquals(1, warnings.size, "expected one warning, got: ${appender.list.map { it.formattedMessage }}")
            assertTrue(warnings.single().formattedMessage.contains("injected autocommit failure"))
            // Teardown closed the connection, so the lock it kept is free again for the next writer.
            svc.query(appendCost(attempts))
            assertEquals(2L, countRows(path, "cost_entries"))
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }

    @Test
    fun `the writer handle is built single-attempt`(
        @TempDir dir: Path,
    ) = runTest {
        val svc = DatabaseService(dir.resolve("homer.db").toString(), logger)
        assertEquals(1, svc.database.config.defaultMaxAttempts)
        assertEquals(1, DatabaseService.WRITER_DATABASE_CONFIG.defaultMaxAttempts)
    }

    @Test
    fun `a raw transaction on the writer handle runs the block exactly once too`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("homer.db").toString()
        DatabaseService(path, logger).migrate()
        val failing = DatabaseService(path, logger, wrapWriterConnection = PostCommitFailureConnection::wrap)
        val attempts = mutableListOf<Int>()

        // A raw `transaction(db.database)` outside `query { }` is single-attempt too: the config
        // rides the writer Database itself (ADR-0020).
        val thrown = assertFailsWith<SQLException> { transaction(failing.database) { appendCost(attempts)() } }

        assertEquals("database is locked", thrown.message, "the commit failure propagates unchanged")
        assertEquals(listOf(1), attempts, "the raw writer-handle transaction must run the block once")
        assertEquals(1L, countRows(path, "cost_entries"), "exactly one row may land")
    }

    private fun countRows(
        path: String,
        table: String,
    ): Long =
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }

    /** The planner statistics the last `ANALYZE` left, row for row: what `PRAGMA optimize` writes. */
    private fun statistics(path: String): List<String> =
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT tbl, idx, stat FROM sqlite_stat1 ORDER BY tbl, idx").use { rs ->
                    generateSequence { if (rs.next()) "${rs.getString(1)}|${rs.getString(2)}|${rs.getString(3)}" else null }.toList()
                }
            }
        }

    @Test
    fun `maintenance re-runs optimize until close`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        svc.startMaintenance(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO), intervalMs = 20)
        // Rows written after startup are analyzed by a later maintenance tick, not by anything else.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('FINISHED', 'a', '[]', '[]')",
                )
            }
        }
        val analyzed = {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT name FROM sqlite_master WHERE name = 'sqlite_stat1'").use { it.next() } &&
                        st.executeQuery("SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl = 'processes'").use { rs ->
                            rs.next() &&
                                rs.getInt(1) > 0
                        }
                }
            }
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!analyzed() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(analyzed(), "maintenance never gathered statistics")
        svc.close()
    }

    @Test
    fun `optimize on an analyzed database writes no new statistics`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('FINISHED', 'a', '[]', '[]')",
                )
            }
        }
        svc.optimize()
        val analyzed = statistics(dbPath)
        assertTrue(analyzed.any { it.startsWith("processes|") }, "optimize gathered no statistics: $analyzed")
        // Nothing changed since the analysis, so the second call must leave sqlite_stat1 as it is.
        svc.optimize()
        assertEquals(analyzed, statistics(dbPath), "the second optimize rewrote the statistics")
        svc.close()
    }

    @Test
    fun `optimize warns and returns when its statement fails`(
        @TempDir dir: Path,
    ) = runTest {
        val (logbackLogger, appender) = attachListAppender()
        try {
            // A writer handle that cannot hand out a statement: every raw-SQL path on it fails, so
            // the failure can only be reported, never thrown at the caller.
            val svc =
                DatabaseService(
                    dir.resolve("homer.db").toString(),
                    logbackLogger,
                    wrapWriterConnection = FailingStatementConnection::wrap,
                )
            svc.optimize()
            val failures = appender.list.filter { it.formattedMessage.contains("PRAGMA optimize failed") }
            assertEquals(1, failures.size, "expected one warning, got: ${appender.list.map { it.formattedMessage }}")
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }

    @Test
    fun `reads reuse one connection and still see the latest commit`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        assertEquals(0L, svc.read { Processes.selectAll().count() })
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('FINISHED', 'a', '[]', '[]')",
                )
            }
        }
        // A reused connection must not keep the previous read's snapshot.
        assertEquals(1L, svc.read { Processes.selectAll().count() })
        repeat(20) { svc.read { Processes.selectAll().count() } }
        assertEquals(1, svc.readerConnections.opened.get(), "sequential reads opened more than one connection")
        svc.close()
    }

    @Test
    fun `a reused read connection still refuses to write`(
        @TempDir dir: Path,
    ) = runTest {
        val svc = DatabaseService(dir.resolve("homer.db").toString(), logger)
        svc.migrate()
        svc.read { Processes.selectAll().count() }
        assertFailsWith<ExposedSQLException> { svc.read { Processes.deleteAll() } }
        assertEquals(1, svc.readerConnections.opened.get())
        svc.close()
    }

    @Test
    fun `close leaves no WAL behind once readers have been cached`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val svc = DatabaseService(dbPath, logger)
        svc.migrate()
        svc.read { Processes.selectAll().count() }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('FINISHED', 'a', '[]', '[]')",
                )
            }
        }
        // While this connection is open SQLite's last-connection checkpoint cannot run, so an empty
        // WAL is the explicit TRUNCATE checkpoint's doing.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { held ->
            held.createStatement().use { it.execute("SELECT 1") }
            // The cached reader keeps the database open, so the WAL is still there.
            val wal = java.io.File("$dbPath-wal")
            assertTrue(wal.exists() && wal.length() > 0, "expected WAL frames while a reader is cached")
            svc.close()
            assertTrue(!wal.exists() || wal.length() == 0L, "WAL left behind after close: ${wal.length()} bytes")
        }
    }

    @Test
    fun `close does not warn that the WAL checkpoint failed`(
        @TempDir dir: Path,
    ) = runTest {
        val (logbackLogger, appender) = attachListAppender()
        try {
            val svc = DatabaseService(dir.resolve("homer.db").toString(), logbackLogger)
            svc.migrate()
            svc.close()
            val failures = appender.list.filter { it.formattedMessage.contains("WAL checkpoint on shutdown failed") }
            assertTrue(failures.isEmpty(), "close() reported a checkpoint failure: ${failures.map { it.formattedMessage }}")
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }

    @Test
    fun `close warns that the WAL checkpoint was blocked by a held write lock`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("homer.db").toString()
        val (logbackLogger, appender) = attachListAppender()
        val svc = DatabaseService(dbPath, logbackLogger)
        svc.migrate()
        // A write transaction held across close() blocks the TRUNCATE checkpoint, which reports busy
        // in its result row instead of throwing.
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { held ->
            held.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            held.createStatement().use {
                it.execute(
                    "INSERT INTO processes (status, agent_name, history, executions) " +
                        "VALUES ('WORKING', 'uncommitted', '[]', '[]')",
                )
            }
            svc.close()
            val busy = appender.list.filter { it.formattedMessage.contains("WAL checkpoint on shutdown blocked (busy)") }
            assertTrue(
                busy.isNotEmpty(),
                "close() did not report the blocked checkpoint: ${appender.list.map { it.formattedMessage }}",
            )
            val failures = appender.list.filter { it.formattedMessage.contains("WAL checkpoint on shutdown failed") }
            assertTrue(
                failures.isEmpty(),
                "a blocked checkpoint must report busy, not a failure: ${failures.map { it.formattedMessage }}",
            )
        }
    }

    @Test
    fun `close on a never-opened service touches nothing`(
        @TempDir dir: Path,
    ) = runTest {
        val dbFile = dir.resolve("homer.db")
        val (logbackLogger, appender) = attachListAppender()
        try {
            DatabaseService(dbFile.toString(), logbackLogger).close()
            assertTrue(!dbFile.exists(), "close() created the database file")
            assertTrue(
                appender.list.isEmpty(),
                "close() logged on a never-opened service: ${appender.list.map { it.formattedMessage }}",
            )
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }

    @Test
    fun `close on a never-opened service leaves a pre-existing database untouched`(
        @TempDir dir: Path,
    ) = runTest {
        val dbFile = dir.resolve("homer.db")
        DriverManager.getConnection("jdbc:sqlite:$dbFile").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE marker (id INTEGER)") }
        }
        val lengthBefore = dbFile.toFile().length()
        val (logbackLogger, appender) = attachListAppender()
        try {
            DatabaseService(dbFile.toString(), logbackLogger).close()
            assertEquals(lengthBefore, dbFile.toFile().length(), "close() rewrote a pre-existing database")
            assertEquals(
                emptyList(),
                appender.list.map { it.formattedMessage },
                "close() logged on a never-opened service",
            )
        } finally {
            logbackLogger.detachAppender(appender)
        }
    }

    @Test
    fun `a database stamped by a newer release runs no upgrade step and keeps its stamp`(
        @TempDir dir: Path,
    ) = runTest {
        val path = dir.resolve("homer.db").toString()
        DatabaseService(path, logger).migrate()
        val newer = DatabaseService.SCHEMA_VERSION + 1
        DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use { it.execute("PRAGMA user_version = $newer") } }
        val recorded = RecordingWriterConnection()

        DatabaseService(path, logger, wrapWriterConnection = recorded.wrap).also {
            it.migrate()
            it.close()
        }

        assertEquals(emptyList(), recorded.matching("CREATE INDEX") + recorded.matching("ALTER TABLE"), "ran: ${recorded.statements}")
        val stamp =
            DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("PRAGMA user_version").use {
                        it.next()
                        it.getInt(1)
                    }
                }
            }
        assertEquals(newer, stamp)
    }
}
