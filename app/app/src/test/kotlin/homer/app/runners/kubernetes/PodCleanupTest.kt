package homer.app.runners.kubernetes

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.kubernetes.client.custom.V1Patch
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * keepWithTtl tests run against a real CoreV1Api short-circuited at the OkHttp
 * layer (see interceptedCoreV1Api): the patch's Content-Type header is the bug
 * surface (415 from the apiserver), and a mocked CoreV1Api can't see it.
 */
class PodCleanupTest {
    private fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    @Test
    fun `delete swallows 404`() {
        val api = mockk<CoreV1Api>(relaxed = true)
        val req =
            mockk<CoreV1Api.APIdeleteNamespacedPodRequest>(relaxed = true) {
                every { gracePeriodSeconds(any()) } returns this
                every { body(any()) } returns this
                every { execute() } throws ApiException(404, "not found")
            }
        every { api.deleteNamespacedPod(any(), any()) } returns req

        PodCleanup.delete(api, "homer", "pod-1") // must not throw
    }

    @Test
    fun `keepWithTtl sends the TTL patch as a JSON Patch, not application json`() {
        val seen = mutableListOf<Request>()

        val kept = PodCleanup.keepWithTtl(interceptedCoreV1Api(seen), "homer", "pod-1", ttlSeconds = 900, existingDeadlineSeconds = 86_400)

        assertTrue(kept)
        assertEquals(listOf("PATCH"), seen.map { it.method })
        val patch = seen.single()
        assertTrue(patch.url.encodedPath.endsWith("/namespaces/homer/pods/pod-1"), patch.url.toString())
        assertEquals(V1Patch.PATCH_FORMAT_JSON_PATCH, patch.body?.contentType()?.toString())
        val body = patch.bodyText()
        assertTrue(body.contains("\"/spec/activeDeadlineSeconds\",\"value\":900"), body)
        assertTrue(body.contains("\"/metadata/labels/homer.app~1kept\",\"value\":\"true\""), body)
        // Both ops must be "add": activeDeadlineSeconds is never set, and "replace" on a missing field 422s the whole
        // atomic patch, voiding the kept label.
        assertFalse(body.contains("\"op\":\"replace\""), body)
        assertEquals(2, Regex("\"op\":\"add\"").findAll(body).count(), body)
    }

    @Test
    fun `keepWithTtl deletes the pod when the apiserver rejects the patch`() {
        val seen = mutableListOf<Request>()
        val api = interceptedCoreV1Api(seen) { if (it.method == "PATCH") 415 else 200 }

        assertFalse(PodCleanup.keepWithTtl(api, "homer", "pod-1", ttlSeconds = 900, existingDeadlineSeconds = 86_400)) // must not throw

        val delete = seen.single { it.method == "DELETE" }
        assertTrue(delete.url.encodedPath.endsWith("/namespaces/homer/pods/pod-1"), delete.url.toString())
    }

    @Test
    fun `keepWithTtl does not fall back to delete when the patch 404s`() {
        val seen = mutableListOf<Request>()
        val api = interceptedCoreV1Api(seen) { if (it.method == "PATCH") 404 else 200 }

        assertFalse(
            PodCleanup.keepWithTtl(api, "homer", "pod-1", ttlSeconds = 900, existingDeadlineSeconds = 86_400),
            "a vanished pod is not kept",
        ) // must not throw

        assertEquals(emptyList(), seen.filter { it.method == "DELETE" })
    }

    private fun keepPatchBody(
        ttl: Long,
        existing: Long,
        seen: MutableList<Request> = mutableListOf(),
    ): String {
        PodCleanup.keepWithTtl(interceptedCoreV1Api(seen), "homer", "pod-1", ttl, existing)
        return seen.single { it.method == "PATCH" }.bodyText()
    }

    private fun deadlineOf(body: String): Long? =
        Regex("\"/spec/activeDeadlineSeconds\",\"value\":(\\d+)")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toLong()

    @Test
    fun `keepWithTtl lowers a later existing deadline`() {
        val body = keepPatchBody(3600, 10800)
        assertEquals(3600L, deadlineOf(body), body)
        assertTrue(body.contains("homer.app~1kept"), body)
    }

    @Test
    fun `keepWithTtl leaves an earlier or equal deadline and only labels`() {
        for (existing in listOf(3600L, 60L)) {
            val seen = mutableListOf<Request>()
            val body = keepPatchBody(3600, existing, seen)
            assertFalse(body.contains("activeDeadlineSeconds"), body)
            assertTrue(body.contains("\"/metadata/labels/homer.app~1kept\",\"value\":\"true\""), body)
            assertEquals(emptyList(), seen.filter { it.method == "DELETE" })
        }
    }

    @Test
    fun `keepWithTtl never raises an existing deadline`() =
        runTest {
            checkAll(Arb.int(1..100_000), Arb.int(1..100_000)) { max, ttl ->
                val deadline = deadlineOf(keepPatchBody(ttl.toLong(), max.toLong()))
                assertTrue(deadline == null || deadline <= max, "max=$max ttl=$ttl deadline=$deadline")
            }
        }
}
