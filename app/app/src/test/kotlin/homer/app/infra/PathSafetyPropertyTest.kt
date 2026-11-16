package homer.app.infra

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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [PathSafety]'s containment gate over generated escape-heavy paths (ADR-0047, CWE-22/59): every
 * call either refuses the candidate or answers with a path under the root — never one outside it.
 * The example cases live in `PathSafetyTest`; these cover the segment combinations an editor would
 * not think of, including the planted live and dangling links out of the root (root CLAUDE.md §6).
 */
class PathSafetyPropertyTest {
    @TempDir
    lateinit var root: Path

    @TempDir
    lateinit var outside: Path

    private val lexicalRoot get() = root.toAbsolutePath().normalize()

    private val realRoot get() = root.toRealPath()

    @BeforeEach
    fun setup() {
        Files.createDirectories(root.resolve("sub"))
        Files.writeString(root.resolve("a.txt"), "a")
        Files.writeString(outside.resolve("secret.txt"), "s")
        // A live link out of the root, a dangling one whose chain leaves it, and a dangling one
        // that stays inside: the three shapes a generated segment pool can route a candidate through.
        Files.createSymbolicLink(root.resolve("escape"), outside.resolve("secret.txt"))
        Files.createSymbolicLink(root.resolve("dangling"), Path.of("../outside-not-there.txt"))
        Files.createSymbolicLink(root.resolve("inside"), Path.of("not-yet.txt"))
    }

    /** Relative, `./`-prefixed, `../`-prefixed and absolute spellings of the same segment pool. */
    private val candidates: Arb<String> =
        Arb.bind(
            Arb.list(Arb.element("..", ".", "", "sub", "a.txt", "escape", "dangling", "inside", "missing"), 1..5),
            Arb.element("", "/", "./", "../"),
            Arb.element("", "/"),
        ) { parts, prefix, suffix -> prefix + parts.joinToString("/") + suffix }

    @Test
    fun `a containment check either refuses or answers under the root`() {
        runTest {
            checkAll(300, candidates) { rel ->
                val candidate = root.resolve(rel)
                val attempt = runCatching { PathSafety.assertContained(root, candidate) }
                val resolved = attempt.getOrNull()
                if (resolved == null) {
                    // Refusing is the other allowed outcome; any other failure is a bug, not a refusal.
                    assertIs<SecurityException>(attempt.exceptionOrNull(), "'$rel' failed with an unexpected exception")
                } else {
                    assertTrue(
                        resolved.startsWith(realRoot) || resolved.startsWith(lexicalRoot),
                        "'$rel' was accepted but resolved outside the root: $resolved",
                    )
                }
            }
        }
    }

    /** `..`-prefixed relative spellings: one level up already leaves the root. */
    private val escaping: Arb<String> =
        Arb.bind(Arb.int(1, 5), Arb.element("x.txt", "sub", "a.txt")) { depth, leaf -> "../".repeat(depth) + leaf }

    @Test
    fun `a path that climbs out of the root is always refused`() {
        runTest {
            checkAll(100, escaping) { rel ->
                assertFailsWith<SecurityException>("'$rel' must be refused") {
                    PathSafety.assertContained(root, root.resolve(rel))
                }
            }
        }
    }

    @Test
    fun `an absolute path outside the root is always refused`() {
        runTest {
            checkAll(100, Arb.list(Arb.element("x", "sub", "a.txt"), 1..3)) { parts ->
                val candidate = outside.resolve(parts.joinToString("/"))
                assertFailsWith<SecurityException>("'$candidate' must be refused") {
                    PathSafety.assertContained(root, candidate)
                }
            }
        }
    }

    /**
     * The planted links, deterministically: `assertContained` refuses the live link out of the root,
     * and `assertDanglingLinkContained` refuses the dangling chain that leaves it while allowing the
     * one that stays inside.
     */
    @Test
    fun `a planted link out of the root is refused and one inside it is allowed`() {
        assertFailsWith<SecurityException> { PathSafety.assertContained(root, root.resolve("escape")) }
        assertFailsWith<SecurityException> { PathSafety.assertDanglingLinkContained(root, root.resolve("dangling")) }
        PathSafety.assertDanglingLinkContained(root, root.resolve("inside"))
    }

    /**
     * A link whose chain is entirely dangling (the generated target never exists, so no hop lands on
     * a live link) is either refused or resolves to a path under the root — the property a write
     * through the link relies on.
     */
    @Test
    fun `a dangling link is refused or leads inside the root`() {
        var created = 0
        runTest {
            checkAll(150, danglingTargets) { target ->
                val link = root.resolve("link-${created++}")
                Files.createSymbolicLink(link, Path.of(target))
                val attempt =
                    runCatching {
                        PathSafety.assertDanglingLinkContained(root, link)
                        PathSafety.resolveDanglingLinkTarget(link)
                    }
                val landing = attempt.getOrNull()
                if (landing == null) {
                    assertIs<SecurityException>(attempt.exceptionOrNull(), "dangling link '$target' failed with an unexpected exception")
                } else {
                    assertTrue(
                        landing.startsWith(realRoot) || landing.startsWith(lexicalRoot),
                        "dangling link '$target' was accepted but leads outside the root: $landing",
                    )
                }
            }
        }
    }

    /** Targets that stay dangling: a never-existing leaf, reached relatively or through an absolute path. */
    private val danglingTargets: Arb<String> =
        Arb.bind(
            Arb.list(Arb.element("..", ".", "sub", "missing"), 0..4),
            Arb.element("", "/"),
        ) { parts, absolute ->
            val rel = (parts + "missing-leaf").joinToString("/")
            if (absolute == "/") outside.resolve(rel).toString() else rel
        }
}
