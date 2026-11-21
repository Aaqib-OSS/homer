package homer.app.fixtures

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records every SQL statement Exposed executes, JVM-wide, registered through `META-INF/services`. Tests call [reset]
 * before the code under test, then inspect [statements] and [transactionCount] to assert which queries and how many
 * transactions ran.
 */
class SqlStatementRecorder : GlobalStatementInterceptor {
    override fun beforeExecution(
        transaction: Transaction,
        context: StatementContext,
    ) {
        recorded += transaction.transactionId to context.sql(transaction)
    }

    companion object {
        private val recorded = CopyOnWriteArrayList<Pair<String, String>>()

        fun reset() = recorded.clear()

        fun statements(): List<String> = recorded.map { it.second }

        /** Distinct transactions that executed at least one recorded statement. */
        fun transactionCount(): Int = recorded.map { it.first }.distinct().size
    }
}
