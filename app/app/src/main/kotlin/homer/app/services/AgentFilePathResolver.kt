package homer.app.services

import homer.app.infra.AgentDirs
import homer.app.infra.PathSafety
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name

// Path containment for [AgentFilesService]: resolves a caller-supplied path against an agent's
// directory and refuses `..`, absolute paths, symlink escapes and a nested agent's subtree.
// See ADR-0047, ADR-0068.
internal class AgentFilePathResolver(
    private val agentsService: AgentsService,
) {
    fun resolveSafe(
        agentName: String,
        relPath: String,
    ): ResolveResult {
        if (relPath.isBlank()) return ResolveError(AgentFilesService.FileOpResult.BadRequest("Empty path"))
        if (relPath.startsWith("/")) return ResolveError(AgentFilesService.FileOpResult.BadRequest("Path must be relative"))
        val dir =
            agentsService.getAgentDir(agentName)
                ?: return ResolveError(AgentFilesService.FileOpResult.NotFound("Agent '$agentName' not found"))
        if (!dir.exists()) return ResolveError(AgentFilesService.FileOpResult.NotFound("Agent directory missing"))

        val candidate = dir.resolve(relPath).normalize()
        val resolved =
            try {
                PathSafety.assertContained(dir, candidate)
            } catch (_: SecurityException) {
                return ResolveError(
                    AgentFilesService.FileOpResult.BadRequest("Path escapes the agent directory: $relPath"),
                )
            }
        return gateContained(relPath, dir, candidate, resolved)
    }

    /** The nested-agent, landing, depth and discovery gates [resolveSafe] runs on a contained [resolved] path. */
    private fun gateContained(
        relPath: String,
        dir: Path,
        candidate: Path,
        resolved: Path,
    ): ResolveResult {
        // Nested agent = different grant, so containment is not authorization: nothing at or inside
        // its subtree is reachable, denied as not-found like a missing path (§12 rule 3). The gate
        // runs on the resolved realpath, not the lexical candidate (CWE-59). See ADR-0047, ADR-0068.
        val realDir = dir.toRealPath()
        val gateRel = if (resolved.startsWith(realDir)) realDir.relativize(resolved) else dir.relativize(candidate)
        if (AgentDirs.isBelowNestedAgentDir(dir, gateRel)) {
            return ResolveError(AgentFilesService.FileOpResult.NotFound("File not found: $relPath"))
        }
        // A WRITE to a not-yet-existing path follows any dangling symlink in the chain, so gate where
        // the write would land (link chain followed) against the real root and the nested boundary.
        val landingAbsolute = PathSafety.resolveDanglingLinkTarget(candidate)
        if (!landingAbsolute.startsWith(realDir)) {
            return ResolveError(AgentFilesService.FileOpResult.NotFound("File not found: $relPath"))
        }
        val landing = realDir.relativize(landingAbsolute)
        if (AgentDirs.isBelowNestedAgentDir(realDir, landing)) {
            return ResolveError(AgentFilesService.FileOpResult.NotFound("File not found: $relPath"))
        }
        return landingShapeError(relPath, realDir, landing) ?: ResolveOk(resolved, realDir, landingAbsolute)
    }

    /** The depth and discovery-reach gates on a request's [landing] below [realDir]; null when it passes both. */
    private fun landingShapeError(
        relPath: String,
        realDir: Path,
        landing: Path,
    ): ResolveError? {
        // A deep path is the walk's entry cap pointed at the disk: bound the caller-supplied depth
        // before any disk touch, since the directories a write creates are real (§12 rule 4, CWE-400).
        if (AgentDirs.isTooDeep(landing)) {
            return ResolveError(
                AgentFilesService.FileOpResult.BadRequest("Path is too deep (more than ${AgentDirs.MAX_PATH_SEGMENTS} segments): $relPath"),
            )
        }
        // A definition landing discovery never reaches (too deep or under a hidden dir) would leave a
        // subtree this API can only 404 for; refused before any disk touch. See ADR-0068.
        if (isDefFileName(landing.invariantSeparatorsPathString) &&
            AgentDirs.isUnreachableByDiscovery(agentRootDepth(realDir), landing)
        ) {
            return ResolveError(
                AgentFilesService.FileOpResult.BadRequest(
                    "Definition path discovery would never load (deeper than ${AgentDirs.MAX_DEPTH} or hidden): $relPath",
                ),
            )
        }
        return null
    }

    // Levels [dir] sits below the agents search path, resolved to real paths (the config value may be
    // relative or reach through a symlink); a dir outside it fails loudly rather than faking a depth.
    private fun agentRootDepth(dir: Path): Int {
        val root = agentsService.rootPath.toRealPath()
        require(dir.startsWith(root)) { "Agent directory '$dir' is not below the agents search path '$root'" }
        return root.relativize(dir).nameCount
    }
}

/** What [AgentFilePathResolver.resolveSafe] made of a request's path. */
internal sealed class ResolveResult

internal data class ResolveOk(
    val path: Path,
    /** The agent directory's real path — the containment root for a write through [path]. */
    val agentDir: Path,
    /** Where a write through [path] lands — the request's symlink chain followed, inside [agentDir]. */
    val landing: Path,
) : ResolveResult() {
    /** The path a request lands on, relative to the agent directory's real path. */
    val landingRel: String get() = agentDir.relativize(landing).invariantSeparatorsPathString
}

internal data class ResolveError(
    val result: AgentFilesService.FileOpResult<Nothing>,
) : ResolveResult()

// Normalize before matching: the raw string is checked before resolveSafe normalizes it, so
// "./agent.yaml" would otherwise slip past the delete/validate guards.

/** True when [relPath] is the agent's own root definition file. */
internal fun isAgentDef(relPath: String): Boolean {
    val normalized = runCatching { Path.of(relPath).normalize().toString() }.getOrNull() ?: return false
    return normalized == "agent.yaml" || normalized == "agent.json"
}

// Any definition file at any depth: reload() loads it as an agent the moment its directory holds it
// (ADR-0068), so a write here creates an agent even if it is not this agent's own root definition.
internal fun isDefFileName(relPath: String): Boolean =
    runCatching { Path.of(relPath).normalize().name }.getOrNull() in AgentDirs.DEFINITION_FILE_NAMES
