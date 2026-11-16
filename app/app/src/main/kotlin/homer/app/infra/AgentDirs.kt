package homer.app.infra

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.PriorityQueue
import kotlin.io.path.name

/** The one implementation of the "what is an agent directory, and where are they" rule (ADR-0068):
 * identity is the path relative to the search-path root, never the leaf name (two nested `analysis/`
 * would collide). Bounds are local and explicit (§12 rule 4): depth/entry/raw-entry caps, hidden dirs skipped, symlinks never followed (CWE-400). */
internal object AgentDirs {
    /** How many levels below the search-path root are walked. A definition deeper than this is not an agent. */
    const val MAX_DEPTH: Int = 8

    /** Ceiling on directories visited within ONE top-level agent directory's subtree; past it that
     * subtree's walk stops. The budget is fresh per top-level directory — see [walkAgentDirs]. */
    const val MAX_ENTRIES: Int = 1000

    /** Outer ceiling on directories visited in one whole-catalog walk, across every top-level directory. */
    const val MAX_TOTAL_ENTRIES: Int = 10_000

    /** Ceiling on raw entries — files and hidden/symlinked entries included — read within ONE
     * top-level directory's subtree. The budgets above count directories only; without this a
     * principal could multiply listings full of files (CWE-400). Fresh per top-level directory. */
    const val MAX_SUBTREE_RAW_ENTRIES: Int = 200_000

    /** Outer ceiling on raw entries read in one whole-catalog walk, the search-path listing included. */
    const val MAX_TOTAL_RAW_ENTRIES: Int = 2_000_000

    /** Ceiling on the path segments ANY caller-supplied relative path may carry into a
     * directory-creating operation — a deep chain is the CWE-400 vector pointed at the disk. A
     * definition landing is bounded further by [isUnreachableByDiscovery]. */
    const val MAX_PATH_SEGMENTS: Int = MAX_DEPTH + 2

    private val logger = LoggerFactory.getLogger(AgentDirs::class.java)

    /** The agent-name charset (AGENT.md § Agent Fields, `name` row); an alias of the kernel pattern
     * ([homer.app.models.AGENT_NAME_PATTERN]) so the scanner and hook-target check cannot drift. */
    val AGENT_NAME_PATTERN: Regex = homer.app.models.AGENT_NAME_PATTERN

    /** The recognized definition-file names, `agent.json` first so it wins. */
    val DEFINITION_FILE_NAMES: List<String> = listOf("agent.json", "agent.yaml")

    /** True when [dir] is itself an agent directory. The definition must be a real file inside it
     * (`NOFOLLOW_LINKS`): a symlinked `agent.yaml` read through to its target would bypass
     * [resolveSafe]'s deny (CWE-59). The walk refuses a symlinked directory before this runs. */
    fun isAgentDir(dir: Path): Boolean = definitionFile(dir) != null

    /** The definition file discovery reads for [dir], under the same no-symlink rule as [isAgentDir]; null when there is none. */
    fun definitionFile(dir: Path): Path? =
        DEFINITION_FILE_NAMES
            .map { dir.resolve(it) }
            .firstOrNull { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }

    /** Walks [root] and yields every agent directory found, as paths relative to [root], depth-first
     * and in sorted listing order. [root] itself is never yielded. The walk descends *through* an
     * agent directory — a nested agent is found at its own position — except where a bound, a hidden name or a symlink stops it. */
    fun walkAgentDirs(
        root: Path,
        subtreeRawEntries: Int = MAX_SUBTREE_RAW_ENTRIES,
        totalRawEntries: Int = MAX_TOTAL_RAW_ENTRIES,
    ): List<Path> {
        val walk = CatalogWalk(subtreeRawEntries, totalRawEntries)
        walk.walkCatalog(root)
        warnIfTruncated(root, subtreeRawEntries, totalRawEntries, walk.truncatedTops, walk.catalogTruncated)
        return walk.found
    }

    /** One whole-catalog walk of [walkAgentDirs]: the directories found and the budgets hit.
     * [subtreeRawEntries] is the fresh per-top-level budget; [totalRawEntries] the whole walk's. */
    private class CatalogWalk(
        private val subtreeRawEntries: Int,
        totalRawEntries: Int,
    ) {
        val found = mutableListOf<Path>()
        val truncatedTops = mutableListOf<String>()
        var catalogTruncated = false
            private set
        private var totalVisited = 0

        // The search-path listing belongs to no subtree: only the total budget charges it.
        private val raw = RawBudget(subtree = Int.MAX_VALUE, total = totalRawEntries)
        private var subtreeTruncated = false

        fun walkCatalog(root: Path) {
            // Each top-level directory walks its own subtree with a fresh entry budget; the
            // whole-catalog cap stays an outer ceiling. CWE-400: a shared budget let one principal's
            // wide/deep subtree drop other principals' agents. Each top-level dir costs one total unit.
            val topLevel = readableChildren(root, MAX_TOTAL_ENTRIES, raw)
            if (topLevel.truncated) catalogTruncated = true
            for (top in topLevel.dirs) {
                if (totalVisited >= MAX_TOTAL_ENTRIES || raw.total <= 0) {
                    catalogTruncated = true
                    break
                }
                totalVisited++
                val topRelative = Path.of(top.name)
                if (isAgentDir(top)) found.add(topRelative)
                // `top` sits at depth 1 below the root; the inner walk receives the listed
                // directory's depth, +1, so it stops at MAX_DEPTH levels below the root.
                raw.subtree = subtreeRawEntries
                subtreeTruncated = false
                walk(top, topRelative, 2, MAX_ENTRIES)
                if (subtreeTruncated) truncatedTops.add(top.name)
            }
        }

        private fun walk(
            dir: Path,
            relative: Path,
            depth: Int,
            budgetIn: Int,
        ): Int {
            var budget = budgetIn
            if (depth > MAX_DEPTH) return budget
            val listing = readableChildren(dir, budget, raw)
            if (listing.truncated) {
                subtreeTruncated = true
                if (raw.total <= 0) catalogTruncated = true
            }
            for (child in listing.dirs) {
                if (budget <= 0) {
                    subtreeTruncated = true
                    return budget
                }
                budget--
                totalVisited++
                val childRelative = relative.resolve(child.name)
                if (isAgentDir(child)) found.add(childRelative)
                budget = walk(child, childRelative, depth + 1, budget)
            }
            return budget
        }
    }

    private fun warnIfTruncated(
        root: Path,
        subtreeRawEntries: Int,
        totalRawEntries: Int,
        truncatedTops: List<String>,
        catalogTruncated: Boolean,
    ) {
        if (catalogTruncated || truncatedTops.isNotEmpty()) {
            logger.warn(
                "Agent discovery under '{}' hit an entry cap ({} directories and {} raw entries per top-level directory, " +
                    "{} directories and {} raw entries total); some agents were not loaded. " +
                    "Truncated top-level directories: {}{}; whole-catalog cap hit: {}",
                root,
                MAX_ENTRIES,
                subtreeRawEntries,
                MAX_TOTAL_ENTRIES,
                totalRawEntries,
                truncatedTops.take(MAX_NAMED_TRUNCATIONS),
                if (truncatedTops.size > MAX_NAMED_TRUNCATIONS) " and ${truncatedTops.size - MAX_NAMED_TRUNCATIONS} more" else "",
                catalogTruncated,
            )
        }
    }

    /** How many truncated top-level directories the cap warning names; the rest are counted. */
    private const val MAX_NAMED_TRUNCATIONS = 10

    /** Raw entries the walk may still read: [subtree] for the current top-level directory, [total] for the catalog. */
    private class RawBudget(
        var subtree: Int,
        var total: Int,
    )

    private class Listing(
        val dirs: List<Path>,
        val truncated: Boolean,
    )

    /** [walkableChildren], or none (logged as a skip) when [dir] cannot be listed. */
    private fun readableChildren(
        dir: Path,
        keep: Int,
        raw: RawBudget,
    ): Listing =
        try {
            walkableChildren(dir, keep, raw)
        } catch (e: Exception) {
            logger.warn("Skipping unreadable directory '{}': {}", dir, e.message)
            Listing(emptyList(), truncated = false)
        }

    /** The children of [dir] the walk visits, the first [keep] by name, sorted. Filtered before the
     * budget applies and ordered by name before the cut, so the kept set never depends on listing
     * order. Every raw entry read is charged to [raw]; at most [keep] + 1 are held (CWE-400). */
    private fun walkableChildren(
        dir: Path,
        keep: Int,
        raw: RawBudget,
    ): Listing {
        // Largest name on top, so the one evicted is the one past the cut.
        val kept = PriorityQueue<Path>(Comparator.reverseOrder())
        var truncated = false
        Files.newDirectoryStream(dir).use { stream ->
            for (entry in stream) {
                if (raw.subtree <= 0 || raw.total <= 0) {
                    truncated = true
                    break
                }
                raw.subtree--
                raw.total--
                // Hidden directories hold tooling (.git, .venv), not definitions. A symlinked
                // directory is not a directory under NOFOLLOW_LINKS and is never followed: its
                // target would be walked under a name that does not describe it (§12 rule 2, CWE-59).
                if (entry.name.startsWith(".")) continue
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue
                if (keep <= 0) {
                    truncated = true
                    break
                }
                kept.add(entry)
                if (kept.size > keep) {
                    kept.poll()
                    truncated = true
                }
            }
        }
        return Listing(kept.sorted(), truncated)
    }

    /** True when [relPath]'s segment count exceeds [MAX_PATH_SEGMENTS]. `.`/blank segments carry no
     * descent and do not count; the lexical check runs before any disk touch, so an over-deep `PUT`
     * is refused before `createDirectories` can build the chain it names. */
    fun isTooDeep(relPath: Path): Boolean =
        (0 until relPath.nameCount)
            .map { relPath.getName(it).toString() }
            .filter { it.isNotEmpty() && it != "." }
            .size > MAX_PATH_SEGMENTS

    /** True when [relPath] (relative to [agentDir]) is at or below a *nested* agent directory — the
     * ADR-0047 boundary check: a parent's file API must not reach a nested agent's subtree, because
     * the parent's grant is not the child's. O(depth) `stat` calls, no listing; [relPath] must be contained. */
    fun isBelowNestedAgentDir(
        agentDir: Path,
        relPath: Path,
    ): Boolean {
        // By index, not by iterator: an empty path iterates as one empty name (and
        // `resolve("")` is the agent's own directory, which would match every call).
        // Blank and "." segments carry no descent and are skipped.
        val segments =
            (0 until relPath.nameCount)
                .map { relPath.getName(it).toString() }
                .filter { it.isNotEmpty() && it != "." }
        var prefix: Path? = null
        for (segment in segments) {
            prefix = if (prefix == null) Path.of(segment) else prefix.resolve(segment)
            if (isAgentDir(agentDir.resolve(prefix))) return true
        }
        return false
    }

    /** True when a definition landing at [landingRel] is a directory [walkAgentDirs] never reaches —
     * deeper than [MAX_DEPTH] or under a dot-prefixed component. Such a directory still counts as a
     * nested-agent boundary, so leaving one behind makes the subtree unreadable (CWE-400). Mirrors the walk's stop exactly. */
    fun isUnreachableByDiscovery(
        agentDirDepth: Int,
        landingRel: Path,
    ): Boolean {
        val directories =
            (0 until landingRel.nameCount)
                .map { landingRel.getName(it).toString() }
                .filter { it.isNotEmpty() && it != "." }
                .dropLast(1)
        if (directories.any { it.startsWith(".") }) return true
        return agentDirDepth + directories.size > MAX_DEPTH
    }

    /** The nested agent directories under [agentDir], relative to it — what a parent's
     * `HMR_AGENT_DIR` upload must exclude (ADR-0068). Exact, not the capped discovery walk, which
     * would ship a child's subtree under the parent's identity (CWE-285); symlinks excluded (CWE-59). */
    fun nestedAgentDirsUnder(agentDir: Path): List<Path> {
        // A missing source is the upload's own failure to report, not this walk's.
        if (!Files.isDirectory(agentDir)) return emptyList()
        val found = mutableListOf<Path>()
        Files.walkFileTree(
            agentDir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != agentDir && isAgentDir(dir)) {
                        found.add(agentDir.relativize(dir))
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // Without FOLLOW_LINKS a symlinked directory arrives here; a dangling one is not a directory.
                    if (attrs.isSymbolicLink && Files.isDirectory(file)) found.add(agentDir.relativize(file))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult {
                    logger.warn("Skipping unreadable entry '{}' while listing nested agents: {}", file, exc.message)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return found.sorted()
    }
}
