package homer.app.infra

import at.favre.lib.crypto.bcrypt.BCrypt
import java.util.concurrent.ConcurrentHashMap

/**
 * `verify` must always run a bcrypt comparison, even for unknown users: callers pass
 * [dummyHashAtCost], at the configured `bcryptCost`, so login latency reveals nothing. See ADR-0011.
 */
class PasswordVerifier {
    private val dummyHashCache = ConcurrentHashMap<Int, String>()

    fun verify(
        plain: String,
        hash: String,
    ): Boolean = BCrypt.verifyer().verify(plain.toCharArray(), hash.toCharArray()).verified

    fun hash(
        plain: String,
        cost: Int,
    ): String = BCrypt.withDefaults().hashToString(cost, plain.toCharArray())

    /**
     * Bcrypt hash of [DUMMY_PLAINTEXT] at [cost], memoized per cost; the login handler passes it to
     * [verify] for an unknown user. Call once at bootstrap to pre-warm it.
     */
    fun dummyHashAtCost(cost: Int): String = dummyHashCache.computeIfAbsent(cost) { hash(DUMMY_PLAINTEXT, it) }

    companion object {
        const val DUMMY_PLAINTEXT: String = "homer-dummy-password"
    }
}
