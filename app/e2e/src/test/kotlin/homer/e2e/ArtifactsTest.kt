package homer.e2e

import homer.e2e.support.ArtifactListing
import homer.e2e.support.Awaits
import homer.e2e.support.E2eTestBase
import homer.e2e.support.HomerApi
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactsTest : E2eTestBase() {
    @Test
    fun `written artifacts are listed and fetchable with status headers`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-writer")
            assertEquals("FINISHED", Awaits.awaitTerminal(processId).status)

            val listing: ArtifactListing = HomerApi.decode(HomerApi.artifactsList(processId))
            val paths = listing.files.map { it.path }.toSet()
            assertTrue(
                paths.containsAll(setOf("hello.txt", "nested/dir/deep.txt", "big.txt", "cmd_0.out")),
                "artifact listing incomplete: $paths",
            )

            val hello = HomerApi.artifact(processId, "hello.txt")
            assertEquals(HttpStatusCode.OK, hello.status)
            assertTrue(hello.bodyAsText().contains("hello artifact"))
            assertEquals("FINISHED", hello.headers["X-Process-Status"])
            assertEquals("true", hello.headers["X-File-Complete"])

            val nested = HomerApi.artifact(processId, "nested/dir/deep.txt")
            assertEquals(HttpStatusCode.OK, nested.status)
            assertTrue(nested.bodyAsText().contains("nested content"))
        }

    @Test
    fun `artifact over the configured cap is rejected with 413`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-writer")
            Awaits.awaitTerminal(processId)

            // big.txt is 128 KiB; the fixture config caps artifacts at 64 KiB.
            val response = HomerApi.artifact(processId, "big.txt")
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        }

    @Test
    fun `artifact growing past the cap while downloaded never serves more than the cap`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-grower")

            // staged.txt appears once growing.txt holds its staged 32 KiB; the fixture appends past the cap only
            // later (CWE-770). The artifacts dir exists only once the workspace is prepared: a 404 means not yet.
            Awaits.awaitNotNull("staged.txt to appear in the artifact listing") {
                val response = HomerApi.artifactsList(processId)
                if (response.status == HttpStatusCode.NotFound) return@awaitNotNull null
                val listing: ArtifactListing = HomerApi.decode(response)
                listing.files.find { it.path == "staged.txt" }
            }

            val response = HomerApi.artifact(processId, "growing.txt")

            // Drain the body slower than the fixture appends, so the copy runs while the file grows.
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(4096)
            var total = 0
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                total += read
                delay(100)
            }

            // Still appending, so the body must be bounded: cut off at the cap (or a 413 if the file
            // already passed the cap at open), never the file's full multi-cap size.
            assertTrue(
                response.status == HttpStatusCode.OK || response.status == HttpStatusCode.PayloadTooLarge,
                "expected 200 or 413 for a growing artifact, got ${response.status}",
            )
            if (response.status == HttpStatusCode.OK) {
                assertEquals("false", response.headers["X-File-Complete"], "the run must still be appending during the download")
                assertTrue(total <= 65_536, "served $total bytes; the fixture config caps artifacts at 64 KiB")
            }

            // The fixture appended ~5 MiB after the staged 32 KiB, so the bound held while the file grew past it.
            assertEquals("FINISHED", Awaits.awaitTerminal(processId, timeoutMs = 120_000).status)
            val finalListing: ArtifactListing = HomerApi.decode(HomerApi.artifactsList(processId))
            val grown = finalListing.files.first { it.path == "growing.txt" }
            assertTrue(grown.size > 4_000_000, "fixture must append well past the cap; grew only to ${grown.size} bytes")
        }

    @Test
    fun `path traversal and unknown artifacts are rejected`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-writer")
            Awaits.awaitTerminal(processId)

            assertEquals(HttpStatusCode.BadRequest, HomerApi.artifact(processId, "../workdir/anything").status)
            assertEquals(HttpStatusCode.NotFound, HomerApi.artifact(processId, "no-such-file.txt").status)
        }

    @Test
    fun `cleanAfterFinish true removes the workdir between runs of a shared workspace`() =
        runBlocking {
            val firstId = HomerApi.startRun("workspace-clean")
            Awaits.awaitTerminal(firstId)
            val secondId = HomerApi.startRun("workspace-clean")
            Awaits.awaitTerminal(secondId)

            // Both runs share a fixed workspace path; the marker written by run 1
            // must have been cleaned away before run 2 probed for it.
            assertTrue(HomerApi.artifact(firstId, "cmd_0.out").bodyAsText().contains("marker=absent"))
            assertTrue(
                HomerApi.artifact(secondId, "cmd_0.out").bodyAsText().contains("marker=absent"),
                "cleanAfterFinish=true must delete the workdir after run 1",
            )
        }

    @Test
    fun `artifactNameTemplate renders custom per-command artifact stems`() =
        runBlocking {
            val processId = HomerApi.startRun("artifact-name-template")
            assertEquals("FINISHED", Awaits.awaitTerminal(processId).status)

            val listing: ArtifactListing = HomerApi.decode(HomerApi.artifactsList(processId))
            val paths = listing.files.map { it.path }.toSet()
            assertTrue(
                paths.containsAll(setOf("step_0.out", "step_0.err", "step_1.out", "step_1.err")),
                "artifact listing incomplete: $paths",
            )
            assertTrue(
                paths.none { it.startsWith("cmd_") },
                "default cmd_N names must not appear alongside a custom artifactNameTemplate: $paths",
            )

            assertTrue(HomerApi.artifact(processId, "step_0.out").bodyAsText().contains("first stdout"))
            assertTrue(HomerApi.artifact(processId, "step_0.err").bodyAsText().contains("first stderr"))
            assertTrue(HomerApi.artifact(processId, "step_1.out").bodyAsText().contains("second stdout"))
            assertTrue(HomerApi.artifact(processId, "step_1.err").bodyAsText().contains("second stderr"))
        }

    @Test
    fun `cleanAfterFinish false keeps the workdir between runs of a shared workspace`() =
        runBlocking {
            val firstId = HomerApi.startRun("workspace-keep")
            Awaits.awaitTerminal(firstId)
            val secondId = HomerApi.startRun("workspace-keep")
            Awaits.awaitTerminal(secondId)

            assertTrue(
                HomerApi.artifact(secondId, "cmd_0.out").bodyAsText().contains("marker=present"),
                "cleanAfterFinish=false must keep run 1's marker for run 2",
            )
        }
}
