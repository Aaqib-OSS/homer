package homer.app.infra

import homer.app.models.HmacEncoding
import homer.app.models.HmacKeyConfig
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Verifies inbound HMAC body signatures against the configured keys; the first key whose MAC matches
 * wins. With a [replayCache] and a `timestampHeader`, a signature already seen in its window is a replay (ADR-0010).
 */
class HmacVerifier(
    private val clock: () -> Long = System::currentTimeMillis,
    private val replayCache: HmacReplayCache? = null,
) {
    sealed class Result {
        data class Authenticated(
            val keyId: String,
        ) : Result()

        data class Rejected(
            val reason: String,
        ) : Result()
    }

    fun verify(
        keys: Map<String, HmacKeyConfig>,
        method: String,
        path: String,
        // Raw query string without the leading '?', bound only when a template uses {{query}}.
        query: String = "",
        headers: (String) -> String?,
        rawBody: ByteArray,
    ): Result {
        for ((keyId, cfg) in keys) {
            val provided = headers(cfg.signatureHeader) ?: continue
            val unprefixed =
                when {
                    cfg.prefix.isEmpty() -> provided
                    provided.startsWith(cfg.prefix) -> provided.substring(cfg.prefix.length)
                    else -> continue
                }
            val expectedBytes = computeMac(cfg, method, path, query, headers, rawBody) ?: continue
            val providedBytes =
                runCatching { decode(unprefixed, cfg.encoding) }.getOrNull() ?: continue
            if (MessageDigest.isEqual(expectedBytes, providedBytes)) return acceptUnlessReplayed(keyId, cfg, expectedBytes)
        }
        return Result.Rejected("hmac: no matching key")
    }

    /** The result for a signature that matched [keyId]'s MAC [macBytes]: authenticated, unless it was already used. */
    private fun acceptUnlessReplayed(
        keyId: String,
        cfg: HmacKeyConfig,
        macBytes: ByteArray,
    ): Result {
        // Only meaningful with a freshness window; a signature is valid within ±tolerance, so keep it 2×tolerance.
        if (replayCache != null && cfg.timestampHeader != null) {
            val ttlMillis = cfg.replayToleranceSec * 2 * 1000
            // Keyed on the decoded MAC bytes: a re-encoded copy of the signature must not evade dedup.
            val canonicalSig = HexFormat.of().formatHex(macBytes)
            if (!replayCache.firstUse("$keyId:$canonicalSig", ttlMillis)) {
                return Result.Rejected("hmac: signature replayed within the freshness window")
            }
        }
        return Result.Authenticated(keyId)
    }

    private fun computeMac(
        cfg: HmacKeyConfig,
        method: String,
        path: String,
        query: String,
        headers: (String) -> String?,
        body: ByteArray,
    ): ByteArray? {
        val tsRaw = cfg.timestampHeader?.let { headers(it) }
        if (cfg.timestampHeader != null) {
            val tsMillis = parseTimestampMillis(tsRaw) ?: return null
            if (abs(clock() - tsMillis) / 1000 > cfg.replayToleranceSec) return null
        }
        val payload =
            cfg.payloadTemplate
                .replace("{{body}}", body.toString(Charsets.UTF_8))
                .replace("{{method}}", method.uppercase())
                .replace("{{path}}", path)
                .replace("{{query}}", query)
                .replace("{{timestamp}}", tsRaw ?: "")
        val mac =
            Mac.getInstance(cfg.algorithm.javaName).apply {
                init(SecretKeySpec(cfg.secret.toByteArray(Charsets.UTF_8), cfg.algorithm.javaName))
            }
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
    }

    private fun parseTimestampMillis(raw: String?): Long? {
        val n = raw?.toLongOrNull() ?: return null
        return if (n > 1_000_000_000_000L) n else n * 1000
    }

    private fun decode(
        s: String,
        enc: HmacEncoding,
    ): ByteArray =
        when (enc) {
            HmacEncoding.HEX -> {
                require(s.length % 2 == 0)
                require(s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
                ByteArray(s.length / 2) {
                    ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
                }
            }

            HmacEncoding.BASE64 -> {
                Base64.getDecoder().decode(s)
            }
        }
}
