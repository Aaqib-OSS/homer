package homer.app.services.persistence

import homer.app.fixtures.SqlStatementRecorder
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.insert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultTokenRepositoryTest {
    private lateinit var db: DatabaseService
    private lateinit var repo: ResultTokenRepository
    private lateinit var dbPath: String

    @BeforeEach
    fun setup(
        @TempDir dir: Path,
    ) {
        dbPath = dir.resolve("d.db").toString()
        db = DatabaseService(dbPath, LoggerFactory.getLogger("t"))
        db.migrate()
        repo = ResultTokenRepository(db)
    }

    private fun record(
        token: String,
        expiresAt: Long? = null,
        transformer: ResultEnvParam = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.x")),
    ) = ResultTokenRecord(
        token = token,
        watchedProcessId = 42,
        path = "cmd-0.out",
        resultTransformer = transformer,
        sizeBytes = 1_000_000,
        createdAt = 1,
        expiresAt = expiresAt,
        consumerAgent = "parent-agent",
    )

    @Test
    fun `create then get round-trips fields including transformer`() =
        runTest {
            val transformer = ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.summary"))
            repo.create(record("tok-1", expiresAt = 500, transformer = transformer))
            val found = repo.get("tok-1")!!
            assertEquals(42, found.watchedProcessId)
            assertEquals("cmd-0.out", found.path)
            assertEquals(transformer, found.resultTransformer)
            assertEquals(1_000_000, found.sizeBytes)
            assertEquals(500, found.expiresAt)
            // The token is audience-bound to the agent that will redeem it (ADR-0025).
            assertEquals("parent-agent", found.consumerAgent)
        }

    @Test
    fun `get returns null for an unknown token`() =
        runTest {
            assertNull(repo.get("nope"))
        }

    @Test
    fun `get returns null for a row without a transformer, so no token redeems a raw read`() =
        runTest {
            db.query {
                ResultTokens.insert {
                    it[token] = "raw"
                    it[watchedProcessId] = 42
                    it[path] = "cmd-0.out"
                    it[resultTransformer] = null
                    it[sizeBytes] = 1
                    it[createdAt] = 1
                    it[expiresAt] = null
                    it[consumerAgent] = "parent-agent"
                }
            }

            assertNull(repo.get("raw"))
        }

    @Test
    fun `deleteExpired removes lapsed tokens and keeps live or non-expiring ones`() =
        runTest {
            repo.create(record("expired", expiresAt = 100))
            repo.create(record("live", expiresAt = 300))
            repo.create(record("forever", expiresAt = null))
            val removed = repo.deleteExpired(now = 200)
            assertEquals(1, removed)
            assertNull(repo.get("expired"))
            assertEquals("live", repo.get("live")!!.token)
            assertEquals("forever", repo.get("forever")!!.token)
        }

    // --- Idle sweep takes no write lock ---

    @Test
    fun `deleteExpired with no due row returns promptly while another connection holds the write lock`() =
        runTest {
            repo.create(record("forever", expiresAt = null))
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { writer ->
                writer.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                var removed = -1
                val elapsedMs = measureTimeMillis { removed = repo.deleteExpired(now = 999_999) }
                writer.createStatement().use { it.execute("ROLLBACK") }

                // A sweep that took the write lock would sit out the whole 5 s busy_timeout.
                assertTrue(elapsedMs < 2_000, "idle sweep waited $elapsedMs ms behind the writer")
                assertEquals(0, removed)
            }
        }

    // --- The expiry index (schema v34) ---

    private fun explain(
        st: java.sql.Statement,
        sql: String,
    ): List<String> =
        st.executeQuery("EXPLAIN QUERY PLAN $sql").use { rs ->
            generateSequence { if (rs.next()) rs.getString("detail") else null }.toList()
        }

    /** The expiry index's definition as SQLite stores it, without the quoting and guard that differ by creator. */
    private fun expiryIndexDefinition(): String? =
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'result_tokens_expires_at'").use {
                    if (it.next()) it.getString(1).replace(" IF NOT EXISTS", "").replace("\"", "") else null
                }
            }
        }

    /** Sweeps at [now] and plans both statements it sent, the due-row pre-check and the delete, on [st]. */
    private suspend fun assertSweepSeeksTheExpiryIndex(
        st: java.sql.Statement,
        now: Long,
    ) {
        SqlStatementRecorder.reset()
        assertTrue(repo.deleteExpired(now) > 0, "the seed must leave tokens due at $now")
        val sent = SqlStatementRecorder.statements()
        assertEquals(2, sent.size, "$sent")
        for (sql in sent) {
            val plan = explain(st, sql)
            assertTrue(plan.none { it.startsWith("SCAN ") }, "scan for '$sql': $plan")
            assertTrue(plan.any { "INDEX result_tokens_expires_at" in it }, "'$sql' not planned on result_tokens_expires_at: $plan")
        }
    }

    @Test
    fun `the sweep seeks the expiry index at 150k tokens, with and without statistics`() =
        runTest {
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { st ->
                    // Production-shaped: one token minted a second, a tenth with no TTL, the rest lapsing an
                    // hour after minting, so a sweep tick finds only the few that lapsed since the last one.
                    st.execute(
                        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 150000) " +
                            "INSERT INTO result_tokens (token, watched_process_id, path, size_bytes, created_at, expires_at, " +
                            "consumer_agent) SELECT 'tok-' || i, i, 'cmd_0.out', 1, i, " +
                            "CASE WHEN i % 10 = 0 THEN NULL ELSE i + 3600 END, 'agent-' || (i % 12) FROM n",
                    )
                    assertSweepSeeksTheExpiryIndex(st, now = 3_700)
                }
            }
            db.optimize()
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM sqlite_stat1 WHERE tbl = 'result_tokens'").use { rs ->
                        assertTrue(rs.next() && rs.getInt(1) > 0, "no planner statistics for result_tokens")
                    }
                    assertSweepSeeksTheExpiryIndex(st, now = 3_800)
                }
            }
        }

    @Test
    fun `a v33 database gains the expiry index a fresh one has`() =
        runTest {
            val fresh = assertNotNull(expiryIndexDefinition())
            db.close()
            DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
                c.createStatement().use { st ->
                    st.execute("DROP INDEX result_tokens_expires_at")
                    st.execute("PRAGMA user_version = 33")
                }
            }

            DatabaseService(dbPath, LoggerFactory.getLogger("t")).also { it.migrate() }.close()

            assertEquals(fresh, expiryIndexDefinition())
        }
}
