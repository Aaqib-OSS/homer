package homer.app.routes

import homer.app.exceptions.QueryParamValidationException
import homer.app.exceptions.ValidationException
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Unit tests for the paging helper both list families parse through: the defaults, the
 * 1..1000 / >= 0 bounds, and the exact error each family's own factory builds from it.
 */
class PageParamsTest {
    /** The question / dispatch / continuation family's factory. */
    private val listError: (String, String) -> Throwable = { field, message -> ValidationException(message, field) }

    /** The `/status/all` factory, which prefixes the field itself. */
    private val statusAllError: (String, String) -> Throwable = { field, message ->
        QueryParamValidationException("Invalid $field parameter. $message")
    }

    private fun call(vararg params: Pair<String, String>): ApplicationCall {
        val request = mockk<ApplicationRequest>()
        every { request.queryParameters } returns Parameters.build { params.forEach { (name, value) -> append(name, value) } }
        val call = mockk<ApplicationCall>()
        every { call.request } returns request
        return call
    }

    @Test
    fun `absent params default to a 100 limit and a 0 offset`() {
        assertEquals(DEFAULT_PAGE_LIMIT, 100)
        assertEquals(MAX_PAGE_LIMIT, 1000)
        assertEquals(100 to 0, call().pageParams(listError))
    }

    @Test
    fun `both bounds of the limit are accepted`() {
        assertEquals(1 to 0, call("limit" to "1").pageParams(listError))
        assertEquals(1000 to 0, call("limit" to "1000").pageParams(listError))
        assertEquals(100 to 7, call("offset" to "7").pageParams(listError))
    }

    @Test
    fun `a non-numeric limit or offset falls back to its default`() {
        assertEquals(100 to 0, call("limit" to "abc", "offset" to "1.5").pageParams(listError))
    }

    @Test
    fun `a limit outside 1 to 1000 is rejected through the supplied factory`() {
        for (bad in listOf("0", "-1", "1001")) {
            val ex = assertThrows<ValidationException>(bad) { call("limit" to bad).pageParams(listError) }
            assertEquals("Validation error for 'limit': Must be between 1 and 1000", ex.message, bad)
        }
    }

    @Test
    fun `a negative offset is rejected through the supplied factory`() {
        val ex = assertThrows<ValidationException> { call("offset" to "-1").pageParams(listError) }
        assertEquals("Validation error for 'offset': Must be >= 0", ex.message)
    }

    @Test
    fun `the status all factory keeps the QueryParamValidationException wording`() {
        val limit = assertThrows<QueryParamValidationException> { call("limit" to "1001").pageParams(statusAllError) }
        assertEquals("Invalid limit parameter. Must be between 1 and 1000", limit.message)

        val offset = assertThrows<QueryParamValidationException> { call("offset" to "-1").pageParams(statusAllError) }
        assertEquals("Invalid offset parameter. Must be >= 0", offset.message)
    }

    @Test
    fun `the limit is checked before the offset`() {
        val ex = assertThrows<ValidationException> { call("limit" to "0", "offset" to "-1").pageParams(listError) }
        assertEquals("Validation error for 'limit': Must be between 1 and 1000", ex.message)
    }
}
