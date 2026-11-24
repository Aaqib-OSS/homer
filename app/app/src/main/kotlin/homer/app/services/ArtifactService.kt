package homer.app.services

import homer.app.infra.ArtifactTooLargeException
import homer.app.infra.FileOperations
import homer.app.infra.PathSafety
import homer.app.models.ArtifactEntry
import homer.app.models.ArtifactListingResponse
import org.slf4j.Logger
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

class ArtifactService(
    private val processManager: ProcessManager,
    private val fileOperations: FileOperations,
    private val logger: Logger,
    private val maxArtifactBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES,
    private val maxListEntries: Int = MAX_ARTIFACT_LIST_ENTRIES,
) {
    /**
     * The size of [path], or an [ArtifactTooLargeException] over [maxArtifactBytes]; the artifact route
     * answers `413` with it before opening the stream (CWE-400, ADR-0047 rule 4).
     */
    fun checkArtifactSize(path: Path): Result<Long> {
        val size = fileOperations.fileSize(path)
        if (size > maxArtifactBytes) {
            return Result.failure(ArtifactTooLargeException(size, maxArtifactBytes))
        }
        return Result.success(size)
    }

    /**
     * Validates a relative path to prevent directory traversal attacks
     * Returns the normalized path if valid, or an error result
     */
    fun validateRelativePath(path: String): Result<String> {
        if (path.isBlank()) {
            return Result.failure(IllegalArgumentException("Path cannot be empty"))
        }

        // Reject absolute paths
        if (path.startsWith("/") || path.startsWith("\\")) {
            return Result.failure(IllegalArgumentException("Absolute paths are not allowed"))
        }

        // Reject paths containing ..
        if (path.contains("..")) {
            return Result.failure(IllegalArgumentException("Path traversal (..) is not allowed"))
        }

        // Normalize the path
        val normalized = Path.of(path).normalize().invariantSeparatorsPathString

        // After normalization, verify it still doesn't start with .. or /
        if (normalized.startsWith("..") || normalized.startsWith("/")) {
            return Result.failure(IllegalArgumentException("Path escapes the artifacts directory"))
        }

        return Result.success(normalized)
    }

    /**
     * Resolves an artifact path for a given process and relative path
     * Returns the full path if valid, or an error result
     */
    suspend fun resolveArtifactPath(
        processId: Int,
        relativePath: String,
    ): Result<Path> {
        val processInfo =
            processManager.getProcessInfo(processId)
                ?: return Result.failure(IllegalArgumentException("Process not found"))

        val artifactsDir =
            processInfo.artifactsDir
                ?: return Result.failure(IllegalArgumentException("Process has no artifacts directory"))
        // A run can swap its artifacts directory for a symlink, which assertContained follows (CWE-59).
        // Checked once here, so a swap before the file is opened is followed (AR-12).
        if (Files.isSymbolicLink(artifactsDir)) return Result.failure(IllegalArgumentException("Artifact not found"))

        val fullPath = artifactsDir.resolve(relativePath)
        val contained =
            try {
                PathSafety.assertContained(artifactsDir, fullPath)
            } catch (_: SecurityException) {
                // Surface as "not found" instead of "forbidden" — leaking
                // the distinction lets an attacker probe filesystem layout.
                return Result.failure(IllegalArgumentException("Artifact not found"))
            }
        // Refuse a non-regular or hard-linked file: a FIFO would pin the request thread, a hard link
        // may name another run's artifact. A missing path passes (CWE-59, ADR-0070).
        if (PathSafety.isLinkedOrSpecial(contained)) {
            return Result.failure(IllegalArgumentException("Artifact not found"))
        }
        return Result.success(contained)
    }

    /**
     * The regular files under the process's artifacts directory, relative, `/`-separated and sorted, capped
     * at [maxListEntries] with `truncated = true` (CWE-400). Fails for an unknown process and for a missing
     * or symlinked artifacts directory.
     */
    suspend fun listArtifacts(processId: Int): Result<ArtifactListingResponse> {
        val processInfo =
            processManager.getProcessInfo(processId)
                ?: return Result.failure(IllegalArgumentException("Process not found"))

        val artifactsDir =
            processInfo.artifactsDir
                ?: return Result.failure(IllegalArgumentException("Process has no artifacts directory"))

        if (Files.isSymbolicLink(artifactsDir) || !fileOperations.dirExists(artifactsDir)) {
            return Result.failure(IllegalArgumentException("Artifacts directory does not exist"))
        }

        // Request one past the cap so we can tell "exactly at the cap" from "more".
        val walked = fileOperations.listRegularFiles(artifactsDir, maxListEntries + 1)
        val truncated = walked.size > maxListEntries
        val entries =
            walked
                .take(maxListEntries)
                .map { file ->
                    ArtifactEntry(
                        path = artifactsDir.relativize(file).invariantSeparatorsPathString,
                        size = fileOperations.fileSize(file),
                        modified = fileOperations.lastModifiedEpochSecond(file),
                    )
                }.sortedBy { it.path }

        return Result.success(
            ArtifactListingResponse(
                processId = processId,
                artifactsDir = artifactsDir.toString(),
                files = entries,
                truncated = truncated,
            ),
        )
    }

    /**
     * Copies [artifactPath] into the stream [respond] opens, at most [maxArtifactBytes] bytes: an over-cap
     * file is refused with an [ArtifactTooLargeException] before the first write, and a file that grows is
     * cut off at the cap (CWE-770, ADR-0047 rule 4).
     */
    suspend fun streamArtifact(
        artifactPath: Path,
        respond: suspend (write: suspend (OutputStream) -> Unit) -> Unit,
    ) {
        streamCompleteFile(artifactPath, respond)
    }

    /**
     * Streams a complete file in chunks
     */
    private suspend fun streamCompleteFile(
        artifactPath: Path,
        respond: suspend (write: suspend (OutputStream) -> Unit) -> Unit,
    ) {
        logger.debug("Streaming complete file: {}", artifactPath)
        fileOperations.streamFile(artifactPath, maxArtifactBytes, respond)
    }

    companion object {
        const val DEFAULT_MAX_ARTIFACT_BYTES: Long = 100L * 1024 * 1024

        /** Hard cap on entries returned by [listArtifacts]; excess is dropped and flagged. */
        const val MAX_ARTIFACT_LIST_ENTRIES: Int = 10_000
    }
}
