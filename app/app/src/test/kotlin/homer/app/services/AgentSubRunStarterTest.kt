package homer.app.services

import homer.app.fixtures.AgentFixtures
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.RunStartRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentSubRunStarterTest {
    private val target = AgentFixtures.simpleAgent(name = "target")
    private val agentsService = mockk<AgentsService> { every { agents } returns mapOf("target" to target) }
    private val authorizer = mockk<Authorizer>()
    private val processManager = mockk<ProcessManager>()
    private val agentRunStarter = mockk<AgentRunStarter>()
    private var providerCalls = 0
    private val request =
        RunStartRequest(
            agent = target,
            invocationChain = listOf("caller"),
            source = "agent",
            ancestorLockKeys = listOf("lock"),
            owner = "alice",
            parentProcessId = 7,
            rootProcessId = 3,
            resolveInputs = { emptyMap() },
        )
    private val subRuns =
        AgentSubRunStarter(
            agentRunStarter = {
                providerCalls++
                agentRunStarter
            },
            agentsService = agentsService,
            authorizer = authorizer,
            processManager = processManager,
        )

    @Test
    fun `resolves the target among the loaded agents`() {
        assertSame(target, subRuns.agent("target"))
        assertNull(subRuns.agent("missing"))
    }

    @Test
    fun `a hop the authorizer denies is denied`() {
        every { authorizer.canDispatchHop("alice", "caller", "target") } returns false

        assertFalse(subRuns.canDispatchHop("alice", "caller", "target"))
    }

    @Test
    fun `a flow root the principal cannot see is dropped`() =
        runTest {
            coEvery { processManager.getProcessInfo(7) } returns
                ProcessInfo(id = 7, status = ProcessStatus.WORKING, agentName = "caller", rootProcessId = 3)
            coEvery { processManager.getProcessInfo(3) } returns
                ProcessInfo(id = 3, status = ProcessStatus.WORKING, agentName = "root", owner = "bob")
            every { authorizer.canSee("alice", "bob", "root") } returns false

            assertNull(subRuns.visibleOriginRootProcessId(7, "alice"))
        }

    @Test
    fun `starts through the run starter resolved at call time`() =
        runTest {
            coEvery { agentRunStarter.start(request, any(), any(), any()) } returns 42
            assertEquals(0, providerCalls)

            val processId = subRuns.start(request)

            assertEquals(42, processId)
            assertEquals(1, providerCalls)
            coVerify(exactly = 1) { agentRunStarter.start(any(), any(), any(), any()) }
        }

    @Test
    fun `a hop marks its parent as server-derived so a live parent admits it`() =
        runTest {
            // The hop's parent is the executing run, never a caller-supplied id: the start may
            // bypass the maintenance gate while that run is still WORKING. See ADR-0092.
            coEvery { agentRunStarter.start(request, any(), any(), serverDerivedParent = true) } returns 42

            assertEquals(42, subRuns.start(request))

            coVerify(exactly = 1) { agentRunStarter.start(any(), any(), any(), serverDerivedParent = true) }
        }
}
