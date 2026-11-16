package homer.app.infra

import at.favre.lib.crypto.bcrypt.BCrypt
import java.util.concurrent.ConcurrentHashMap

/**
 * `verify` must always perform a bcrypt comparison, even for unknown users: the
 * caller passes [dummyHashAtCost] so its wall-clock cost matches a real
 * wrong-password rejection, preventing account enumeration via login latency.
 * The dummy hash must use the configured `bcryptCost`, or a hardcoded cost
 * re-introduces the oracle when the operator deviates. See ADR-0011.
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
     * Bcrypt hash of [DUMMY_PLAINTEXT] at [cost], computed once per cost and
     * memoized. The login handler passes this to [verify] on the unknown-user
     * path so the rejection takes the same wall-clock time as a real
     * wrong-password rejection at the configured cost. Pre-warm by calling
     * once during server bootstrap so the first unknown-user login isn't
     * slower than subsequent ones.
     */
    fun dummyHashAtCost(cost: Int): String = dummyHashCache.computeIfAbsent(cost) { hash(DUMMY_PLAINTEXT, it) }

    companion object {
        const val DUMMY_PLAINTEXT: String = "homer-dummy-password"
    }
}
