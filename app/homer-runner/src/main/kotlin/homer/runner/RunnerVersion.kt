package homer.runner

import homer.app.models.RunnerTagValidation

/**
 * The version this runner was built from, as the build stamped it into the jar (see
 * `homer-runner/build.gradle.kts`) and never from run-time configuration. It must match
 * [RunnerTagValidation.PATTERN], which Homer checks at registration.
 */
object RunnerVersion {
    /** What a build without release information reports. */
    const val FALLBACK = "dev"

    private const val RESOURCE = "/homer/runner/runner-version.txt"

    /** The version the build stamped into this jar; [FALLBACK] when the resource is absent or unusable. */
    val current: String by lazy { parse(resourceText()) }

    /** [raw] trimmed when it is a safe release token, [FALLBACK] otherwise. */
    fun parse(raw: String?): String {
        val version = raw?.trim().orEmpty()
        return if (RunnerTagValidation.PATTERN.matches(version)) version else FALLBACK
    }

    private fun resourceText(): String? = RunnerVersion::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }
}
