package homer.app.executors

import homer.app.EnvConstants
import homer.app.exceptions.CommandExecutionException
import homer.app.infra.SecretRedactor
import homer.app.infra.TextCompiler
import java.nio.file.Path

/**
 * Renders the per-command, per-attempt artifact names from an agent's `artifactNameTemplate`,
 * validated at the point of use — fail closed (root §12): the env is attacker-influenced and
 * the rendered stem becomes a server-local file name; the exception names the template, never
 * the stem (it can embed secrets). [secretEnvNames] is excluded from the render map and
 * [secretRedactor] rejects any stem it would alter; callers must raise its needle-length floor
 * (`SecretRedactor.ARTIFACT_STEM_MIN_NEEDLE_LENGTH`) — a shorter one matches nearly any stem and hard-fails every command.
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
        // Defense in depth for DERIVED secrets (e.g. a resultEnvParam-captured value
        // copied into a non-secret-classified var): reject any stem the run's
        // SecretRedactor would alter, even though it passed the syntax check above. A
        // distinct message helps operators diagnose this case without naming the value.
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
