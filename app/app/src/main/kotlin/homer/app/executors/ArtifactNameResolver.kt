package homer.app.executors

import homer.app.EnvConstants
import homer.app.exceptions.CommandExecutionException
import homer.app.infra.SecretRedactor
import homer.app.infra.TextCompiler
import java.nio.file.Path

/**
 * Renders an agent's `artifactNameTemplate` into per-attempt artifact paths, validated fail-closed at the point of use
 * (ADR-0047 rule 2, CWE-22); errors name the template, never the stem. [secretEnvNames] stay out of the render, and a stem
 * [secretRedactor] would alter is refused: build it with `SecretRedactor.ARTIFACT_STEM_MIN_NEEDLE_LENGTH`.
 */
class ArtifactNameResolver(
    private val template: String,
    private val textCompiler: TextCompiler,
    private val artifactsDir: Path,
    private val cmdIndex: Int,
    private val envProvider: () -> Map<String, String>,
    private val secretEnvNames: Set<String> = emptySet(),
    private val secretRedactor: SecretRedactor = SecretRedactor.NONE,
) {
    fun stdOut(attempt: Int): Path = resolve(attempt, "out")

    fun stdErr(attempt: Int): Path = resolve(attempt, "err")

    private fun resolve(
        attempt: Int,
        suffix: String,
    ): Path {
        val stem = render(attempt)
        validate(stem)
        return artifactsDir.resolve("$stem.$suffix")
    }

    private fun render(attempt: Int): String =
        textCompiler.replaceArguments(
            template,
            envProvider().filterKeys { it !in secretEnvNames } +
                mapOf(
                    EnvConstants.CMD_INDEX to cmdIndex.toString(),
                    EnvConstants.CMD_ATTEMPT to attempt.toString(),
                ),
        )

    private fun validate(stem: String) {
        val normalizedRoot = artifactsDir.normalize()
        val syntaxValid =
            SAFE_STEM.matches(stem) &&
                stem.length <= MAX_STEM_LENGTH &&
                stem != "." &&
                stem != ".." &&
                artifactsDir.resolve("$stem.out").normalize().parent == normalizedRoot
        if (!syntaxValid) {
            throw CommandExecutionException(
                message = "artifactNameTemplate '$template' rendered an invalid artifact file name",
                exitCode = -1,
                command = template,
            )
        }
        // A derived secret (a captured value copied into a non-secret var) escapes [secretEnvNames]:
        // refuse any stem the redactor would alter, with a message that names no value (CWE-200).
        if (secretRedactor.redact(stem) != stem) {
            throw CommandExecutionException(
                message = "artifactNameTemplate '$template' rendered a name matching a secret value",
                exitCode = -1,
                command = template,
            )
        }
    }

    private companion object {
        val SAFE_STEM = Regex("^[A-Za-z0-9._-]+$")
        const val MAX_STEM_LENGTH = 200
    }
}
