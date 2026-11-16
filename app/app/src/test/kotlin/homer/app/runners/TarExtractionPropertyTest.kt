package homer.app.runners

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [TarExtraction.extractEntry]'s containment gate over generated tar entries (ADR-0047/0054,
 * CWE-22/59): the remote tar is attacker-controlled, so no entry — file, directory or link — may
 * land outside the destination, whatever its name. The example cases live in `TarExtractionTest`;
 * these drive the escape shapes its author did not list (root CLAUDE.md §6).
 */
class TarExtractionPropertyTest {
    @TempDir
    lateinit var parent: Path

    @TempDir
    lateinit var outside: Path

    private var created = 0

    /** Every destination handed out so far: the only places an extraction may write. */
    private val destinations = mutableListOf<Path>()

    /** A fresh destination per iteration, so a write outside it is visible next to it in [parent]. */
    private fun destination(): Path {
        val wrapper = Files.createDirectories(parent.resolve("w${created++}"))
        val dest = Files.createDirectories(wrapper.resolve("dest"))
        destinations.add(dest)
        return dest
    }

    /** A plain (uncompressed) tar holding [entry], positioned at it, plus the parsed entry. */
    private fun tarOf(entry: TarArchiveEntry): Pair<TarArchiveInputStream, TarArchiveEntry> {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { out ->
            out.putArchiveEntry(entry)
            if (entry.size > 0) out.write(PAYLOAD.toByteArray())
            out.closeArchiveEntry()
        }
        val tar = TarArchiveInputStream(ByteArrayInputStream(bytes.toByteArray()))
        return tar to tar.nextEntry
    }

    private fun extract(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        dest: Path,
    ) = TarExtraction.extractEntry(tar, entry, remoteIsDir = true, dest, "", DownloadMode.OVERWRITE, null, "test", null)

    /**
     * Every path under [parent] belongs to some iteration's destination or is a directory on the way
     * to one: the walk is rooted above the destinations, so an entry written next to one — or one
     * level up — is caught, and it does not follow links.
     */
    private fun assertNothingOutside(label: String) {
        Files.walk(parent).use { paths ->
            paths.forEach { path ->
                assertTrue(
                    destinations.any { path.startsWith(it) || it.startsWith(path) },
                    "entry '$label' created '$path' outside every destination: $destinations",
                )
            }
        }
    }

    /**
     * Names built from traversal segments, absolute prefixes and a concrete leaf, so the resolved
     * target is never the destination itself; the archive decides file vs directory from the name.
     */
    private val names: Arb<String> =
        Arb.bind(
            Arb.list(Arb.element("..", ".", "", "sub", "a.txt", "deep"), 1..4),
            Arb.element("", "/", "./", "../", "/abs/"),
            Arb.element("", "/"),
            Arb.element("leaf.txt", "leaf", "run.sh"),
        ) { middle, prefix, suffix, leaf -> prefix + (middle + leaf).joinToString("/") + suffix }

    private val modes: Arb<Int> = Arb.element(0, "100644".toInt(8), "100755".toInt(8), "100777".toInt(8), "777".toInt(8))

    private val entries: Arb<Pair<String, Int>> = Arb.bind(names, modes) { name, mode -> name to mode }

    private fun entryOf(
        name: String,
        mode: Int,
    ): TarArchiveEntry =
        TarArchiveEntry(name).apply {
            this.mode = mode
            if (!isDirectory) size = PAYLOAD.length.toLong()
        }

    @Test
    fun `no generated entry lands outside the destination`() {
        runTest {
            checkAll(200, entries) { (name, mode) ->
                val dest = destination()
                val (tar, entry) = tarOf(entryOf(name, mode))
                runCatching { extract(tar, entry, dest) }.exceptionOrNull()?.let { failure ->
                    assertIs<SecurityException>(failure, "entry '$name' failed with an unexpected error")
                }
                assertNothingOutside(name)
            }
        }
    }

    /**
     * Spellings that can only leave the destination: a `..` chain, plainly or behind a `.` segment.
     * An absolute spelling is deliberately absent: the tar reader strips a leading `/` while
     * re-parsing the entry, so it never reaches the guard as one — the first property covers those.
     */
    private val escapingNames: Arb<String> =
        Arb.bind(Arb.int(1, 4), Arb.element("x.txt", "sub", "a.txt"), Arb.element("", "./")) { depth, leaf, prefix ->
            prefix + "../".repeat(depth) + leaf
        }

    @Test
    fun `an entry whose name escapes the destination is refused`() {
        runTest {
            checkAll(100, escapingNames) { name ->
                val dest = destination()
                val (tar, entry) = tarOf(entryOf(name, "100644".toInt(8)))
                assertThrows<SecurityException>("entry '$name' must be refused") { extract(tar, entry, dest) }
                assertNothingOutside(name)
            }
        }
    }

    /** Single-segment names for the link cases: no separator, so the link itself is the only path. */
    private val plainNames: Arb<String> = Arb.list(Arb.element('a', 'b', 'z', '0', '_', '-'), 1..8).map { it.joinToString("") }

    private val linkTargets: Arb<String> =
        Arb.bind(Arb.element("", "/", "../", "../../"), Arb.element("target", "etc/passwd", "sub/target")) { prefix, leaf ->
            prefix + leaf
        }

    @Test
    fun `a link entry is never materialized`() {
        runTest {
            checkAll(100, plainNames, linkTargets) { name, linkName ->
                val dest = destination()
                val (tar, entry) = tarOf(TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK).apply { this.linkName = linkName })

                extract(tar, entry, dest)

                assertFalse(
                    Files.exists(dest.resolve(name), LinkOption.NOFOLLOW_LINKS),
                    "link entry '$name' -> '$linkName' must not be materialized",
                )
                assertTrue(Files.list(dest).use { it.toList() }.isEmpty(), "a skipped link entry must create nothing")
                assertNothingOutside(name)
            }
        }
    }

    /**
     * A link planted in the destination — live (pointing at an existing file outside) or dangling —
     * must not steer the entry's write through it (CWE-59). Either way the entry is refused and the
     * path outside the destination is untouched.
     */
    @Test
    fun `an entry that would follow a link out of the destination is refused`() {
        runTest {
            checkAll(100, plainNames, Arb.boolean()) { name, live ->
                val dest = destination()
                val target = outside.resolve("target-${created++}")
                if (live) Files.writeString(target, "s")
                Files.createSymbolicLink(dest.resolve(name), target)

                val (tar, entry) = tarOf(entryOf(name, "100644".toInt(8)))

                assertThrows<SecurityException>("entry '$name' through a planted link must be refused") {
                    extract(tar, entry, dest)
                }
                if (live) {
                    assertEquals("s", Files.readString(target), "the link's destination must not be overwritten")
                } else {
                    assertFalse(Files.exists(target), "the entry must not be written through the planted link")
                }
                assertNothingOutside(name)
            }
        }
    }

    private companion object {
        const val PAYLOAD = "payload"
    }
}
