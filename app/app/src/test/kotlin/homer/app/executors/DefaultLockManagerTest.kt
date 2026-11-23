package homer.app.executors

import homer.app.exceptions.AnotherProcessStillActive
import homer.app.exceptions.MaxLockWaitersExceededException
import homer.app.models.OnKeyCollisionStrategy
import homer.app.services.ProcessManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The gate every test here passes: a no-op, since the agent's lock-wait bound is not under test. */
private val noOpGate = LockWaitGate { LockWaitTicket {} }

/**
 * The lock half of `Process.admit`'s admission — acquire, then kill the holder the collision
 * displaced — under a no-op gate: these tests exercise the lock manager, not the agent's
 * lock-wait bound.
 */
private suspend fun LockManager.acquireThenDisplace(
    lockName: String,
    onKeyCollisionStrategy: OnKeyCollisionStrategy,
    processID: Int,
) {
    killDisplaced(acquireLock(lockName, onKeyCollisionStrategy, processID, noOpGate), processID)
}

/** What a `SKIP_CURRENT` collision on [lockName] answers: the id of the key's current holder. */
private suspend fun LockManager.holderOf(
    lockName: String,
    processID: Int,
): String? =
    assertFailsWith<AnotherProcessStillActive> {
        acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processID)
    }.message

/**
 * Unit tests for DefaultLockManager
 * Tests lock acquisition, release, and collision strategies
 */
class DefaultLockManagerTest {
    private lateinit var lockManager: DefaultLockManager
    private lateinit var mockProcessManager: ProcessManager
    private lateinit var mockLogger: Logger

    @BeforeEach
    fun setup() {
        mockProcessManager = mockk(relaxed = true)
        mockLogger = mockk(relaxed = true)
        lockManager = DefaultLockManager(mockProcessManager, mockLogger)
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    // ========== Lock Acquisition Tests ==========

    @Test
    fun `acquireLock should acquire lock when no collision`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            // Should not throw
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)

            assertTrue(true)
        }

    @Test
    fun `acquireLock should acquire different locks for different names`() =
        runTest {
            val lockName1 = "lock-1"
            val lockName2 = "lock-2"
            val processId1 = 1
            val processId2 = 2

            lockManager.acquireThenDisplace(lockName1, OnKeyCollisionStrategy.WAIT, processId1)
            lockManager.acquireThenDisplace(lockName2, OnKeyCollisionStrategy.WAIT, processId2)

            assertTrue(true)
        }

    // ========== SKIP_CURRENT Strategy Tests ==========

    @Test
    fun `SKIP_CURRENT should throw exception when lock is taken`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            // First process acquires lock
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processId1)

            // Second process should throw exception
            val exception =
                assertFailsWith<AnotherProcessStillActive> {
                    lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processId2)
                }

            assertEquals("The process $processId1 is still active, stopping current", exception.message)
        }

    @Test
    fun `SKIP_CURRENT should acquire lock after release`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            // First process acquires and releases lock
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processId1)
            lockManager.releaseLock(lockName, processId1)

            // Second process should succeed
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, processId2)

            assertTrue(true)
        }

    // ========== KILL_OLD_RUN_CURRENT Strategy Tests ==========

    @Test
    fun `KILL_OLD_RUN_CURRENT should kill old process and acquire lock`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit

            // First process acquires lock
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId1)

            // Second process should kill first and acquire lock
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId2)

            coVerify { mockProcessManager.killProcess(processId1, "The new process $processId2 takes precedence") }
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT should not kill when no collision`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit

            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId)

            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT keeps a single holder between acquire and killDisplaced (CWE-667)`() =
        runTest {
            val lockName = "test-lock"
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            // The replacement is pending, not a second holder: the key still names run 1.
            val grant = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)
            assertEquals(1, grant.displacedProcessId)
            assertEquals("The process 1 is still active, stopping current", lockManager.holderOf(lockName, 3))

            // The deferred kill is the handover: only then does the key name run 2.
            lockManager.killDisplaced(grant, 2)
            lockManager.releaseLock(lockName, 1)
            assertEquals("The process 2 is still active, stopping current", lockManager.holderOf(lockName, 3))

            lockManager.releaseLock(lockName, 2)
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    @Test
    fun `a third KILL run supersedes the pending one and targets the live holder, not a doomed id (CWE-362)`() =
        runTest {
            val lockName = "test-lock"
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            val second = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)
            val third = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 3, noOpGate)

            assertEquals(1, second.displacedProcessId)
            assertEquals(1, third.displacedProcessId)

            // The superseded run 2 never becomes a holder, and the key names the run 3 installs.
            assertFailsWith<AnotherProcessStillActive> { lockManager.killDisplaced(second, 2) }
            lockManager.killDisplaced(third, 3)

            coVerify(exactly = 1) { mockProcessManager.killProcess(1, "The new process 3 takes precedence") }
            assertEquals("The process 3 is still active, stopping current", lockManager.holderOf(lockName, 4))
        }

    @Test
    fun `a superseded pending run is refused by killDisplaced and kills nobody`() =
        runTest {
            val lockName = "test-lock"
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            val superseded = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)
            lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 3, noOpGate)

            assertFailsWith<AnotherProcessStillActive> { lockManager.killDisplaced(superseded, 2) }

            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
        }

    @Test
    fun `a collision in the released window is decided against the pending replacement`() =
        runTest {
            val lockName = "test-lock"
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)
            val replacement = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)

            // Run 1 released while only the pending handover still holds the key.
            lockManager.releaseLock(lockName, 1)
            assertEquals("The process 2 is still active, stopping current", lockManager.holderOf(lockName, 3))

            // A later KILL run supersedes the pending one: run 2's handover is refused, run 3's installs.
            val third = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 3, noOpGate)
            assertEquals(2, third.displacedProcessId)
            assertFailsWith<AnotherProcessStillActive> { lockManager.killDisplaced(replacement, 2) }
            lockManager.killDisplaced(third, 3)

            coVerify(exactly = 1) { mockProcessManager.killProcess(2, "The new process 3 takes precedence") }
            assertEquals("The process 3 is still active, stopping current", lockManager.holderOf(lockName, 4))
        }

    @Test
    fun `a WAIT run queues behind a pending handover whose holder is already gone`() =
        runTest {
            val lockName = "test-lock"
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)
            val replacement = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)
            lockManager.releaseLock(lockName, 1)

            var waiterGranted = false
            val waiter =
                launch {
                    lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, 3)
                    waiterGranted = true
                }
            runCurrent()
            assertFalse(waiterGranted, "the waiter queues behind the pending replacement")

            // The pending run installs and releases: only then does the queued waiter get the key.
            lockManager.killDisplaced(replacement, 2)
            lockManager.releaseLock(lockName, 2)
            runCurrent()
            assertTrue(waiterGranted)

            waiter.join()
        }

    @Test
    fun `a superseded replacement releases without the not-a-holder warning`() =
        runTest {
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)
            lockManager.acquireLock("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)
            lockManager.acquireLock("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 3, noOpGate)

            // Run 2 was superseded while pending: its release is the designed refusal, not a bug.
            lockManager.releaseLock("test-lock", 2)

            verify(exactly = 0) { mockLogger.warn(match<String> { it.contains("not a holder") }, any(), any()) }
            // Run 1 still holds and run 3 is still the pending replacement.
            assertEquals("The process 1 is still active, stopping current", lockManager.holderOf("test-lock", 4))

            val fourth = lockManager.acquireLock("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 5, noOpGate)
            assertEquals(1, fourth.displacedProcessId)
            lockManager.killDisplaced(fourth, 5)
            lockManager.releaseLock("test-lock", 1)
        }

    @Test
    fun `the handover kill lands even when the replacing run is cancelled while it runs`() =
        runTest {
            val killEntered = CompletableDeferred<Unit>()
            val killRelease = CompletableDeferred<Unit>()
            val killReturned = CompletableDeferred<Unit>()
            coEvery { mockProcessManager.killProcess(any(), any()) } coAnswers {
                killEntered.complete(Unit)
                killRelease.await()
                killReturned.complete(Unit)
            }
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)
            val grant = lockManager.acquireLock("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)

            val handover = launch { lockManager.killDisplaced(grant, 2) }
            killEntered.await()
            handover.cancel()
            killRelease.complete(Unit)

            // The kill is non-cancellable: it completes although the replacing run was cancelled mid-kill.
            withTimeout(1.seconds) { killReturned.await() }
            handover.join()
        }

    @Test
    fun `a WAIT run promoted before its cancellation landed still frees the key`() =
        runTest {
            val lockName = "test-lock"
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, 1)

            val second = launch { lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, 2) }
            var thirdGranted = false
            val third =
                launch {
                    lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, 3)
                    thirdGranted = true
                }
            runCurrent()

            // Run 1's release promotes run 2 before run 2's own cancellation reaches the cleanup:
            // the key must not stay stranded on the dead run id, nor be lost on run 3.
            second.cancel()
            lockManager.releaseLock(lockName, 1)
            runCurrent()
            second.join()

            assertTrue(thirdGranted)
            assertEquals("The process 3 is still active, stopping current", lockManager.holderOf(lockName, 4))
            third.join()
        }

    @Test
    fun `a cancelled run that was the pending replacement still frees the key`() =
        runTest {
            val lockName = "test-lock"
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)
            // Run 2 registers as the pending replacement, then re-queues on the same key with WAIT and
            // is cancelled: the cleanup must clear the pending registration, or the key strands (CWE-667).
            lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)

            val requeued = launch { lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, 2) }
            runCurrent()
            requeued.cancelAndJoin()

            lockManager.releaseLock(lockName, 1)
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    @Test
    fun `releaseLock by the victim during a pending handover promotes no WAIT waiter`() =
        runTest {
            val lockName = "test-lock"
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)
            val replacement = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)

            var waiterGranted = false
            val waiter =
                launch {
                    lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, 3)
                    waiterGranted = true
                }
            runCurrent()

            // The victim's own release hands the key to its replacement, never to a waiter.
            lockManager.releaseLock(lockName, 1)
            runCurrent()
            assertFalse(waiterGranted)

            lockManager.killDisplaced(replacement, 2)
            lockManager.releaseLock(lockName, 2)
            runCurrent()
            assertTrue(waiterGranted)

            waiter.join()
        }

    @Test
    fun `releaseLock by a refused pending run leaves the victim holding and the key grantable next`() =
        runTest {
            val lockName = "test-lock"
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            // The replacement takes the key and is then refused its slot, so it gives the key back.
            lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)
            lockManager.releaseLock(lockName, 2)

            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
            assertEquals("The process 1 is still active, stopping current", lockManager.holderOf(lockName, 3))

            lockManager.releaseLock(lockName, 1)
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    @Test
    fun `a stale or foreign id in killDisplaced or releaseLock removes and installs nothing`() =
        runTest {
            val lockName = "test-lock"
            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)
            val replacement = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)

            assertFailsWith<AnotherProcessStillActive> { lockManager.killDisplaced(replacement, 99) }
            lockManager.releaseLock(lockName, 99)

            // Run 1 still holds the key and run 2 is still its pending replacement.
            assertEquals("The process 1 is still active, stopping current", lockManager.holderOf(lockName, 3))
            lockManager.killDisplaced(replacement, 2)
            coVerify(exactly = 1) { mockProcessManager.killProcess(1, "The new process 2 takes precedence") }
        }

    @Test
    fun `mixed WAIT and KILL runs on one key keep at most one holder`() =
        runTest {
            val lockName = "test-lock"
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, 1)
            val replacement = lockManager.acquireLock(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, noOpGate)

            var waiterGranted = false
            val waiter =
                launch {
                    lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, 3)
                    waiterGranted = true
                }
            runCurrent()
            assertEquals("The process 1 is still active, stopping current", lockManager.holderOf(lockName, 4))

            lockManager.killDisplaced(replacement, 2)
            lockManager.releaseLock(lockName, 1)
            runCurrent()
            assertFalse(waiterGranted)
            assertEquals("The process 2 is still active, stopping current", lockManager.holderOf(lockName, 4))

            lockManager.releaseLock(lockName, 2)
            runCurrent()
            assertTrue(waiterGranted)

            waiter.join()
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT kills the old holder once outside the mutex and the new process holds the lock`() =
        runTest {
            val killEntered = CompletableDeferred<Unit>()
            val killRelease = CompletableDeferred<Unit>()
            coEvery { mockProcessManager.killProcess(any(), any()) } coAnswers {
                killEntered.complete(Unit)
                killRelease.await()
            }
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            val taking = launch { lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2) }
            killEntered.await()
            // The kill is a database write: another lock must stay acquirable while it runs.
            withTimeout(1.seconds) { lockManager.acquireThenDisplace("other-lock", OnKeyCollisionStrategy.WAIT, 3) }
            killRelease.complete(Unit)
            taking.join()

            coVerify(exactly = 1) { mockProcessManager.killProcess(1, "The new process 2 takes precedence") }
            lockManager.releaseLock("test-lock", 1)
            val blocked =
                assertFailsWith<AnotherProcessStillActive> {
                    lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 4)
                }
            assertEquals("The process 2 is still active, stopping current", blocked.message)
        }

    @Test
    fun `KILL_OLD_RUN_CURRENT whose kill throws still leaves the new process holding the lock`() =
        runTest {
            coEvery { mockProcessManager.killProcess(any(), any()) } throws IllegalStateException("kill failed")
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            assertFailsWith<IllegalStateException> {
                lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2)
            }

            lockManager.releaseLock("test-lock", 1)
            val blocked =
                assertFailsWith<AnotherProcessStillActive> {
                    lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
                }
            assertEquals("The process 2 is still active, stopping current", blocked.message)
            lockManager.releaseLock("test-lock", 2)
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    // ========== Decision / kill split Tests ==========

    @Test
    fun `acquireLock returns the victim without killing it, and killDisplaced kills it once`() =
        runTest {
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            val grant =
                lockManager.acquireLock("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 2, LockWaitGate { LockWaitTicket {} })

            assertEquals(1, grant.displacedProcessId)
            // The decision is made under the mutex; the kill waits for the caller to admit the run.
            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }

            lockManager.killDisplaced(grant, 2)

            coVerify(exactly = 1) { mockProcessManager.killProcess(1, "The new process 2 takes precedence") }
        }

    @Test
    fun `acquireLock without a collision carries no victim to kill`() =
        runTest {
            val grant =
                lockManager.acquireLock("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1, LockWaitGate { LockWaitTicket {} })

            assertNull(grant.displacedProcessId)
            lockManager.killDisplaced(grant, 1)

            coVerify(exactly = 0) { mockProcessManager.killProcess(any(), any()) }
        }

    @Test
    fun `a collision that never enters the gate consumes no lock-wait place`() =
        runTest {
            // KILL_OLD_RUN_CURRENT does not wait, so it must not reserve a place in the bound.
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, 1)

            lockManager.acquireLock(
                "test-lock",
                OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT,
                2,
                LockWaitGate { throw AssertionError("the gate must not be entered") },
            )

            assertTrue(true)
        }

    // ========== WAIT Strategy Tests ==========

    @Test
    fun `WAIT should wait until lock is released`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2
            var process2Acquired = false

            // First process acquires lock
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId1)

            // Launch second process in background
            val job =
                launch {
                    lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId2)
                    process2Acquired = true
                    lockManager.releaseLock(lockName, processId2)
                }

            // Give second process time to try acquiring
            delay(100)
            assertFalse(process2Acquired, "Second process should be waiting")

            // Release lock
            lockManager.releaseLock(lockName, processId1)

            // Signal-based: waiter is woken immediately on release
            delay(50)
            assertTrue(process2Acquired, "Second process should have acquired lock")

            job.join()
        }

    @Test
    fun `WAIT should be cancelled when coroutine is cancelled`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            // First process acquires lock
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId1)

            // Launch second process in background
            val job =
                launch {
                    lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId2)
                }

            // Give second process time to start waiting
            delay(100)

            // Cancel the waiting job
            job.cancelAndJoin()

            // First process should still hold the lock
            // Verify by trying SKIP_CURRENT strategy
            assertFailsWith<AnotherProcessStillActive> {
                lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, 3)
            }
        }

    @Test
    fun `a WAIT cancelled while the mutex is contended still leaves the queue`() =
        runTest {
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.WAIT, 1)
            val waiter = launch { lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.WAIT, 2) }
            runCurrent()

            // Hold the manager's own mutex across the cancel, as a concurrent release would.
            val mutex =
                DefaultLockManager::class.java
                    .getDeclaredField("mutex")
                    .apply { isAccessible = true }
                    .get(lockManager) as Mutex
            mutex.lock()
            waiter.cancel()
            runCurrent()
            mutex.unlock()
            waiter.join()
            lockManager.releaseLock("test-lock", 1)

            // Had the dead waiter stayed queued, the release would have handed it the lock.
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    @Test
    fun `WAIT should acquire lock immediately when available`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1
            val startTime = System.currentTimeMillis()

            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)

            val elapsed = System.currentTimeMillis() - startTime
            assertTrue(elapsed < 100, "Lock acquisition should be immediate, took ${elapsed}ms")
        }

    // ========== Lock-wait gate Tests ==========

    @Test
    fun `a WAIT refused by the gate is not enqueued`() =
        runTest {
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.WAIT, 1)

            assertFailsWith<MaxLockWaitersExceededException> {
                lockManager.acquireLock(
                    "test-lock",
                    OnKeyCollisionStrategy.WAIT,
                    2,
                    LockWaitGate { throw MaxLockWaitersExceededException("agent", 1) },
                )
            }

            // Had the refused run been enqueued, this release would have handed it the lock.
            lockManager.releaseLock("test-lock", 1)
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    @Test
    fun `a granted WAIT frees its gate place`() =
        runTest {
            val open = AtomicInteger(0)
            val gate =
                LockWaitGate {
                    open.incrementAndGet()
                    LockWaitTicket { open.decrementAndGet() }
                }
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.WAIT, 1)

            val waiter = launch { lockManager.acquireLock("test-lock", OnKeyCollisionStrategy.WAIT, 2, gate) }
            runCurrent()
            assertEquals(1, open.get(), "the waiting run holds its place")

            lockManager.releaseLock("test-lock", 1)
            waiter.join()

            assertEquals(0, open.get(), "the granted run frees its place")
        }

    @Test
    fun `a cancelled WAIT frees its gate place and leaves no queue entry`() =
        runTest {
            val open = AtomicInteger(0)
            val gate =
                LockWaitGate {
                    open.incrementAndGet()
                    LockWaitTicket { open.decrementAndGet() }
                }
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.WAIT, 1)

            val waiter = launch { lockManager.acquireLock("test-lock", OnKeyCollisionStrategy.WAIT, 2, gate) }
            runCurrent()
            assertEquals(1, open.get())

            waiter.cancelAndJoin()

            assertEquals(0, open.get(), "the cancelled run frees its place")
            // The dead waiter left the queue: the release hands the lock to nobody.
            lockManager.releaseLock("test-lock", 1)
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.SKIP_CURRENT, 3)
        }

    // ========== Lock Release Tests ==========

    @Test
    fun `releaseLock should remove process from lock set`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)

            // Verify lock is available by acquiring it again
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)

            assertTrue(true)
        }

    @Test
    fun `releaseLock should remove lock key when set is empty`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)

            // Acquire lock again - should succeed immediately since lock was removed
            val startTime = System.currentTimeMillis()
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
            val elapsed = System.currentTimeMillis() - startTime

            assertTrue(elapsed < 100, "Lock should have been removed and reacquired immediately")
        }

    @Test
    fun `releaseLock should handle non-existent lock gracefully`() =
        runTest {
            val lockName = "non-existent-lock"
            val processId = 1

            // Should not throw
            lockManager.releaseLock(lockName, processId)

            assertTrue(true)
        }

    @Test
    fun `releaseLock should handle non-existent processId gracefully`() =
        runTest {
            val lockName = "test-lock"
            val processId1 = 1
            val processId2 = 2

            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId1)

            // Release with wrong processId should not throw
            lockManager.releaseLock(lockName, processId2)

            // Original process should still hold lock
            assertFailsWith<AnotherProcessStillActive> {
                lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, 3)
            }
        }

    @Test
    fun `releaseLock warns when the lock is unknown`() =
        runTest {
            lockManager.releaseLock("ghost-lock", 1)

            verify(exactly = 1) { mockLogger.warn(match<String> { it.contains("unknown lock") }, "ghost-lock", 1) }
        }

    @Test
    fun `releaseLock warns when the releasing process does not hold the lock`() =
        runTest {
            lockManager.acquireThenDisplace("test-lock", OnKeyCollisionStrategy.WAIT, 1)

            lockManager.releaseLock("test-lock", 2)

            verify(exactly = 1) { mockLogger.warn(match<String> { it.contains("not a holder") }, 2, "test-lock") }
        }

    @Test
    fun `releaseLock should work in NonCancellable context`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)

            // Verify lock was released by trying to acquire it again
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)

            // Cleanup
            lockManager.releaseLock(lockName, processId)

            assertTrue(true)
        }

    // ========== Concurrent Access Tests ==========

    @Test
    fun `multiple processes should wait in queue for same lock`() =
        runTest {
            val lockName = "test-lock"
            val processIds = listOf(1, 2, 3, 4, 5)
            val acquisitionOrder = mutableListOf<Int>()

            // First process acquires lock
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processIds[0])
            acquisitionOrder.add(processIds[0])

            // Launch other processes
            coroutineScope {
                processIds.drop(1).forEach { processId ->
                    launch {
                        lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
                        synchronized(acquisitionOrder) {
                            acquisitionOrder.add(processId)
                        }
                        lockManager.releaseLock(lockName, processId)
                    }
                }

                // Release locks sequentially
                delay(100)
                processIds.forEach { processId ->
                    lockManager.releaseLock(lockName, processId)
                    delay(50)
                }
            }

            delay(500)
            assertEquals(processIds.size, acquisitionOrder.size)
        }

    @Test
    fun `different locks should not interfere with each other`() =
        runTest {
            val results = mutableListOf<Pair<String, Int>>()

            coroutineScope {
                // Launch processes for different locks concurrently
                repeat(10) { i ->
                    launch {
                        val lockName = "lock-${i % 3}"
                        val processId = i
                        lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
                        synchronized(results) {
                            results.add(lockName to processId)
                        }
                        delay(10)
                        lockManager.releaseLock(lockName, processId)
                    }
                }
            }

            delay(500)
            assertEquals(10, results.size)
        }

    @Test
    fun `concurrent SKIP_CURRENT requests should handle race conditions`() =
        runTest {
            val lockName = "test-lock"
            val exceptionCounts = mutableListOf<Int>()

            coroutineScope {
                repeat(5) { i ->
                    launch {
                        try {
                            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.SKIP_CURRENT, i)
                            delay(100)
                            lockManager.releaseLock(lockName, i)
                        } catch (e: AnotherProcessStillActive) {
                            synchronized(exceptionCounts) {
                                exceptionCounts.add(1)
                            }
                        }
                    }
                }
            }

            delay(500)
            // At least one should have succeeded, others threw exceptions
            assertTrue(exceptionCounts.size >= 4, "Expected at least 4 exceptions, got ${exceptionCounts.size}")
        }

    @Test
    fun `concurrent KILL_OLD_RUN_CURRENT requests should work correctly`() =
        runTest {
            val lockName = "test-lock"
            val processIds = listOf(1, 2, 3, 4, 5)

            coEvery { mockProcessManager.killProcess(any(), any()) } returns Unit

            coroutineScope {
                processIds.forEach { processId ->
                    launch {
                        delay(processId * 10L) // Stagger requests
                        lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.KILL_OLD_RUN_CURRENT, processId)
                        delay(50)
                        lockManager.releaseLock(lockName, processId)
                    }
                }
            }

            delay(500)
            // Should have killed all but the last process
            coVerify(atLeast = processIds.size - 1) { mockProcessManager.killProcess(any(), any()) }
        }

    // ========== Edge Cases ==========

    @Test
    fun `same process can release and reacquire lock`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            // Acquire, release, and reacquire
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)
            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)

            assertTrue(true)
        }

    @Test
    fun `lock with very long name should work`() =
        runTest {
            val lockName = "a".repeat(1000)
            val processId = 1

            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)

            assertTrue(true)
        }

    @Test
    fun `multiple releases of same lock should be idempotent`() =
        runTest {
            val lockName = "test-lock"
            val processId = 1

            lockManager.acquireThenDisplace(lockName, OnKeyCollisionStrategy.WAIT, processId)
            lockManager.releaseLock(lockName, processId)
            lockManager.releaseLock(lockName, processId)
            lockManager.releaseLock(lockName, processId)

            assertTrue(true)
        }
}
