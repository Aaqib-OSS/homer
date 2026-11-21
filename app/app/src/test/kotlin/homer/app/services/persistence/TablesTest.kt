package homer.app.services.persistence

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.Statement
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Integer columns a migration step adds with `ALTER TABLE ADD COLUMN` cannot carry Exposed's int32 range
 * CHECK, so `Tables.kt` declares them without it and a fresh database matches an upgraded one.
 * Type (`INT`), nullability, default and value mapping are unchanged.
 */
class TablesTest {
    private val logger = LoggerFactory.getLogger(TablesTest::class.java)

    @Test
    fun `a fresh database declares the migration-added integer columns without a CHECK and otherwise unchanged`(
        @TempDir dir: Path,
    ) {
        val path = freshDatabase(dir)
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { st ->
                MIGRATION_ADDED.forEach { (column, declared) ->
                    val table = column.table.tableName
                    val ddl = tableSql(st, table)
                    assertFalse(checkOn(column.name).containsMatchIn(ddl), "$table.${column.name} has a CHECK: $ddl")
                    assertEquals(declared, columnInfo(st, table, column.name), "$table.${column.name}")
                }
                // Control: an integer() column Tables.kt declared from the start keeps Exposed's CHECK.
                assertTrue(checkOn("process_id").containsMatchIn(tableSql(st, "questions")))
            }
        }
    }

    @Test
    fun `the migration-added integer columns map Int values as integer() does`(
        @TempDir dir: Path,
    ) {
        MIGRATION_ADDED.forEach { (column, _) -> assertIs<IntegerColumnType>(column.columnType, column.name) }
        val path = freshDatabase(dir)
        val svc = DatabaseService(path.toString(), logger)
        try {
            transaction(svc.database) {
                listOf(Int.MAX_VALUE to Int.MIN_VALUE, null to null).forEach { (parent, root) ->
                    Processes.insert {
                        it[status] = "FAILED"
                        it[agentName] = "a"
                        it[history] = "[]"
                        it[executions] = "[]"
                        it[parentProcessId] = parent
                        it[rootProcessId] = root
                        it[failureReasonRunId] = parent
                    }
                }
                Questions.insert {
                    it[id] = "q1"
                    it[processId] = 1
                    it[agentName] = "a"
                    it[text] = "?"
                    it[optionsJson] = "[]"
                    it[status] = "OPEN"
                    it[createdAt] = 0
                    it[dispatchBound] = 1
                }
                Questions.insert {
                    it[id] = "q2"
                    it[processId] = 1
                    it[agentName] = "a"
                    it[text] = "?"
                    it[optionsJson] = "[]"
                    it[status] = "OPEN"
                    it[createdAt] = 0
                }
                Continuations.insert {
                    it[watchedProcessId] = 1
                    it[status] = "PENDING"
                    it[agentName] = "a"
                    it[resumeParam] = "R"
                    it[params] = "{}"
                    it[originProcessId] = 1
                    it[originAgentName] = "a"
                    it[createdAt] = 0
                    it[fireAttempts] = Int.MAX_VALUE
                }
            }
            transaction(svc.database) {
                val processes = Processes.selectAll().orderBy(Processes.id, SortOrder.ASC).toList()
                assertEquals(
                    listOf(listOf(Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE), listOf(null, null, null)),
                    processes.map { r ->
                        listOf(r[Processes.parentProcessId], r[Processes.rootProcessId], r[Processes.failureReasonRunId])
                    },
                )
                assertEquals(
                    listOf(1, 0),
                    Questions.selectAll().orderBy(Questions.id, SortOrder.ASC).map { it[Questions.dispatchBound] },
                )
                assertEquals(Int.MAX_VALUE, Continuations.selectAll().single()[Continuations.fireAttempts])
            }
        } finally {
            svc.close()
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { st ->
                assertEquals(
                    listOf("integer:2147483647 integer:-2147483648 integer:2147483647", "null:null null:null null:null"),
                    rawValues(
                        st,
                        "SELECT typeof(parent_process_id) || ':' || ifnull(parent_process_id, 'null') || ' ' || " +
                            "typeof(root_process_id) || ':' || ifnull(root_process_id, 'null') || ' ' || " +
                            "typeof(failure_reason_run_id) || ':' || ifnull(failure_reason_run_id, 'null') " +
                            "FROM processes ORDER BY id",
                    ),
                )
                assertEquals(
                    listOf("integer:1", "integer:0"),
                    rawValues(st, "SELECT typeof(dispatch_bound) || ':' || dispatch_bound FROM questions ORDER BY id"),
                )
                assertEquals(
                    listOf("integer:2147483647"),
                    rawValues(st, "SELECT typeof(fire_attempts) || ':' || fire_attempts FROM continuations"),
                )
            }
        }
    }

    private fun freshDatabase(dir: Path): Path {
        val path = dir.resolve("fresh.db")
        DatabaseService(path.toString(), logger).also {
            it.migrate()
            it.close()
        }
        return path
    }

    private fun tableSql(
        st: Statement,
        table: String,
    ): String = rawValues(st, "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '$table'").single()

    private fun columnInfo(
        st: Statement,
        table: String,
        column: String,
    ): String =
        st.executeQuery("PRAGMA table_info($table)").use { rs ->
            generateSequence { if (rs.next()) rs else null }
                .first { it.getString("name") == column }
                .let { "type=${it.getString("type")} notnull=${it.getInt("notnull")} default=${it.getString("dflt_value")}" }
        }

    private fun rawValues(
        st: Statement,
        sql: String,
    ): List<String> = st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }

    private fun checkOn(column: String) = Regex("check\\s*\\(\\s*\"?$column\"?\\s", RegexOption.IGNORE_CASE)

    private companion object {
        /** Each column with its declaration on a fresh database, as `integer()` gave it less the CHECK. */
        val MIGRATION_ADDED: List<Pair<Column<*>, String>> =
            listOf(
                Questions.dispatchBound to "type=INT notnull=1 default=0",
                Continuations.fireAttempts to "type=INT notnull=1 default=0",
                Processes.parentProcessId to "type=INT notnull=0 default=null",
                Processes.rootProcessId to "type=INT notnull=0 default=null",
                Processes.failureReasonRunId to "type=INT notnull=0 default=null",
            )
    }
}
