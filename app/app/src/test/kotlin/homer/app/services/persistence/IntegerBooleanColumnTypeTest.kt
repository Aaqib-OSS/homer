package homer.app.services.persistence

import homer.app.fixtures.PersistenceTestSupport
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IntegerBooleanColumnTypeTest {
    @Test
    fun `maps values exactly as bool() does on SQLite and declares INTEGER`() {
        val db = PersistenceTestSupport.tempDatabase()
        val integer = IntegerBooleanColumnType()
        val boolean = BooleanColumnType()
        transaction(db.database) {
            assertEquals("INTEGER", integer.sqlType())
            for (value in listOf(true, false)) {
                assertEquals(boolean.notNullValueToDB(value), integer.notNullValueToDB(value))
                assertEquals(boolean.nonNullValueToString(value), integer.nonNullValueToString(value))
            }
            for (stored in listOf<Any>(1, 0, 1L, 0L, "1", "0", "true", "false")) {
                assertEquals(boolean.valueFromDB(stored), integer.valueFromDB(stored), "valueFromDB($stored)")
            }
        }
        db.close()
    }
}
