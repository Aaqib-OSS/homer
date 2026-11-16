package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentDirs
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [AgentFilePathResolver.resolveSafe]'s containment gate over generated request paths (ADR-0047,
 * CWE-22/59): a request either resolves inside the agent directory or is denied — never outside it.
 * The example cases live in `AgentFilePathResolverTest`; these cover the `..` chains, absolute
 * spellings and link forms an editor would not think of (root CLAUDE.md §6).
 */
class AgentFilePathResolverPropertyTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private lateinit var resolver: AgentFilePathResolver
    private lateinit var agentDir: Path
    private lateinit var realDir: Path
    private lateinit var name: String

    @BeforeEach
    fun setup() {
        agentDir = Files.createDirectories(root.resolve("my_agent"))
        Files.write(agentDir.resolve("agent.json"), AgentFixtures.validAgentJson().toByteArray())
        Files.createDirectories(agentDir.resolve("sub"))
        Files.writeString(agentDir.resolve("a.txt"), "a")
        Files.writeString(outside.resolve("secret.txt"), "s")
        // A live link out of the agent directory, and a dangling one whose chain leaves it.
        Files.createSymbolicLink(agentDir.resolve("escape"), outside.resolve("secret.txt"))
        Files.createSymbolicLink(agentDir.resolve("dangling"), Path.of("../outside-not-there.txt"))
        realDir = agentDir.toRealPath()

        val agentsService = AgentsService(root.toString(), TestFixtures.mockLogger())
        name = agentsService.agents.keys.first()
        resolver = AgentFilePathResolver(agentsService)
    }

    /** Relative, `./`-prefixed, `../`-prefixed and absolute spellings of the same segment pool. */
    private val candidates: Arb<String> =
        Arb.bind(
            Arb.list(Arb.element("..", ".", "", "sub", "a.txt", "escape", "dangling", "missing"), 1..5),
            Arb.element("", "/", "./", "../"),
            Arb.element("", "/"),
        ) { parts, prefix, suffix -> prefix + parts.joinToString("/") + suffix }

    @Test
    fun `a resolved request is always inside the agent directory or denied`() {
        runTest {
            checkAll(300, candidates) { rel ->
                val resolved = resolver.resolveSafe(name, rel)
                if (resolved is ResolveOk) {
                    assertTrue(
                        resolved.landing.startsWith(realDir),
                        "'$rel' lands outside the agent directory: ${resolved.landing}",
                    )
                    assertTrue(
                        resolved.path.startsWith(realDir) ||
                            resolved.path.startsWith(agentDir.toAbsolutePath().normalize()),
                        "'$rel' resolved outside the agent directory: ${resolved.path}",
                    )
                } else {
                    // A denial is the other allowed outcome, and must be the typed one — not, say, a throw.
                    assertIs<ResolveError>(resolved, "'$rel' was neither resolved nor denied")
                }
            }
        }
    }

    /** Spellings that can only leave the agent directory: a `..` chain, an absolute path, the planted link. */
    private val escapes: Arb<String> =
        Arb.bind(
            Arb.int(1, 5),
            Arb.element("x.txt", "sub", "a.txt", "agent.json"),
            Arb.element("up", "absolute", "link"),
        ) { depth, leaf, kind ->
            when (kind) {
                "absolute" -> "/" + (1..depth).joinToString("/") { "d$it" } + "/$leaf"
                "link" -> "escape/$leaf"
                else -> "../".repeat(depth) + leaf
            }
        }

    @Test
    fun `a request that leaves the agent directory is always denied`() {
        runTest {
            checkAll(150, escapes) { rel ->
                assertIs<ResolveError>(resolver.resolveSafe(name, rel), "'$rel' must be denied, never resolved")
            }
        }
    }

    /** The planted links themselves, and the single-segment spellings of them. */
    @Test
    fun `a request for the planted link itself is denied`() {
        for (rel in listOf("escape", "escape/", "dangling", "dangling/", "./escape")) {
            assertIs<ResolveError>(resolver.resolveSafe(name, rel), "'$rel' must be denied, never resolved")
        }
    }

    /** One segment over the depth cap, spelled relative or absolute. */
    private val overDeep: Arb<String> =
        Arb.bind(
            Arb.int(AgentDirs.MAX_PATH_SEGMENTS + 1, AgentDirs.MAX_PATH_SEGMENTS + 4),
            Arb.element("", "/"),
        ) { depth, prefix -> prefix + (1..depth).joinToString("/") { "d$it" } }

    @Test
    fun `a request deeper than the segment cap is always denied`() {
        runTest {
            checkAll(50, overDeep) { rel ->
                assertIs<ResolveError>(resolver.resolveSafe(name, rel))
            }
        }
    }
}
