package homer.app.services

import homer.app.exceptions.RunInputsTooLargeException
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.HookChainFixture
import homer.app.models.Agent
import homer.app.models.AgentHook
import homer.app.models.NewRunRecord
import homer.app.models.ProcessStatus
import homer.app.services.persistence.HookRepository
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The status-hook chain end to end over a real database, with no stand-in for any link: a status write
 * records its hook rows in its own transaction, then a real [HookService] fires them through
 * `HookFireService` into a start. See ADR-0107.
 */
class ProcessHookChainTest {
    private fun agent(
        name: String,
        vararg hooks: Pair<ProcessStatus, String>,
    ): Agent = AgentFixtures.simpleAgent(name = name).copy(hooks = hooks.map { AgentHook(it.first, it.second) })

    /** A declaring agent with [hooks], plus every agent they name, all loaded. */
    private fun chain(vararg hooks: Pair<ProcessStatus, String>): HookChainFixture =
        HookChainFixture(
            agents =
                buildMap {
                    put("declarer", agent("declarer", *hooks))
                    hooks.map { it.second }.distinct().forEach { put(it, agent(it)) }
                },
        )

    @Test
    fun `a run reaching CREATED, WORKING and FINISHED fires each status's hook once, in that order`() =
        runTest {
            val f =
                chain(
                    ProcessStatus.CREATED to "notifier-created",
                    ProcessStatus.WORKING to "notifier-working",
                    ProcessStatus.FINISHED to "notifier-finished",
                )

            val id = f.processManager.obtainProcessId(NewRunRecord("declarer", owner = "alice"))
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.WORKING), ProcessStatus.FINISHED)

            assertEquals(listOf("notifier-created", "notifier-working", "notifier-finished"), f.started)
        }

    @Test
    fun `the guarded CREATED re-assert a run's own start writes does not fire the CREATED hook again`() =
        runTest {
            // ProcessExecutor.runJob writes this right after the INSERT: the same status written twice is
            // one reach of it, so the hook fires exactly once. ADR-0107.
            val f = chain(ProcessStatus.CREATED to "notifier-created")

            val id = f.processManager.obtainProcessId(NewRunRecord("declarer", owner = "alice"))
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.CREATED), ProcessStatus.CREATED)

            assertEquals(listOf("notifier-created"), f.started)
        }

    @Test
    fun `a failed run fires its FAILED hook and not its FINISHED hook`() =
        runTest {
            val f =
                chain(
                    ProcessStatus.FINISHED to "notifier-finished",
                    ProcessStatus.FAILED to "notifier-failed",
                )

            val id = f.processManager.obtainProcessId(NewRunRecord("declarer", owner = "alice"))
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.WORKING), ProcessStatus.FAILED)

            assertEquals(listOf("notifier-failed"), f.started)
        }

    @Test
    fun `a killed run fires its KILLED hook and not its FINISHED hook`() =
        runTest {
            val f =
                chain(
                    ProcessStatus.FINISHED to "notifier-finished",
                    ProcessStatus.KILLED to "notifier-killed",
                )

            val id = f.processManager.obtainProcessId(NewRunRecord("declarer", owner = "alice"))
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.killProcess(id, "operator kill")

            assertEquals(listOf("notifier-killed"), f.started)
        }

    @Test
    fun `a status the declaring agent names no hook for starts nothing`() =
        runTest {
            val f = chain(ProcessStatus.FINISHED to "notifier-finished")

            val id = f.processManager.obtainProcessId(NewRunRecord("declarer", owner = "alice"))
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.setProcessStatus(id, ProcessStatus.FAILED)

            assertEquals(emptyList(), f.started)
        }

    @Test
    fun `a guard-rejected transition fires nothing`() =
        runTest {
            // A kill landed first: the FINISHED write is refused, and a refused write records no
            // hook row to fire. See ADR-0107.
            val f = chain(ProcessStatus.FINISHED to "notifier-finished")

            val id = f.processManager.obtainProcessId(NewRunRecord("declarer", owner = "alice"))
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.killProcess(id, "operator kill")
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.WORKING), ProcessStatus.FINISHED)

            assertEquals(emptyList(), f.started)
        }

    // CWE-770: a hook fire starts a run, so the target's per-run input cap refuses it like any other start.
    @Test
    fun `a hook fire refused over the input cap settles the hook and leaves the status write intact`() =
        runTest {
            val f = chain(ProcessStatus.FINISHED to "notifier-finished")
            coEvery { f.runStarter.start(any(), any(), any(), any()) } throws
                RunInputsTooLargeException(maxBytes = 64, actualBytes = 512)

            val id = f.processManager.obtainProcessId(NewRunRecord("declarer", owner = "alice"))
            f.processManager.setProcessStatus(id, ProcessStatus.WORKING)
            f.processManager.compareAndSetStatus(id, setOf(ProcessStatus.WORKING), ProcessStatus.FINISHED)

            assertEquals(ProcessStatus.FINISHED, f.processManager.getProcessStatus(id), "the status write still landed")
            assertEquals(emptyList(), f.started, "a refused fire starts nothing")
            val failed = HookRepository(f.db).list(HookStatus.FAILED)
            assertEquals(1, failed.size, "the refused hook settles FAILED, never retried")
            val hook = failed.single()
            val reason = hook.error.orEmpty()
            assertTrue(
                reason.contains("Run inputs exceed the server limit of 64 bytes"),
                "the hook names only the sizes: $reason",
            )
        }
}
