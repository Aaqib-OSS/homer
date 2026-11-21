package homer.app.routes

import io.ktor.server.application.ApplicationCall

/** Page size a list endpoint returns when the caller names no `limit`. */
internal const val DEFAULT_PAGE_LIMIT = 100

/** Largest page size a caller may ask for — the page-size bound of every list endpoint (§12 rule 4). */
internal const val MAX_PAGE_LIMIT = 1000

/**
 * The `limit` (default [DEFAULT_PAGE_LIMIT], 1..[MAX_PAGE_LIMIT]) and `offset` (default 0, >= 0) query parameters of
 * every paginated list, validated in that order; a missing or non-numeric value falls back to the default.
 * [invalid] builds the calling endpoint's error, so each list family keeps its own exception type and message.
 */
internal fun ApplicationCall.pageParams(invalid: (field: String, message: String) -> Throwable): Pair<Int, Int> {
    val limit = request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_LIMIT
    val offset = request.queryParameters["offset"]?.toIntOrNull() ?: 0
    if (limit < 1 || limit > MAX_PAGE_LIMIT) throw invalid("limit", "Must be between 1 and $MAX_PAGE_LIMIT")
    if (offset < 0) throw invalid("offset", "Must be >= 0")
    return limit to offset
}
