package homer.app.runners

import homer.app.exceptions.RunnerTransferTooLargeException
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TarStreams.extract]'s bound on the bytes a tar's entries expand to, sparse holes included (ADR-0047 rule 4). */
class BoundedTarInputStreamTest {
    @TempDir
    lateinit var tmp: Path

    private var created = 0

    private fun destination(): Path = tmp.resolve("dst${created++}").createDirectories()

    /** One PAX record, `<length> key=value\n`, whose length counts its own digits. */
    private fun paxRecord(
        key: String,
        value: String,
    ): String {
        val body = " $key=$value\n"
        var length = body.length + 1
        while (length.toString().length + body.length != length) length = length.toString().length + body.length
        return "$length$body"
    }

    /**
     * A gzip'd tar of PAX 0.0 sparse entries, one per [holes] value: entry `f<i>` is a hole of that many bytes followed
     * by one data byte `A`, so the archive carries one byte per entry and the entry expands to `hole + 1` bytes.
     */
    private fun sparseTar(holes: List<Long>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GzipCompressorOutputStream(bytes)).use { out ->
            holes.forEachIndexed { i, hole ->
                val pax =
                    (
                        paxRecord("GNU.sparse.size", "${hole + 1}") + paxRecord("GNU.sparse.numblocks", "1") +
                            paxRecord("GNU.sparse.offset", "$hole") + paxRecord("GNU.sparse.numbytes", "1")
                    ).toByteArray()
                val header = TarArchiveEntry("PaxHeaders/f$i", TarConstants.LF_PAX_EXTENDED_HEADER_LC)
                header.size = pax.size.toLong()
                out.putArchiveEntry(header)
                out.write(pax)
                out.closeArchiveEntry()
                out.putArchiveEntry(TarArchiveEntry("f$i").apply { size = 1 })
                out.write('A'.code)
                out.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun extract(
        tar: ByteArray,
        dst: Path,
        bound: Long,
        mode: DownloadMode = DownloadMode.OVERWRITE,
    ) = TarStreams.extract(ByteArrayInputStream(tar), dst, mode, remoteIsDir = true, fileName = ".", sourceLabel = "test", bound)

    private fun writtenBytes(dst: Path): Long =
        Files.walk(dst).use { walk -> walk.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }

    @Test
    fun `a sparse entry expanding past the cap is refused before it writes past it (CWE-409)`() {
        val bound = 64L * 1024
        val tar = sparseTar(listOf(16 * bound))
        assertTrue(tar.size < 1024, "the fixture must be small compressed, got ${tar.size}")
        val dst = destination()

        assertThrows<RunnerTransferTooLargeException> { extract(tar, dst, bound) }

        val written = writtenBytes(dst)
        assertTrue(written <= bound, "extraction stopped at the bound, wrote $written bytes")
    }

    @Test
    fun `a sparse entry skipped over an existing file still counts toward the cap (CWE-400)`() {
        val bound = 64L * 1024
        val dst = destination()
        dst.resolve("f0").writeText("kept")

        assertThrows<RunnerTransferTooLargeException> { extract(sparseTar(listOf(16 * bound)), dst, bound, DownloadMode.OVERLAY) }

        assertEquals("kept", dst.resolve("f0").readText())
    }

    @Test
    fun `a sparse entry within the cap extracts to its logical size`() {
        val dst = destination()

        extract(sparseTar(listOf(1000)), dst, TarStreams.DEFAULT_MAX_EXTRACTED_BYTES)

        assertContentEquals(ByteArray(1000) + 'A'.code.toByte(), dst.resolve("f0").readBytes())
    }

    /** The cap also counts the PAX header bytes the parser reads, under one record per entry here. */
    private val paxHeaderSlack = 512L

    @Test
    fun `the bytes a transfer's entries write never exceed the cap, and an expansion past it is refused`() {
        runTest {
            checkAll(100, Arb.list(Arb.long(0L..32 * 1024), 1..4), Arb.long(16L * 1024..96 * 1024)) { holes, bound ->
                val dst = destination()
                val expanded = holes.sumOf { it + 1 }

                val outcome = runCatching { extract(sparseTar(holes), dst, bound) }

                val written = writtenBytes(dst)
                assertTrue(written <= bound, "holes $holes under bound $bound wrote $written bytes")
                if (expanded > bound) assertTrue(outcome.isFailure, "holes $holes past bound $bound were extracted")
                val clearlyUnder = expanded + paxHeaderSlack * holes.size <= bound
                if (clearlyUnder) assertTrue(outcome.isSuccess, "holes $holes under bound $bound: $outcome")
                outcome.exceptionOrNull()?.let { assertTrue(it is RunnerTransferTooLargeException, "unexpected failure $it") }
            }
        }
    }
}
