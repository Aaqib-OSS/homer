package homer.app.infra

import kotlin.coroutines.cancellation.CancellationException

/** [runCatching] for coroutine code: rethrows [CancellationException], captures any other [Exception]. */
inline fun <T> coRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
