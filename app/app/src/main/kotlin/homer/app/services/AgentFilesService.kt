package homer.app.services

import homer.app.infra.AgentDirs
import org.slf4j.Logger
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * The file API over an agent's directory. Every path is contained by [AgentFilePathResolver], and
 * every mutation needs the directory writable on disk. See ADR-0004.
 */
open class AgentFilesService(
    private val agentsService: AgentsService,
    logger: Logger,
    private val maxConfigFileBytes: Long = DEFAULT_MAX_CONFIG_FILE_BYTES,
) {
    private val paths = AgentFilePathResolver(agentsService)
    private val definitionGate = AgentDefinitionWriteGate(logger, agentsService.sysEnvAllowlist)

    sealed class FileOpResult<out T> {
        data class Ok<T>(
            val value: T,
        ) : FileOpResult<T>()

        data class NotFound(
            val msg: String,
        ) : FileOpResult<Nothing>()

        data class Forbidden(
            val msg: String,
        ) : FileOpResult<Nothing>()

        data class BadRequest(
            val msg: String,
        ) : FileOpResult<Nothing>()

        data class Conflict(
            val msg: String,
        ) : FileOpResult<Nothing>()

        data class SchemaInvalid(
            val errors: List<String>,
        ) : FileOpResult<Nothing>()
    }

    data class FileEntry(
        val path: String,
        val size: Long,
        val mtime: Long,
        val isDirectory: Boolean,
    )

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun list(agentName: String): FileOpResult<List<FileEntry>> {
        val dir = agentsService.getAgentDir(agentName) ?: return FileOpResult.NotFound("Agent '$agentName' not found")
        if (!dir.exists() || !dir.isDirectory()) return FileOpResult.NotFound("Agent directory missing")
        val real = dir.toRealPath()
        // The listing stops at a nested agent's directory, so it never surfaces another agent's
        // files; checked per entry, so a dropped subtree is never walked. See ADR-0068.
        val entries =
            real
                .walk()
                .filter { it != real }
                .take(LIST_MAX_ENTRIES)
                .mapNotNull { p ->
                    val relative = p.relativeTo(real)
                    // Cap recursion depth — `relative.nameCount` is the number of segments
                    if (relative.nameCount > LIST_MAX_DEPTH) return@mapNotNull null
                    if (AgentDirs.isBelowNestedAgentDir(real, relative)) return@mapNotNull null
                    // A link to a nested agent's file stats as its own name: gate its target like
                    // resolveSafe does (CWE-59, ADR-0047 rule 3).
                    val realTarget =
                        runCatching { p.toRealPath() }.getOrNull()
                            ?: return@mapNotNull null
                    if (!isListableLinkTarget(real, p, realTarget)) return@mapNotNull null
                    val attrs =
                        try {
                            // From the checked realTarget, not by re-following p: a link re-pointed
                            // after the check above must not expose its new target's size or mtime.
                            Files.readAttributes(realTarget, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        } catch (_: NoSuchFileException) {
                            return@mapNotNull null
                        }
                    FileEntry(
                        path = relative.toString().replace('\\', '/'),
                        size = if (attrs.isDirectory) 0L else attrs.size(),
                        mtime = attrs.lastModifiedTime().toMillis() / 1000,
                        isDirectory = attrs.isDirectory,
                    )
                }.toList()
                .sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.path })
        return FileOpResult.Ok(entries)
    }

    /** Whether [p], resolving to [realTarget], stays listable: a link's target must be inside [real] and outside a nested agent. */
    private fun isListableLinkTarget(
        real: Path,
        p: Path,
        realTarget: Path,
    ): Boolean {
        if (realTarget == p) return true
        // CWE-59: relativeTo does not throw for a target
        // outside real, so containment must be tested explicitly first.
        if (!realTarget.startsWith(real)) return false
        val targetRelative = realTarget.relativeTo(real)
        return !AgentDirs.isBelowNestedAgentDir(real, targetRelative)
    }

    fun read(
        agentName: String,
        relPath: String,
    ): FileOpResult<String> {
        val resolved = paths.resolveSafe(agentName, relPath)
        if (resolved !is ResolveOk) return resolveErrorToFileOp(resolved)
        val target = resolved.path
        if (!target.exists()) return FileOpResult.NotFound("File not found: $relPath")
        if (target.isDirectory()) return FileOpResult.BadRequest("Refusing to read a directory: $relPath")

        val size = Files.size(target)
        if (size > maxConfigFileBytes) {
            return FileOpResult.BadRequest("File too large to read inline: ${size}B (cap ${maxConfigFileBytes}B)")
        }
        val bytes = Files.readAllBytes(target)
        if (looksBinary(bytes)) {
            return FileOpResult.BadRequest("Refusing to read binary file: $relPath")
        }
        return FileOpResult.Ok(bytes.toString(Charsets.UTF_8))
    }

    fun write(
        agentName: String,
        relPath: String,
        content: String,
        authorizeCreateAgent: (String) -> Boolean = { true },
    ): FileOpResult<Unit> {
        val agent =
            agentsService.agents[agentName]
                ?: return FileOpResult.NotFound("Agent '$agentName' not found")
        if (!agent.writable) return FileOpResult.Forbidden("Agent directory is read-only")

        // Capped before the write lands, so an oversized body never reaches disk (CWE-400, ADR-0047 rule 4).
        val sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
        if (sizeBytes > maxConfigFileBytes) {
            return FileOpResult.BadRequest(
                "File too large to write: ${sizeBytes}B (cap ${maxConfigFileBytes}B)",
            )
        }

        val resolved = paths.resolveSafe(agentName, relPath)
        if (resolved !is ResolveOk) return resolveErrorToFileOp(resolved)
        val target = resolved.path

        if (target.isDirectory()) return FileOpResult.BadRequest("Refusing to overwrite a directory: $relPath")

        // Definition checks key on where the write lands, so an alias like `notes.txt -> agent.yaml`
        // is the definition (CWE-59, ADR-0068).
        definitionGate.denial(resolved.landingRel, relPath, content, authorizeCreateAgent)?.let { return it }

        // Writes through the gated landing, never a dangling-link `target`; a permission change since the
        // probe is a fixed 403 with no OS message or path, and no reload (ADR-0004, ADR-0047 rule 7).
        if (!writeCreatingParents(resolved.landing, content.toByteArray(Charsets.UTF_8))) {
            return FileOpResult.Forbidden("Agent directory is read-only")
        }
        agentsService.reload()
        return FileOpResult.Ok(Unit)
    }

    /** Writes [bytes] at [landing], creating its parents; false when that is denied. See [write]. */
    private fun writeCreatingParents(
        landing: Path,
        bytes: ByteArray,
    ): Boolean {
        // The directories `createDirectories` will build, so a denied write removes the empty
        // chain it left. See ADR-0004.
        val made =
            landing.parent
                ?.let { parent -> generateSequence(parent) { it.parent }.takeWhile { !it.exists() }.toList() }
                ?: emptyList()
        try {
            createDirectoriesAndWrite(landing, bytes)
        } catch (_: AccessDeniedException) {
            made.forEach { dir -> runCatching { Files.deleteIfExists(dir) } }
            return false
        }
        return true
    }

    fun delete(
        agentName: String,
        relPath: String,
    ): FileOpResult<Unit> {
        val agent =
            agentsService.agents[agentName]
                ?: return FileOpResult.NotFound("Agent '$agentName' not found")
        if (!agent.writable) return FileOpResult.Forbidden("Agent directory is read-only")

        val resolved = paths.resolveSafe(agentName, relPath)
        if (resolved !is ResolveOk) return resolveErrorToFileOp(resolved)
        // Same landing rule as write: an alias to the definition is the definition (CWE-59).
        if (isAgentDef(resolved.landingRel)) return FileOpResult.Forbidden("Cannot delete the agent definition file")
        val target = resolved.path
        if (!target.exists()) return FileOpResult.NotFound("File not found: $relPath")
        if (target.isDirectory()) return FileOpResult.BadRequest("Refusing to delete a directory")
        return deleteFile(target)
    }

    /**
     * Deletes [target] and reloads the agents. Same race as write(): a read-only target or
     * parent directory in the check-to-syscall window is a 403, not an unhandled 500 (ADR-0004).
     */
    private fun deleteFile(target: Path): FileOpResult<Unit> {
        try {
            target.deleteExisting()
        } catch (_: AccessDeniedException) {
            return FileOpResult.Forbidden("Agent directory is read-only")
        }
        agentsService.reload()
        return FileOpResult.Ok(Unit)
    }

    /**
     * Creates a new agent directory under the configured agentsSearchPath with
     * a minimal `agent.yaml` template. Returns the agent name on success.
     */
    fun create(name: String): FileOpResult<String> {
        if (!AgentDirs.AGENT_NAME_PATTERN.matches(name)) {
            return FileOpResult.BadRequest("Invalid agent name: only letters, digits, '-', '_' allowed")
        }
        if (agentsService.agents.containsKey(name)) {
            return FileOpResult.Conflict("Agent '$name' already exists")
        }
        val root = agentsService.rootPath
        searchPathDenial(root)?.let { return it }

        val dir = root.resolve(name)
        if (dir.exists()) return FileOpResult.Conflict("Directory '$name' already exists")

        // Same race as write(): the search path can become unwritable between the
        // isWritable probe above and the syscall (ADR-0004). Fixed message, no path.
        try {
            createDirectoriesAndTemplate(dir, name)
        } catch (_: AccessDeniedException) {
            // Remove the definition-less directory, which would wedge the name behind a 409; a
            // raced-in file keeps it, and the 409 then reports it truthfully. See ADR-0004.
            if (Files.notExists(dir.resolve("agent.yaml"))) runCatching { Files.deleteIfExists(dir) }
            return FileOpResult.Forbidden("Agents search path is not writable")
        }
        agentsService.reload()
        return FileOpResult.Ok(name)
    }

    /** Why no agent can be created under the agents search path [root], else null. */
    private fun searchPathDenial(root: Path): FileOpResult<Nothing>? {
        if (!root.exists() || !root.isDirectory()) {
            return FileOpResult.BadRequest("Agents search path does not exist: $root")
        }
        if (!Files.isWritable(root)) {
            return FileOpResult.Forbidden("Agents search path is not writable: $root")
        }
        return null
    }

    /**
     * `create()`'s directory and template write as one step: the test seam for a denied template
     * write, which no POSIX bit combination produces once `createDirectories` has run.
     */
    internal open fun createDirectoriesAndTemplate(
        dir: Path,
        name: String,
    ) {
        Files.createDirectories(dir)
        Files.write(dir.resolve("agent.yaml"), agentTemplate(name).toByteArray(Charsets.UTF_8))
    }

    /**
     * `write()`'s parent creation and write as one step: the test seam for a denied write, which
     * no POSIX bit combination produces once `createDirectories` has run.
     */
    internal open fun createDirectoriesAndWrite(
        landing: Path,
        bytes: ByteArray,
    ) {
        landing.parent?.let { Files.createDirectories(it) }
        Files.write(landing, bytes)
    }

    private fun agentTemplate(name: String): String =
        """
        name: $name
        description: New agent — edit me.
        commands:
          - !<cmd>
            label: hello
            cmd: ["echo", "hello from $name"]
        """.trimIndent() + "\n"

    private fun resolveErrorToFileOp(result: ResolveResult): FileOpResult<Nothing> = (result as ResolveError).result

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(BINARY_SNIFF_BYTES)
        return sample.any { it == 0.toByte() }
    }

    companion object {
        private const val DEFAULT_MAX_CONFIG_FILE_BYTES: Long = 1L * 1024 * 1024
        private const val LIST_MAX_DEPTH: Int = 2
        private const val LIST_MAX_ENTRIES: Int = 1000
        private const val BINARY_SNIFF_BYTES: Int = 8000
    }
}
