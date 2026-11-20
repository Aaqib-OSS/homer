package homer.app

import homer.app.models.ExecutionInfo
import homer.app.models.LangfuseConfig
import homer.app.models.PodPhase
import homer.app.models.PodTermination
import homer.app.models.PodTerminationResponse
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.RunnerState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The [toResponse] mappings of `ResponseMapping.kt`: the Langfuse trace deep link and the
 * runner-alias disclosure rule (CWE-200).
 */
class ResponseMappingTest {
    // ---- Langfuse trace deep link ----

    private fun processWithTrace(traceId: String?) =
        ProcessInfo(
            id = 1,
            status = ProcessStatus.FINISHED,
            agentName = "test-agent",
            traceId = traceId,
        )

    @Test
    fun `toResponse builds a Langfuse trace URL from serverUrl, projectId and traceId`() {
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://lf.example.test/", projectId = "p1"),
            )

        assertEquals(
            "https://lf.example.test/project/p1/traces/0af7651916cd43dd8448eb211c80319c",
            response.langfuseTraceUrl,
        )
    }

    @Test
    fun `toResponse omits the trace URL when projectId is not configured`() {
        // Langfuse derives the project from the API keys and never sends it back over
        // OTLP, so without the configured id there is no URL that resolves.
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://lf.example.test"),
            )

        assertNull(response.langfuseTraceUrl)
    }

    @Test
    fun `toResponse omits the trace URL for a run that has no trace id`() {
        val response =
            processWithTrace(null).toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://lf.example.test", projectId = "p1"),
            )

        assertNull(response.langfuseTraceUrl)
    }

    @Test
    fun `toResponse strips credentials from the trace URL it returns`() {
        // The field goes to every caller who can see the process, and on to the browser
        // as an external link. An operator credential in serverUrl must not ride along
        // — the same policy the log sink already applied.
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://user:pa55w0rd@lf.example.test", projectId = "p1"),
            )

        assertEquals(
            "https://lf.example.test/project/p1/traces/0af7651916cd43dd8448eb211c80319c",
            response.langfuseTraceUrl,
        )
    }

    @Test
    fun `toResponse strips a query token and fragment from the trace URL`() {
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "https://lf.example.test?token=s3cret#f", projectId = "p1"),
            )

        assertEquals(
            "https://lf.example.test/project/p1/traces/0af7651916cd43dd8448eb211c80319c",
            response.langfuseTraceUrl,
        )
    }

    @Test
    fun `toResponse keeps a base path and an explicit port in the trace URL`() {
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "http://localhost:3000/langfuse", projectId = "p1"),
            )

        assertEquals(
            "http://localhost:3000/langfuse/project/p1/traces/0af7651916cd43dd8448eb211c80319c",
            response.langfuseTraceUrl,
        )
    }

    @Test
    fun `toResponse omits the trace URL when serverUrl will not parse`() {
        // No link beats a guessed one: an unparseable value is where deciding which
        // part is sensitive is least safe.
        val response =
            processWithTrace("0af7651916cd43dd8448eb211c80319c").toResponse(
                agent = null,
                langfuse = LangfuseConfig(serverUrl = "http://ho st:/// not a uri", projectId = "p1"),
            )

        assertNull(response.langfuseTraceUrl)
    }

    // ---- Runner alias disclosure (CWE-200) ----

    private fun processWithRunner(alias: String) =
        ProcessInfo(
            id = 1,
            status = ProcessStatus.FINISHED,
            agentName = "test-agent",
            runner =
                RunnerState(
                    alias = alias,
                    podPhase = PodPhase.READY,
                ),
        )

    @Test
    fun `RunnerState toResponse withholds the alias by default but always emits the type`() {
        // Fail-closed default: a caller that does not pass `aliasVisible = true`
        // (a non-admin) gets the generic backend kind, never the operator-chosen alias.
        val response =
            RunnerState(alias = "k8s-prod-eu", podPhase = PodPhase.READY)
                .toResponse(type = "kubernetes", aliasVisible = false)

        assertNull(response.alias, "a non-admin response must not carry the raw alias")
        assertEquals("kubernetes", response.type)
    }

    @Test
    fun `RunnerState termination maps to the response`() {
        val mapped =
            RunnerState(alias = "k", termination = PodTermination("DeadlineExceeded", "late"))
                .toResponse(type = "kubernetes", aliasVisible = false)

        assertEquals(PodTerminationResponse("DeadlineExceeded", "late"), mapped.termination)
        assertNull(RunnerState(alias = "k").toResponse(type = "kubernetes", aliasVisible = false).termination)
    }

    @Test
    fun `RunnerState jobName maps to the response`() {
        val mapped =
            RunnerState(alias = "k", jobName = "homer-7-abcdef")
                .toResponse(type = "kubernetes", aliasVisible = false)

        assertEquals("homer-7-abcdef", mapped.jobName)
        assertNull(mapped.podName)
        assertNull(RunnerState(alias = "k").toResponse(type = "kubernetes", aliasVisible = false).jobName)
    }

    @Test
    fun `RunnerState toResponse keeps the alias for an admin caller`() {
        val response =
            RunnerState(alias = "k8s-prod-eu", podPhase = PodPhase.READY)
                .toResponse(type = "kubernetes", aliasVisible = true)

        assertEquals("k8s-prod-eu", response.alias)
        assertEquals("kubernetes", response.type)
    }

    @Test
    fun `ProcessInfo toResponse withholds the runner alias for a non-admin and emits the type`() {
        val response =
            processWithRunner("ssh-build-01").toResponse(
                agent = null,
                aliasVisible = false,
                runnerType = "ssh",
            )

        val runner = assertNotNull(response.runner)
        assertNull(runner.alias, "a non-admin row must not leak the runner alias")
        assertEquals("ssh", runner.type)
    }

    @Test
    fun `ProcessInfo toResponse keeps the runner alias for an admin`() {
        val response =
            processWithRunner("ssh-build-01").toResponse(
                agent = null,
                aliasVisible = true,
                runnerType = "ssh",
            )

        assertEquals("ssh-build-01", response.runner?.alias)
        assertEquals("ssh", response.runner?.type)
    }

    @Test
    fun `ProcessInfo toResponse has no runner block when the run never got a session`() {
        val response =
            processWithTrace(null).toResponse(
                agent = null,
                aliasVisible = true,
                runnerType = null,
            )

        assertNull(response.runner)
    }

    @Test
    fun `ProcessInfo toResponse maps each execution's command index`() {
        val response =
            ProcessInfo(
                id = 1,
                status = ProcessStatus.FINISHED,
                agentName = "test-agent",
                executions =
                    listOf(
                        ExecutionInfo("l", 0, 1, 0, null, null, null, commandIndex = 2),
                        ExecutionInfo("l", 0, 1, 0, null, null, null),
                    ),
            ).toResponse(agent = null)

        assertEquals(2, response.executions[0].commandIndex)
        assertNull(response.executions[1].commandIndex, "a row written before the field existed carries no index")
    }
}
