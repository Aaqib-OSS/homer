package homer.app.infra

import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded, in-memory set of recently seen HMAC signatures, each live for its freshness window only.
 * See ADR-0010.
 */
class HmacReplayCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    // key -> expiry epoch millis; only validly signed requests reach here (ADR-0010).
    private val seen = ConcurrentHashMap<String, Long>()

    /** Records [key] for [ttlMillis]; `false` for a replay of a live key, or when the full cache cannot take it. */
    fun firstUse(
        key: String,
        ttlMillis: Long,
    ): Boolean {
        val now = clock()
        val newExpiry = now + ttlMillis
        val existing = seen.putIfAbsent(key, newExpiry)
        if (existing == null) {
            // Over the cap, reclaim only expired entries: evicting a live marker re-opens its replay
            // window (ADR-0010). Fail closed when none can be reclaimed.
            if (seen.size > maxEntries) {
                seen.entries.removeIf { it.value <= now }
                if (seen.size > maxEntries) {
                    seen.remove(key, newExpiry)
                    return false
                }
            }
            return true
        }
        if (existing <= now) {
            seen[key] = newExpiry
            return true
        }
        return false
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000
    }
}
