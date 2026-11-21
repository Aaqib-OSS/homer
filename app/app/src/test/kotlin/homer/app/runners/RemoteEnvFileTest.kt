package homer.app.runners

import homer.app.models.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RemoteEnvFileTest {
    private val result = { exit: Int -> CommandResult(exitCode = exit, stdOut = Path.of("o"), stdErr = Path.of("e")) }

    @Test
    fun `render names a fresh file in the dir per call and carries the env only in its content`() {
        val a = RemoteEnvFile.render("/w/.homer/env", mapOf("TOKEN" to "s3cr3t-Ab9-env-value"))
        val b = RemoteEnvFile.render("/w/.homer/env", mapOf("TOKEN" to "s3cr3t-Ab9-env-value"))

        assertTrue(a.path.matches(Regex("/w/\\.homer/env/[0-9a-f-]{36}\\.env")), a.path)
        assertNotEquals(a.path, b.path)
        assertFalse(a.path.contains("s3cr3t"))
        assertEquals(RemoteShell.envFileContent(a.path, mapOf("TOKEN" to "s3cr3t-Ab9-env-value")), a.content.toString(Charsets.UTF_8))
    }

    @Test
    fun `a successful step removes nothing`() =
        runTest {
            val removed = mutableListOf<String>()
            RemoteEnvFile.removingUnlessSucceeded("/w/e.env", { removed += it }) { result(0) }
            assertEquals(emptyList(), removed)
        }

    @Test
    fun `a non-zero exit, a failure and a cancellation each remove the file`() =
        runTest {
            val removed = mutableListOf<String>()
            RemoteEnvFile.removingUnlessSucceeded("/w/a.env", { removed += it }) { result(2) }
            assertThrows<IllegalStateException> {
                RemoteEnvFile.removingUnlessSucceeded("/w/b.env", { removed += it }) { error("exec refused") }
            }
            val job = launch { RemoteEnvFile.removingUnlessSucceeded("/w/c.env", { removed += it }) { awaitCancellation() } }
            yield()
            job.cancel()
            job.join()
            assertEquals(listOf("/w/a.env", "/w/b.env", "/w/c.env"), removed)
        }

    @Test
    fun `a failed removal never replaces the step's outcome`() =
        runTest {
            val outcome = RemoteEnvFile.removingUnlessSucceeded("/w/e.env", { error("pod gone") }) { result(3) }
            assertEquals(3, outcome.exitCode)
            val thrown =
                assertThrows<CancellationException> {
                    RemoteEnvFile.removingUnlessSucceeded("/w/e.env", { error("pod gone") }) { throw CancellationException("stop") }
                }
            assertEquals("stop", thrown.message)
        }

    @Test
    fun `no env file means no removal`() =
        runTest {
            var calls = 0
            RemoteEnvFile.removingUnlessSucceeded(null, { calls++ }) { result(1) }
            assertEquals(0, calls)
        }
}
