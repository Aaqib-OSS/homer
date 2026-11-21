package homer.app.services.persistence

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals

/** The v35 backfill: only a PENDING row with no expiry of its own gets `created_at` plus seven days. */
class DefaultExpiryMigrationTest {
    private val logger = LoggerFactory.getLogger(DefaultExpiryMigrationTest::class.java)

    @Test
    fun `v35 gives only PENDING rows without an expiry the default one`(
        @TempDir dir: Path,
    ) = runTest {
        val dbPath = dir.resolve("v35-backfill.db").toString()
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        val rows =
            listOf(
                Triple("pending-null", "PENDING", null),
                Triple("pending-own", "PENDING", 5_000L),
                Triple("settled-null", "DISPATCHED", null),
            )
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                for ((table, key) in listOf("dispatches" to "question_id", "continuations" to "resume_param")) {
                    val required =
                        if (table == "dispatches") {
                            "answer_param, origin_process_id, origin_agent_name, params, agent_name"
                        } else {
                            "watched_process_id, origin_process_id, origin_agent_name, params, agent_name"
                        }
                    val values = if (table == "dispatches") "'a', 1, 'o', '{}', 't'" else "1, 1, 'o', '{}', 't'"
                    for ((id, status, expires) in rows) {
                        st.execute(
                            "INSERT INTO $table ($key, $required, status, created_at, expires_at) " +
                                "VALUES ('$id', $values, '$status', 1000, ${expires ?: "NULL"})",
                        )
                    }
                }
                st.execute("PRAGMA user_version = 34")
            }
        }
        DatabaseService(dbPath, logger).also {
            it.migrate()
            it.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { c ->
            c.createStatement().use { st ->
                assertEquals(
                    35,
                    st.executeQuery("PRAGMA user_version").use {
                        it.next()
                        it.getInt(1)
                    },
                )
                for ((table, key) in listOf("dispatches" to "question_id", "continuations" to "resume_param")) {
                    val expiry =
                        st.executeQuery("SELECT $key, expires_at FROM $table").use { rs ->
                            generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap()
                        }
                    assertEquals(
                        mapOf("pending-null" to "605800", "pending-own" to "5000", "settled-null" to null),
                        expiry,
                        table,
                    )
                }
            }
        }
    }
}
