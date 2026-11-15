package homer.app.routes

import homer.app.exceptions.ValidationException
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The property behind the paging helper: whatever a caller puts in `limit` / `offset`, the page it
 * gets back is inside the bound, and anything outside it is refused — the bound is local and
 * explicit, never bypassed by an input shape the example tests did not think of (§12 rule 4).
 */
class PageParamsPropertyTest {
    private val listError: (String, String) -> Throwable = { field, message -> ValidationException(message, field) }

    private fun call(
        rawLimit: String,
        rawOffset: String,
    ): ApplicationCall {
        val request = mockk<ApplicationRequest>()
        every { request.queryParameters } returns
            Parameters.build {
                append("limit", rawLimit)
                append("offset", rawOffset)
            }
        val call = mockk<ApplicationCall>()
        every { call.request } returns request
        return call
    }

    @Test
    fun `any accepted page keeps the limit within 1 to 1000 and the offset at or above 0`() =
        runTest {
            checkAll(Arb.string(0..12), Arb.string(0..12)) { rawLimit, rawOffset ->
                val outcome = runCatching { call(rawLimit, rawOffset).pageParams(listError) }
                val page = outcome.getOrNull()
                if (page == null) {
                    assertIs<ValidationException>(outcome.exceptionOrNull(), "limit='$rawLimit' offset='$rawOffset'")
                } else {
                    val (limit, offset) = page
                    assertTrue(limit in 1..MAX_PAGE_LIMIT, "limit=$limit from '$rawLimit'")
                    assertTrue(offset >= 0, "offset=$offset from '$rawOffset'")
                }
            }
        }

    @Test
    fun `a numeric limit outside the bound is never accepted`() =
        runTest {
            checkAll(Arb.int(), Arb.int()) { limit, offset ->
                val outcome = runCatching { call(limit.toString(), offset.toString()).pageParams(listError) }
                val accepted = outcome.getOrNull()
                if (limit in 1..MAX_PAGE_LIMIT && offset >= 0) {
                    assertTrue(accepted == (limit to offset), "limit=$limit offset=$offset")
                } else {
                    assertIs<ValidationException>(outcome.exceptionOrNull(), "limit=$limit offset=$offset")
                }
            }
        }
}
