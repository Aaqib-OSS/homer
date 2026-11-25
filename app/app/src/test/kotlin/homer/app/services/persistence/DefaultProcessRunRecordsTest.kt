package homer.app.services.persistence

import homer.app.exceptions.WorkspacePendingDeleteException
import homer.app.fixtures.PersistenceTestSupport
import homer.app.infra.ProcessJobRegistry
import homer.app.models.CostSource
import homer.app.models.NewRunRecord
import homer.app.models.PodPhase
import homer.app.models.ProcessStatus
import homer.app.models.RunStop
import homer.app.models.RunnerState
import homer.app.models.StopReason
import homer.app.models.StopStepsOutcome
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultProcessRunRecordsTest {
    private val repository = ProcessRepository(PersistenceTestSupport.tempDatabase(), ProcessJobRegistry())
    private val records = DefaultProcessRunRecords(repository)

    @Test
    fun `annotations land on the run's row`() =
        runTest {
            val id = repository.obtainProcessId(NewRunRecord("a"), 100).processId

            records.setTraceId(id, "0af7651916cd43dd8448eb211c80319c")
            records.setArtifactsDir(id, Path.of("/ws/1/artifacts"))
            records.setRunnerState(id) { RunnerState("local", podPhase = PodPhase.READY) }
            records.addCost(id, 0.25, CostSource.SESSION)

            val info = repository.getProcessInfo(id)
            assertEquals("0af7651916cd43dd8448eb211c80319c", info?.traceId)
            assertEquals(Path.of("/ws/1/artifacts"), info?.artifactsDir)
            assertEquals(PodPhase.READY, info?.runner?.podPhase)
            assertEquals(0.25, info?.costUsd)
            assertEquals(CostSource.SESSION.wire, info?.costSource)
        }

    @Test
    fun `a zero cost delta leaves the cost source unstamped`() =
        runTest {
            val id = repository.obtainProcessId(NewRunRecord("a"), 100).processId

            records.addCost(id, 0.0, CostSource.SESSION)

            assertEquals(CostSource.CLI.wire, repository.getProcessInfo(id)?.costSource)
        }

    @Test
    fun `a runtime tag past the ceiling is refused`() =
        runTest {
            val id = repository.obtainProcessId(NewRunRecord("a"), 100).processId

            assertTrue(records.addRuntimeTag(id, "k:1", maxTags = 1, fromInputsKeys = emptySet()).written)
            assertFalse(records.addRuntimeTag(id, "k:2", maxTags = 1, fromInputsKeys = emptySet()).written)
            assertEquals(listOf("k:1"), repository.getTags(id))
        }

    @Test
    fun `a failure reason reaches the run's row through the port`() =
        runTest {
            val id = repository.obtainProcessId(NewRunRecord("a"), 100).processId
            repository.transitionStatus(id, ProcessStatus.WORKING, 10, setOf(ProcessStatus.CREATED))
            repository.transitionStatus(id, ProcessStatus.FAILED, 20, setOf(ProcessStatus.WORKING))

            assertTrue(records.setFailureReason(id, "Lock skip (SKIP_CURRENT)", 999))

            val info = repository.getProcessInfo(id)
            assertEquals("Lock skip (SKIP_CURRENT)", info?.failureReason)
            assertEquals(999, info?.failureReasonRunId)
        }

    @Test
    fun `a registration refused by an in-flight delete claim throws through the port`() =
        runTest {
            val artifacts = Path.of("/ws/9/artifacts")
            repository.claimWorkspaceDelete(artifacts.toString(), evictedProcessId = 9, maxPending = 4, attemptToken = "a")
            val id = repository.obtainProcessId(NewRunRecord("a"), 100).processId

            val refused = assertFailsWith<WorkspacePendingDeleteException> { records.setArtifactsDir(id, artifacts) }

            assertEquals(id, refused.processId)
            assertNull(repository.getProcessInfo(id)?.artifactsDir)
        }

    @Test
    fun `a stop reaches the run's row through the port`() =
        runTest {
            val id = repository.obtainProcessId(NewRunRecord("a"), 100).processId
            val stop = RunStop(StopReason.STEP_TIMEOUT, 1, "Run tests", 1_700_000_000, StopStepsOutcome.FINISHED)

            records.setStop(id, stop)

            assertEquals(stop, repository.getProcessInfo(id)?.stop)
            // A run nobody stopped reads as not stopped, not as a stop with empty fields.
            assertNull(repository.getProcessInfo(repository.obtainProcessId(NewRunRecord("b"), 100).processId)?.stop)
        }
}
