package homer.app.services.persistence

import homer.app.models.ResultEnvParam
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * A capability token for reading [path] from one watched process's artifacts dir through [resultTransformer],
 * reusable until [expiresAt] and independent of the continuation that minted it. It is redeemed only
 * for a caller running [consumerAgent], so a leaked token is useless to any other agent. See ADR-0025.
 */
data class ResultTokenRecord(
    val token: String,
    val watchedProcessId: Int,
    val path: String,
    val resultTransformer: ResultEnvParam,
    val sizeBytes: Long,
    val createdAt: Long,
    val expiresAt: Long?,
    val consumerAgent: String,
)

/**
 * Durable access to capability-token records. SQLite is authoritative (ADR-0020).
 */
class ResultTokenRepository(
    private val db: DatabaseService,
) {
    suspend fun create(record: ResultTokenRecord) {
        db.query {
            ResultTokens.insert {
                it[token] = record.token
                it[watchedProcessId] = record.watchedProcessId
                it[path] = record.path
                it[resultTransformer] = columnJson.encodeToString(ResultEnvParam.serializer(), record.resultTransformer)
                it[sizeBytes] = record.sizeBytes
                it[createdAt] = record.createdAt
                it[expiresAt] = record.expiresAt
                it[consumerAgent] = record.consumerAgent
            }
        }
    }

    suspend fun get(token: String): ResultTokenRecord? =
        db.read {
            ResultTokens
                .selectAll()
                .where { ResultTokens.token eq token }
                .singleOrNull()
                ?.toRecord()
        }

    /**
     * Deletes tokens whose TTL has lapsed at or before [now] and returns how many. Both the `read { }` pre-check
     * and the delete seek `result_tokens_expires_at`; a tick with nothing due takes no write lock.
     */
    suspend fun deleteExpired(now: Long): Int {
        val due =
            db.read {
                ResultTokens
                    .select(ResultTokens.token)
                    .where { (ResultTokens.expiresAt.isNotNull()) and (ResultTokens.expiresAt lessEq now) }
                    .limit(1)
                    .any()
            }
        if (!due) return 0
        return db.query {
            ResultTokens.deleteWhere {
                (ResultTokens.expiresAt.isNotNull()) and (ResultTokens.expiresAt lessEq now)
            }
        }
    }

    /** The row as a record; null for a row without a transformer, which no token can be redeemed through. */
    private fun ResultRow.toRecord(): ResultTokenRecord? {
        val transformer = this[ResultTokens.resultTransformer] ?: return null
        return ResultTokenRecord(
            token = this[ResultTokens.token],
            watchedProcessId = this[ResultTokens.watchedProcessId],
            path = this[ResultTokens.path],
            resultTransformer = columnJson.decodeFromString(ResultEnvParam.serializer(), transformer),
            sizeBytes = this[ResultTokens.sizeBytes],
            createdAt = this[ResultTokens.createdAt],
            expiresAt = this[ResultTokens.expiresAt],
            consumerAgent = this[ResultTokens.consumerAgent],
        )
    }
}
