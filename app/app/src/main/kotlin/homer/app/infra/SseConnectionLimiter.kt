package homer.app.infra

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounds the concurrent SSE / long-poll streams one caller may hold open, so streams cannot exhaust server
 * resources. The caller key is the authenticated principal, or the client host when unsecured (ADR-0014).
 * In-memory and single-node, like the streams themselves.
 */
class SseConnectionLimiter(
    private val maxPerCaller: Int = DEFAULT_MAX_PER_CALLER,
) {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** Increments the caller's count if it is under the cap; returns false (no change) at the cap. */
    fun tryAcquire(key: String): Boolean {
        val counter = counts.computeIfAbsent(key) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= maxPerCaller) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    fun release(key: String) {
        val counter = counts[key] ?: return
        if (counter.decrementAndGet() <= 0) counts.remove(key, counter)
    }

    companion object {
        const val DEFAULT_MAX_PER_CALLER = 100
    }
}
