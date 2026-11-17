package homer.app.services

import homer.app.EnvConstants
import homer.app.exceptions.TooManyPendingContinuationsException
import homer.app.exceptions.ValidationException
import homer.app.models.CreateJoinRequest
import homer.app.models.CreateJoinResponse
import homer.app.models.CreateRunRequest
import homer.app.models.CreateRunResponse
import homer.app.models.JoinSubSpec
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The start path's seam with [ContinuationService]: which of its calls the starter makes, in what
 * order, with what arguments, and who kills a sub the registration then refuses. The end-to-end
 * behaviour of the same paths is in [ContinuationServiceTest]; this pins the split between the two.
 */
class ContinuationStartServiceTest {
    private val continuations = mockk<ContinuationService>(relaxed = true)
    private val dispatchService = mockk<DispatchService>(relaxed = true)
    private val processManager = mockk<ProcessManager>(relaxed = true)
    private val starts =
        ContinuationStartService(continuations, dispatchService, processManager, LoggerFactory.getLogger("t"))

    private fun runRequest(
        continueAgent: String? = "h",
        continueThread: String? = null,
        continueParams: Map<String, String> = emptyMap(),
        resultJsonPath: String? = null,
        ttlSec: Int? = null,
    ) = CreateRunRequest(
        agent = "sub",
        params = mapOf("subParam" to "1"),
        continueAgent = continueAgent,
        continueThread = continueThread,
        continueParams = continueParams,
        resultJsonPath = resultJsonPath,
        ttlSec = ttlSec,
    )

    private fun joinRequest(vararg subs: JoinSubSpec) = CreateJoinRequest(subs = subs.toList(), continueAgent = "h")

    @Test
    fun `a run with no continue agent is a plain dispatch that asks the service for nothing`() =
        runTest {
            coEvery { dispatchService.dispatchNow(7, "sub", mapOf("subParam" to "1")) } returns 42

            val response = starts.startWithContinuation(7, runRequest(continueAgent = null))

            assertEquals(CreateRunResponse(processId = 42), response)
            coVerify(exactly = 0) { continuations.validate(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { continuations.create(any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a continuation run is validated before the sub starts, and the started sub is what gets registered`() =
        runTest {
            coEvery { dispatchService.dispatchNow(7, "sub", mapOf("subParam" to "1")) } returns 42
            coEvery { continuations.create(any(), any(), any(), any(), any(), any(), any()) } returns 5

            val response =
                starts.startWithContinuation(
                    7,
                    runRequest(
                        continueThread = "thread-9",
                        continueParams = mapOf("K" to "v"),
                        resultJsonPath = "$.result",
                        ttlSec = 60,
                    ),
                )

            assertEquals(CreateRunResponse(processId = 42, continuationId = 5), response)
            coVerifyOrder {
                continuations.validate(7, "h", "HMR_LG_RESUME", any(), 60)
                dispatchService.dispatchNow(7, "sub", mapOf("subParam" to "1"))
            }
            val params = slot<Map<String, String>>()
            val transformer = slot<ResultEnvParam>()
            coVerify {
                continuations.create(7, 42, "h", capture(params), "HMR_LG_RESUME", capture(transformer), 60)
            }
            // The thread id rides with continueParams to the resumed parent, not to the sub.
            assertEquals(mapOf(EnvConstants.LG_THREAD to "thread-9", "K" to "v"), params.captured)
            assertEquals(
                ResultEnvParam(SrcType.STD_OUT, "HMR_LG_RESUME", ResultTransformer.JsonPath("$.result")),
                transformer.captured,
            )
        }

    @Test
    fun `a refused continuation starts no sub`() =
        runTest {
            coEvery { continuations.validate(any(), any(), any(), any(), any()) } throws
                ValidationException("ttlSec must be > 0; got 0", "ttlSec")

            assertFailsWith<ValidationException> { starts.startWithContinuation(7, runRequest(ttlSec = 0)) }

            coVerify(exactly = 0) { dispatchService.dispatchNow(any(), any(), any()) }
        }

    @Test
    fun `a refused registration kills the sub that was already started`() =
        runTest {
            coEvery { dispatchService.dispatchNow(7, "sub", any()) } returns 42
            coEvery { continuations.create(any(), any(), any(), any(), any(), any(), any()) } throws
                TooManyPendingContinuationsException(1)

            assertFailsWith<TooManyPendingContinuationsException> { starts.startWithContinuation(7, runRequest()) }

            coVerify {
                processManager.killProcess(42, "Never registered: the callback request that started it failed")
            }
        }

    @Test
    fun `a cancelled request kills the sub that was already started`() =
        runTest {
            coEvery { dispatchService.dispatchNow(7, "sub", any()) } returns 42
            coEvery { continuations.create(any(), any(), any(), any(), any(), any(), any()) } throws
                CancellationException("client went away")

            assertFailsWith<CancellationException> { starts.startWithContinuation(7, runRequest()) }

            coVerify { processManager.killProcess(42, any()) }
        }

    @Test
    fun `a join validates the join and every sub before it starts the first, and registers one watch per sub`() =
        runTest {
            coEvery { dispatchService.dispatchNow(7, "a", any()) } returns 10
            coEvery { dispatchService.dispatchNow(7, "b", any()) } returns 11
            coEvery { continuations.createJoin(any(), any(), any(), any(), any(), any()) } returns 12

            val response =
                starts.startJoin(
                    7,
                    joinRequest(
                        JoinSubSpec("a", interruptId = "i1"),
                        JoinSubSpec("b", resultJsonPath = "$.r", interruptId = "i2"),
                    ),
                )

            assertEquals(CreateJoinResponse(processIds = listOf(10, 11), continuationId = 12), response)
            coVerifyOrder {
                continuations.validateJoin(7, "h", listOf("a", "b"), any(), any(), any())
                dispatchService.validateDispatchNow(7, "a", any())
                dispatchService.validateDispatchNow(7, "b", any())
                dispatchService.dispatchNow(7, "a", any())
            }
            val watches = slot<List<JoinWatch>>()
            coVerify { continuations.createJoin(7, capture(watches), "h", any(), any(), any()) }
            assertEquals(
                listOf(
                    JoinWatch(watchedProcessId = 10, interruptId = "i1"),
                    JoinWatch(
                        watchedProcessId = 11,
                        interruptId = "i2",
                        resultTransformer =
                            ResultEnvParam(SrcType.STD_OUT, "HMR_LG_RESUME", ResultTransformer.JsonPath("$.r")),
                    ),
                ),
                watches.captured,
            )
        }

    @Test
    fun `a refused sub starts nothing, and a sub started before a later refusal is killed`() =
        runTest {
            coEvery { dispatchService.validateDispatchNow(7, "b", any()) } throws ValidationException("nope", "agent")

            assertFailsWith<ValidationException> {
                starts.startJoin(7, joinRequest(JoinSubSpec("a", interruptId = "i1"), JoinSubSpec("b", interruptId = "i2")))
            }
            coVerify(exactly = 0) { dispatchService.dispatchNow(any(), any(), any()) }

            coEvery { dispatchService.validateDispatchNow(7, "b", any()) } returns Unit
            coEvery { dispatchService.dispatchNow(7, "a", any()) } returns 10
            coEvery { dispatchService.dispatchNow(7, "b", any()) } throws IllegalStateException("no capacity")

            assertFailsWith<IllegalStateException> {
                starts.startJoin(7, joinRequest(JoinSubSpec("a", interruptId = "i1"), JoinSubSpec("b", interruptId = "i2")))
            }
            coVerify { processManager.killProcess(10, any()) }
        }
}
