package homer.app.runners

import homer.app.exceptions.RunnerTransferTooLargeException
import io.kotest.property.Arb
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.pattern
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** [TarStreams.extract]'s bound on the entries a tar carries and the paths it materializes (ADR-0047 rule 4). */
class TarEntryCapTest {
    @TempDir
    lateinit var tmp: Path

    private var created = 0

    private fun destination(): Path = tmp.resolve("dst${created++}").createDirectories()

    /** A gzip'd tar of one entry per [names] value; a name ending in `/` is a directory, any other an empty file. */
    private fun tarOf(names: Sequence<String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            names.forEach { name ->
                out.putArchiveEntry(TarArchiveEntry(name))
                out.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun emptyFiles(count: Int) = tarOf((0 until count).asSequence().map { "f$it" })

    /** Counts the compressed bytes the extractor pulled from its input. */
    private class CountingInputStream(
        input: InputStream,
    ) : FilterInputStream(input) {
        var consumed = 0L

        override fun read(): Int = super.read().also { if (it >= 0) consumed++ }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, len).also { if (it > 0) consumed += it }
    }

    private fun extract(
        input: InputStream,
        dst: Path,
        maxEntries: Int,
        mode: DownloadMode = DownloadMode.OVERWRITE,
    ) = TarStreams.extract(
        input,
        dst,
        mode,
        remoteIsDir = true,
        fileName = ".",
        sourceLabel = "test",
        maxDecompressedBytes = TarStreams.DEFAULT_MAX_EXTRACTED_BYTES,
        maxEntries = maxEntries,
    )

    /** Every path below [dst], directories and files alike. */
    private fun pathsUnder(dst: Path): Int = Files.walk(dst).use { walk -> walk.filter { it != dst }.count().toInt() }

    @Test
    fun `a tar with more entries than the cap is refused after the header past it (CWE-400, CWE-770)`() {
        val cap = 8
        val tar = emptyFiles(100_000)
        val input = CountingInputStream(ByteArrayInputStream(tar))
        val dst = destination()

        val refusal = assertThrows<RunnerTransferTooLargeException> { extract(input, dst, cap) }

        assertTrue("$cap tar entries" in refusal.message.orEmpty(), refusal.message)
        assertEquals(cap, pathsUnder(dst))
        assertTrue(input.consumed < tar.size / 10, "read ${input.consumed} of ${tar.size} bytes before refusing")
    }

    @Test
    fun `a tar with exactly the cap of entries extracts every one`() {
        val dst = destination()

        extract(ByteArrayInputStream(emptyFiles(8)), dst, maxEntries = 8)

        assertEquals(8, pathsUnder(dst))
    }

    @Test
    fun `one deep entry is refused before it creates more directories than the cap (CWE-770)`() {
        val dst = destination()
        val deep = (1..20).joinToString("/") { "d$it" } + "/f"

        assertThrows<RunnerTransferTooLargeException> { extract(ByteArrayInputStream(tarOf(sequenceOf(deep))), dst, 8) }

        assertTrue(pathsUnder(dst) <= 8, "created ${pathsUnder(dst)} paths")
    }

    @Test
    fun `a MIRROR extraction records every path of a tar within the cap`() {
        val dst = destination()
        val tar = tarOf(sequenceOf("a/", "a/b/", "a/b/f", "g"))

        val recorded = extract(ByteArrayInputStream(tar), dst, maxEntries = 4, mode = DownloadMode.MIRROR)

        assertEquals(setOf("a", "a/b", "a/b/f", "g").map(Path::of).toSet(), assertNotNull(recorded))
    }

    @Test
    fun `no tar puts more paths under the destination or into the record than the cap, and one past it is refused`() {
        // Directory segments come from [a-c] and every file is named x, so no file name is ever a directory's prefix.
        val name = Arb.pair(Arb.pattern("[a-c](/[a-c]){0,3}"), Arb.boolean())
        runTest {
            checkAll(200, Arb.list(name, 0..24), Arb.int(1..16), Arb.enum<DownloadMode>()) { entries, cap, mode ->
                val names = entries.map { (path, isDir) -> if (isDir) "$path/" else "$path/x" }
                val dst = destination()

                val outcome = runCatching { extract(ByteArrayInputStream(tarOf(names.asSequence())), dst, cap, mode) }

                assertTrue(pathsUnder(dst) <= cap, "$names under cap $cap created ${pathsUnder(dst)} paths")
                outcome.getOrNull()?.let { assertTrue(it.size <= cap, "$names under cap $cap recorded ${it.size} paths") }
                if (names.size > cap) assertTrue(outcome.isFailure, "$names past cap $cap were extracted")
                outcome.exceptionOrNull()?.let { assertTrue(it is RunnerTransferTooLargeException, "unexpected failure $it") }
            }
        }
    }

    @Test
    fun `one entry with a very long and very deep name is refused before any path is recorded (CWE-400, CWE-770)`() {
        val dst = destination()
        val name = "a/".repeat(20_000) + "f"

        val refusal =
            assertThrows<RunnerTransferTooLargeException> {
                extract(ByteArrayInputStream(tarOf(sequenceOf(name))), dst, TarStreams.MAX_EXTRACTED_ENTRIES, DownloadMode.MIRROR)
            }

        assertTrue("Tar entry name exceeds" in refusal.message.orEmpty(), refusal.message)
        assertEquals(0, pathsUnder(dst))
    }

    @Test
    fun `an entry one level past the depth bound is refused, one at it extracts`() {
        val atBound = (1..TarStreams.MAX_ENTRY_SEGMENTS).joinToString("/") { "d$it" }

        val dst = destination()
        extract(ByteArrayInputStream(tarOf(sequenceOf("./$atBound"))), dst, TarStreams.MAX_EXTRACTED_ENTRIES)
        val refusal =
            assertThrows<RunnerTransferTooLargeException> {
                extract(ByteArrayInputStream(tarOf(sequenceOf("$atBound/f"))), destination(), TarStreams.MAX_EXTRACTED_ENTRIES)
            }

        assertTrue(Files.isRegularFile(dst.resolve(atBound)))
        assertTrue("Tar entry name exceeds" in refusal.message.orEmpty(), refusal.message)
    }

    @Test
    fun `a tree packed past the upload nesting cap extracts within the name bounds`() {
        val src = tmp.resolve("deep")
        (1..70)
            .fold(src) { dir, level -> dir.resolve("d$level") }
            .createDirectories()
            .resolve("f")
            .writeText("x")
        val packed = ByteArrayOutputStream().also { TarStreams.pack(it, src) }.toByteArray()
        val dst = destination()

        extract(ByteArrayInputStream(packed), dst, TarStreams.MAX_EXTRACTED_ENTRIES)

        assertTrue(Files.isDirectory(dst.resolve((1..TarStreams.MAX_ENTRY_SEGMENTS).joinToString("/") { "d$it" })))
    }

    @Test
    fun `an entry name is within bounds exactly when its UTF-8 bytes and its segments are`() {
        runTest {
            checkAll(300, Arb.list(Arb.pattern("[a-zé€]{1,90}|\\."), 0..90)) { segments ->
                val name = segments.joinToString("/")
                val expected =
                    name.toByteArray(Charsets.UTF_8).size <= TarStreams.MAX_ENTRY_NAME_BYTES &&
                        segments.count { it != "." } <= TarStreams.MAX_ENTRY_SEGMENTS

                assertEquals(expected, TarStreams.entryNameWithinBounds(name), "name of ${name.length} chars, ${segments.size} segments")
            }
        }
    }

    @Test
    fun `the record refuses a path past its character bound and keeps what it holds`() {
        val record = BoundedPathSet(limit = 10, maxChars = 5)
        record.add(Path.of("ab"))
        record.add(Path.of("cd"))

        assertFalse(record.add(Path.of("ab")))
        assertThrows<RunnerTransferTooLargeException> { record.add(Path.of("efg")) }
        assertEquals(setOf(Path.of("ab"), Path.of("cd")), record.toSet())
    }
}
