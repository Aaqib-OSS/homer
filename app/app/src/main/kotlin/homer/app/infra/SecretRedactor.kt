package homer.app.infra

import homer.app.models.MIN_SCREENED_SECRET_LENGTH
import kotlinx.serialization.json.JsonPrimitive

/**
 * Masks known secret values in free text before it reaches telemetry (Langfuse spans) or logs. Built from the env
 * restricted to `secretEnvNames`; `@SYSENV:` declares a secret, a literal `globalEnv` value does not, and
 * [SysEnvResolver.secretNamesFor] adds referenced host names. See SERVER.md's content-egress note and ADR-0047.
 */
class SecretRedactor private constructor(
    private val secrets: List<String>,
) {
    /** Replaces every known secret value in [text] with `***`. Returns [text] unchanged when there are none. */
    fun redact(text: String): String = secrets.fold(text) { acc, secret -> acc.replace(secret, REDACTED) }

    /** Union of two redactors' needles — for callers composing two independently-built needle sources. */
    operator fun plus(other: SecretRedactor): SecretRedactor = SecretRedactor(secrets + other.secrets)

    companion object {
        const val REDACTED = "***"
        private const val MAX_ESCAPE_LEVELS = 2

        // Floor for ArtifactNameResolver's stem screen: a short secret matches inside nearly any stem and would fail
        // every command; telemetry and tail redaction ignore it (ADR-0047).
        const val ARTIFACT_STEM_MIN_NEEDLE_LENGTH = MIN_SCREENED_SECRET_LENGTH

        /** No-op redactor (nothing flagged secret). */
        val NONE = SecretRedactor(emptyList())

        fun from(
            env: Map<String, String>,
            secretEnvNames: Set<String>,
            hostEnv: (String) -> String? = System::getenv,
            minNeedleLength: Int = 1,
        ): SecretRedactor {
            // Env first, host second: a workspace key shadows a same-named host
            // variable, and the workspace value is the one the child can echo.
            val values = secretEnvNames.mapNotNull { env[it] ?: hostEnv(it) }.filter { it.length >= minNeedleLength }
            if (values.isEmpty()) return NONE
            // Callers redact JSON-encoded text, where a secret with \n, " or \ appears escaped: match those forms
            // too, at two levels (attribute encoding, and a JSON document carrying it as a string) (ADR-0047).
            val needles =
                values.flatMap { secret ->
                    generateSequence(secret) { prev ->
                        JsonPrimitive(prev).toString().removeSurrounding("\"").takeIf { it != prev }
                    }.take(MAX_ESCAPE_LEVELS + 1).toList()
                }
            return SecretRedactor(needles)
        }
    }
}
