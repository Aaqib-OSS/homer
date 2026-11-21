package homer.app.runners

import homer.app.models.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** A step's env rendered as the file [RemoteShell.envFileContent] describes, at [path] under a random name. */
class RemoteEnvFile private constructor(
    val path: String,
    val content: ByteArray,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RemoteEnvFile::class.java)

        /** Bound on one env-file removal, one small round trip; an ssh session stalling it is closed (ADR-0064). */
        val REMOVE_TIMEOUT = 30.seconds

        /** [env] rendered for a file in [dir]; fails on a name the shell cannot export or a value holding a NUL byte. */
        fun render(
            dir: String,
            env: Map<String, String>,
        ): RemoteEnvFile {
            val path = "$dir/${UUID.randomUUID()}.env"
            return RemoteEnvFile(path, RemoteShell.envFileContent(path, env).toByteArray(Charsets.UTF_8))
        }

        /**
         * Runs [block], then [remove]s [envFile] when the step failed or was stopped, since the command may never have
         * sourced it. The removal is non-cancellable; a failed one is logged by path and never replaces the outcome.
         */
        suspend fun removingUnlessSucceeded(
            envFile: String?,
            remove: suspend (String) -> Unit,
            block: suspend () -> CommandResult,
        ): CommandResult {
            var succeeded = false
            try {
                return block().also { succeeded = it.exitCode == 0 }
            } finally {
                if (!succeeded && envFile != null) withContext(NonCancellable) { removeLogged(envFile, remove) }
            }
        }

        private suspend fun removeLogged(
            envFile: String,
            remove: suspend (String) -> Unit,
        ) {
            try {
                remove(envFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Could not remove env file {} ({}); it stays until its workspace or pod is removed",
                    envFile,
                    e.javaClass.simpleName,
                )
            }
        }
    }
}
