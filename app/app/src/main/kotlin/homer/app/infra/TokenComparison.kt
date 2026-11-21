package homer.app.infra

import java.security.MessageDigest

/**
 * Constant-time comparison of an expected and provided bearer token: unlike `==`, [MessageDigest.isEqual] runs in
 * time independent of input contents, so the time to reject a token does not leak the length of its matching
 * prefix (CWE-208).
 */
internal fun matchesTokenConstantTime(
    expected: String,
    provided: String,
): Boolean =
    MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        provided.toByteArray(Charsets.UTF_8),
    )
