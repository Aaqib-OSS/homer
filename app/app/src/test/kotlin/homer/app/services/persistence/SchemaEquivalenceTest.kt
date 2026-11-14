package homer.app.services.persistence

import homer.app.fixtures.repoRoot
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement
import kotlin.test.assertEquals

/**
 * A fresh database is built from `Tables.kt`; a production one by the steps in `SchemaMigrations.kt`.
 * Tests and the `query-plans` check only ever see the fresh form, so this compares the two end
 * states, fact by fact: every column, index and CHECK constraint, and every other `sqlite_master` row.
 */
class SchemaEquivalenceTest {
    private val logger = LoggerFactory.getLogger(SchemaEquivalenceTest::class.java)

    @Test
    fun `an upgraded database has the schema a fresh one has`(
        @TempDir dir: Path,
    ) {
        val fresh = migratedSchema(dir.resolve("fresh.db"))
        val found =
            SEEDS
                .flatMap { (version, tables) -> differences(fresh, migratedSchema(dir.resolve("v$version.db"), version, tables)) }
                .toSortedSet()
        val known = KNOWN_DIFFERENCES.toSortedSet()
        assertEquals(
            known,
            found,
            "unlisted differences: ${found - known}\nlisted differences that no longer occur (delete them): ${known - found}",
        )
    }

    @Test
    fun `the schema-version table in the persistence guide ends at SCHEMA_VERSION`() {
        val guide = repoRoot.resolve("app/app/src/main/kotlin/homer/app/services/persistence/CLAUDE.md").readText()
        val rows = Regex("^\\| (\\d+) \\|", RegexOption.MULTILINE).findAll(guide).map { it.groupValues[1].toInt() }.toList()
        assertEquals(rows.distinct(), rows, "a version has more than one row")
        assertEquals(rows.sorted(), rows, "version rows are out of order")
        assertEquals(DatabaseService.SCHEMA_VERSION, rows.last())
    }

    /** Creates [tables] in [path] stamped [version] (none: an empty file), runs `migrate()`, and reads the schema back. */
    private fun migratedSchema(
        path: Path,
        version: Int = 0,
        tables: List<Table> = emptyList(),
    ): Map<String, String> {
        if (tables.isNotEmpty()) {
            DatabaseService(path.toString(), logger).also { svc ->
                transaction(svc.database) { SchemaUtils.create(*tables.toTypedArray()) }
                svc.close()
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                c.createStatement().use { it.execute("PRAGMA user_version = $version") }
            }
        }
        DatabaseService(path.toString(), logger).also {
            it.migrate()
            it.close()
        }
        return DriverManager.getConnection("jdbc:sqlite:$path").use { c -> c.createStatement().use(::schemaFacts) }
    }

    /** Every fact whose value differs between [fresh] and [upgraded], or that only one of them has. */
    private fun differences(
        fresh: Map<String, String>,
        upgraded: Map<String, String>,
    ): List<String> =
        (fresh.keys + upgraded.keys)
            .filter { fresh[it] != upgraded[it] }
            .map { "$it: fresh ${fresh[it] ?: "absent"}, upgraded ${upgraded[it] ?: "absent"}" }

    private fun schemaFacts(st: Statement): Map<String, String> =
        rows(st, "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") {
            listOf(getString(1), getString(2), getString(3), getString(4).orEmpty())
        }.flatMap { (type, name, table, sql) ->
            when (type) {
                "table" -> tableFacts(st, name, sql)

                "index" -> emptyList()

                // read through its table's index_list, which also has the autoindexes
                else -> listOf("$type $name on $table" to normalize(sql))
            }
        }.toMap()

    // Columns are keyed by name, not position: ADD COLUMN appends, so an upgraded table's column
    // order is its history, and every statement names its columns.
    private fun tableFacts(
        st: Statement,
        table: String,
        sql: String,
    ): List<Pair<String, String>> =
        listOf("table $table" to "autoincrement=${"autoincrement" in sql.lowercase()}") +
            checks(sql).map { "table $table check $it" to "present" } +
            rows(st, "PRAGMA table_info($table)") {
                "column $table.${getString("name")}" to
                    "affinity=${affinity(getString("type"))} notnull=${getInt("notnull")} " +
                    "default=${getString("dflt_value")} pk=${getInt("pk")}"
            } +
            rows(st, "PRAGMA index_list($table)") { Triple(getString("name"), getInt("unique"), getInt("partial")) }
                .map { (index, unique, partial) ->
                    val columns = rows(st, "PRAGMA index_info($index)") { getString("name") }.joinToString(", ")
                    "index $index on $table" to "($columns) unique=$unique partial=$partial where=${whereClause(st, index)}"
                }

    private fun whereClause(
        st: Statement,
        index: String,
    ): String {
        val sql = rows(st, "SELECT sql FROM sqlite_master WHERE name = '$index'") { getString(1).orEmpty() }.singleOrNull()
        return normalize(sql.orEmpty().split(Regex("\\bwhere\\b", RegexOption.IGNORE_CASE)).getOrElse(1) { "" })
    }

    /** The body of each CHECK constraint in a table's DDL, normalized; constraint names are cosmetic. */
    private fun checks(sql: String): List<String> =
        Regex("\\bcheck\\s*\\(", RegexOption.IGNORE_CASE)
            .findAll(sql)
            .map { match ->
                val start = match.range.last + 1
                var depth = 1
                var end = start
                while (depth > 0) {
                    if (sql[end] == '(') {
                        depth++
                    } else if (sql[end] == ')') {
                        depth--
                    }
                    end++
                }
                normalize(sql.substring(start, end - 1))
            }.toList()

    /**
     * SQLite stores by a column's type affinity, not its declared name (BIGINT, INT and INTEGER
     * behave the same; VARCHAR's length is not enforced), so the affinity is what is compared.
     * The rules of https://www.sqlite.org/datatype3.html#determination_of_column_affinity.
     */
    private fun affinity(declared: String): String =
        declared.uppercase().let { type ->
            when {
                "INT" in type -> "INTEGER"
                listOf("CHAR", "CLOB", "TEXT").any { it in type } -> "TEXT"
                "BLOB" in type || type.isEmpty() -> "BLOB"
                listOf("REAL", "FLOA", "DOUB").any { it in type } -> "REAL"
                else -> "NUMERIC"
            }
        }

    /** Lowercased, unquoted, without table qualifiers, with whitespace collapsed and none next to `(`, `)` or `,`. */
    private fun normalize(sql: String): String =
        sql
            .lowercase()
            .replace("\"", "")
            .replace(Regex("\\b[a-z_]+\\."), "")
            .replace(Regex("\\s+"), " ")
            .replace(Regex(" ?([(),]) ?"), "$1")
            .trim()

    private fun <T : Any> rows(
        st: Statement,
        sql: String,
        read: ResultSet.() -> T,
    ): List<T> = st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.read() else null }.toList() }

    private companion object {
        /**
         * Each difference an upgraded database still has, exactly as the test reports it. Exact in
         * both directions: a fix makes its entry fail until it is deleted.
         */
        val KNOWN_DIFFERENCES: Set<String> =
            setOf(
                // #232: v18 added retained_truncated as INTEGER; Tables.kt's bool() declares BOOLEAN (NUMERIC affinity).
                "column processes.retained_truncated: fresh affinity=NUMERIC notnull=0 default=null pk=0, " +
                    "upgraded affinity=INTEGER notnull=0 default=null pk=0",
                // #231: v7 added consumer_agent with DEFAULT ''; Tables.kt declares no default.
                "column result_tokens.consumer_agent: fresh affinity=TEXT notnull=1 default=null pk=0, " +
                    "upgraded affinity=TEXT notnull=1 default='' pk=0",
                // #233: v10 added fire_attempts without the int32 range CHECK Exposed declares for integer().
                "table continuations check fire_attempts between -2147483648 and 2147483647: fresh present, upgraded absent",
                // #233: v30 added failure_reason_run_id without the int32 range CHECK Exposed declares for integer().
                "table processes check failure_reason_run_id between -2147483648 and 2147483647: fresh present, upgraded absent",
                // #233: v19 added parent_process_id without the int32 range CHECK Exposed declares for integer().
                "table processes check parent_process_id between -2147483648 and 2147483647: fresh present, upgraded absent",
                // #233: v22 added root_process_id without the int32 range CHECK Exposed declares for integer().
                "table processes check root_process_id between -2147483648 and 2147483647: fresh present, upgraded absent",
                // #233: v2 added dispatch_bound without the int32 range CHECK Exposed declares for integer().
                "table questions check dispatch_bound between -2147483648 and 2147483647: fresh present, upgraded absent",
            )

        /**
         * The databases upgraded: v1, the oldest version `migrate()` upgrades from, and v4, the
         * first with every table a later step ALTERs (the v1 seed covers `processes` and
         * `questions`). Each table as `Tables.kt` declared it at that version; a
         * table a seed omits is created by `SchemaUtils.create` from today's model.
         */
        val SEEDS: List<Pair<Int, List<Table>>> =
            listOf(
                1 to listOf(V1Processes, V1Questions),
                4 to listOf(V4Dispatches, V4ResultTokens, V4Continuations),
            )
    }
}

/** `processes` as `Tables.kt` declared it at schema v1; keep it agreeing with the raw-SQL v1 seed in `DatabaseMigrationTest`. */
private object V1Processes : Table("processes") {
    val id = integer("id").autoIncrement()
    val status = varchar("status", 16)
    val agentName = varchar("agent_name", 255)
    val currentCommand = text("current_command").nullable()
    val currentCommandStart = long("current_command_start").nullable()
    val purgeAt = long("purge_at").nullable()
    val costUsd = double("cost_usd").default(0.0)
    val artifactsDir = text("artifacts_dir").nullable()
    val inputParams = text("input_params")
    val history = text("history")
    val executions = text("executions")
    val runnerState = text("runner_state").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** `questions` as `Tables.kt` declared it at schema v1; keep it agreeing with the raw-SQL v1 seed in `DatabaseMigrationTest`. */
private object V1Questions : Table("questions") {
    val id = varchar("id", 64)
    val processId = integer("process_id")
    val agentName = varchar("agent_name", 255)
    val text = text("text")
    val optionsJson = text("options")
    val status = varchar("status", 16)
    val answer = text("answer").nullable()
    val createdAt = long("created_at")
    val answeredAt = long("answered_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** `dispatches` as `Tables.kt` declared it at schema v4. */
private object V4Dispatches : Table("dispatches") {
    val id = integer("id").autoIncrement()
    val questionId = varchar("question_id", 64).uniqueIndex()
    val status = varchar("status", 16)
    val agentName = varchar("agent_name", 255)
    val answerParam = varchar("answer_param", 255)
    val params = text("params")
    val originProcessId = integer("origin_process_id")
    val originAgentName = varchar("origin_agent_name", 255)
    val expiresAt = long("expires_at").nullable()
    val createdAt = long("created_at")
    val dispatchedAt = long("dispatched_at").nullable()
    val dispatchedProcessId = integer("dispatched_process_id").nullable()
    val error = text("error").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** `result_tokens` as `Tables.kt` declared it at schema v4. */
private object V4ResultTokens : Table("result_tokens") {
    val token = varchar("token", 64)
    val watchedProcessId = integer("watched_process_id")
    val path = text("path")
    val resultTransformer = text("result_transformer").nullable()
    val sizeBytes = long("size_bytes")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at").nullable()

    override val primaryKey = PrimaryKey(token)
}

/** `continuations` as `Tables.kt` declared it at schema v4. */
private object V4Continuations : Table("continuations") {
    val id = integer("id").autoIncrement()
    val watchedProcessId = integer("watched_process_id")
    val status = varchar("status", 16)
    val agentName = varchar("agent_name", 255)
    val resumeParam = varchar("resume_param", 255)
    val params = text("params")
    val resultTransformer = text("result_transformer").nullable()
    val originProcessId = integer("origin_process_id")
    val originAgentName = varchar("origin_agent_name", 255)
    val expiresAt = long("expires_at").nullable()
    val createdAt = long("created_at")
    val firedAt = long("fired_at").nullable()
    val firedProcessId = integer("fired_process_id").nullable()
    val error = text("error").nullable()

    override val primaryKey = PrimaryKey(id)
}
