package homer.app.infra

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for coroutine code: rethrows [CancellationException], captures any other [Exception].
 * Not a drop-in for `runCatching`: an `Error` propagates, and cleanup that must run when cancelled
 * (or must survive an `Error`) stays a hand-written catch — see ADR-0082.
 */
inline fun <T> coRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
