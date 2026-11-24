package homer.app.routes

import homer.app.apiJson
import homer.app.configureStatusPages
import homer.app.executors.Process
import homer.app.executors.ProcessExecutor
import homer.app.executors.ProcessFactory
import homer.app.executors.RunMessageExpiry
import homer.app.fixtures.AgentFixtures
import homer.app.fixtures.PersistenceTestSupport
import homer.app.fixtures.TestFixtures
import homer.app.infra.AgentInputResolver
import homer.app.infra.LANGFUSE_TRACE_NAME
import homer.app.infra.LANGFUSE_TRACE_TAGS
import homer.app.infra.LangfuseTraceAttributesSpanProcessor
import homer.app.infra.TelemetryService
import homer.app.models.AgentTelemetry
import homer.app.models.Param
import homer.app.models.ParamType
import homer.app.models.ProcessCreationResult
import homer.app.models.ProcessInfo
import homer.app.models.ProcessStatus
import homer.app.models.Tags
import homer.app.models.TelemetryExport
import homer.app.services.Authorizer
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Retry builds its own root span outside AgentRunStarter, so it stamps the Langfuse trace name and `traceTag`
 * subject tags itself, else a retried run is invisible to the tag/name filters. Resume and debug share the
 * pattern in RunHandlers.kt.
 */
class RetryTraceAttributesTest {
    @Test
    fun `retry root span carries the agent name and traceTag subject tags`() =
        testApplication {
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(
                        inputs = listOf(Param(paramName = "mr", envName = "MR_IID")),
                        telemetry = AgentTelemetry(traceTags = listOf("mr")),
                    )
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "4211"),
                    owner = "alice",
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.OK, response.status)
            val name = slot<String>()
            val tags = slot<List<String>>()
            verify { span.setAttribute(AttributeKey.stringKey("langfuse.trace.name"), capture(name)) }
            verify { span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), capture(tags)) }
            assertEquals("triage-decision", name.captured)
            assertEquals(listOf("retry", "mr:4211"), tags.captured)
        }

    @Test
    fun `retry of a muted agent produces no exported span`() =
        testApplication {
            // Retry builds its own root span, so `telemetry.export = OFF` must apply to it too: it goes through
            // startAgentRootSpan, the real tracer is never asked for a span and the mock below stays untouched.
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(
                        inputs = listOf(Param(paramName = "mr", envName = "MR_IID")),
                        telemetry = AgentTelemetry(export = TelemetryExport.OFF, traceTags = listOf("mr")),
                    )
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "4211"),
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            // Muting changes telemetry only — the retry itself still runs.
            assertEquals(HttpStatusCode.OK, response.status)
            verify(exactly = 0) { span.setAllAttributes(any<Attributes>()) }
            coVerify { mockProcessExecutor.execute(any(), any()) }
        }

    @Test
    fun `retry does not tag a value that was secret for the source run`() =
        testApplication {
            // Drift: MR_IID was `secret: true` when process 42 ran and is now `secret: false` and in traceTags; agent
            // load checks only current flags, so the source run's snapshot must keep it out of Langfuse (ADR-0052).
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(
                        inputs = listOf(Param(paramName = "mr", envName = "MR_IID", secret = false)),
                        telemetry = AgentTelemetry(traceTags = listOf("mr")),
                    )
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "tok-historical-secret"),
                    secretInputKeys = setOf("MR_IID"),
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.OK, response.status)
            val tags = slot<List<String>>()
            verify { span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), capture(tags)) }
            assertEquals(
                listOf("retry"),
                tags.captured,
                "a value that was secret for the source run must not become a tag",
            )
            // The same union also rides onto the new row, so the retried run's own
            // /processes/{id} response keeps masking the value it replayed.
            verify {
                mockProcessFactory.create(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    extraSecretInputKeys = setOf("MR_IID"),
                )
            }
        }

    @Test
    fun `retry keeps the source run's parent on the new row and on its root span`() =
        testApplication {
            // A replay is part of the flow that caused the source run (ADR-0058): the new
            // row and the trace both point at the source's parent, not at the source.
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), parentProcessId = 7) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(7L, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
            assertEquals(42L, attributes.captured.get(AttributeKey.longKey("retry.of_process_id")))
            assertRunIdentity(attributes.captured, "retry")
        }

    @Test
    fun `resume keeps the source run's parent on the new row and on its root span`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/resume").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), parentProcessId = 7) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(7L, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
            assertRunIdentity(attributes.captured, "resume")
        }

    @Test
    fun `retry of a root run stays a root run`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(id = 42, status = ProcessStatus.FAILED, agentName = "triage-decision")
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), parentProcessId = null) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(null, attributes.captured.get(AttributeKey.longKey("parent.process.id")))
            assertRunIdentity(attributes.captured, "retry")
        }

    @Test
    fun `retry keeps the source run's root on the new row and on its root span`() =
        testApplication {
            // A replay stays in the source run's flow (ADR-0058): the new row carries the source's root, not its id,
            // else every retry would start a new flow.
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                    rootProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            // The root-visibility gate resolves root 7: a live row, visible to the
            // disabled authorizer, so the derived id stays 7.
            val rootRow =
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "triage-decision",
                )
            val mockProcessManager = mockk<ProcessManager>(relaxed = true)
            coEvery { mockProcessManager.getProcessInfo(7) } returns rootRow

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockProcessManager,
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), rootProcessId = 7) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(7L, attributes.captured.get(AttributeKey.longKey("root.process.id")))
            assertRunIdentity(attributes.captured, "retry")
        }

    @Test
    fun `resume keeps the source run's root on the new row and on its root span`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                    rootProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            // Same root-row stub as the retry counterpart: the gate must resolve root 7.
            val rootRow =
                ProcessInfo(
                    id = 7,
                    status = ProcessStatus.FINISHED,
                    agentName = "triage-decision",
                )
            val mockProcessManager = mockk<ProcessManager>(relaxed = true)
            coEvery { mockProcessManager.getProcessInfo(7) } returns rootRow

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockProcessManager,
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/resume").status)

            verify { mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), rootProcessId = 7) }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(7L, attributes.captured.get(AttributeKey.longKey("root.process.id")))
            assertRunIdentity(attributes.captured, "resume")
        }

    @Test
    fun `retry of a run whose flow root the caller cannot see is stamped rootless`() =
        testApplication {
            // CWE-200: canRetry admits a caller holding only RUN on the source's agent, whose flow root may belong to
            // somebody else; stamping it would publish that id through the new row, its env and its span.
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                    rootProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            val hiddenRootAuthorizer =
                mockk<Authorizer> {
                    every { canSee(any(), any(), any()) } returns false
                }

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = hiddenRootAuthorizer,
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            // No root on the row either: the replay starts a flow of its own rather
            // than carrying an id its caller was never entitled to see.
            verify {
                mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), rootProcessId = null)
            }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
            assertRunIdentity(attributes.captured, "retry")
        }

    @Test
    fun `resume of a run whose flow root the caller cannot see is stamped rootless`() =
        testApplication {
            // Same denial path as retry: the resuming caller may control the source run
            // without being able to see the flow root it points at.
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    parentProcessId = 7,
                    rootProcessId = 7,
                )
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            val hiddenRootAuthorizer =
                mockk<Authorizer> {
                    every { canSee(any(), any(), any()) } returns false
                }

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = hiddenRootAuthorizer,
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/resume").status)

            verify {
                mockProcessFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), rootProcessId = null)
            }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
            assertRunIdentity(attributes.captured, "resume")
        }

    @Test
    fun `retry of a root run stays rootless`() =
        testApplication {
            val agent = AgentFixtures.simpleAgent(name = "triage-decision")
            val sourceProcess =
                ProcessInfo(id = 42, status = ProcessStatus.FAILED, agentName = "triage-decision")
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            val mockProcessFactory = mockk<ProcessFactory>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockProcessFactory,
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            // No root on the row either: a replay of a flow's first run starts a flow.
            verify {
                mockProcessFactory.create(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    parentProcessId = null,
                    rootProcessId = null,
                )
            }
            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertEquals(null, attributes.captured.get(AttributeKey.longKey("root.process.id")))
            assertRunIdentity(attributes.captured, "retry")
        }

    // ========== the stamp lands before the run starts, on all three paths (ADR-0050) ==========

    /**
     * A provider wired the way `OtelTelemetryService` wires one — the copy processor ahead of the
     * exporter — so a span the run itself starts can be inspected as Langfuse would see it.
     */
    private class CollectingExporter : SpanExporter {
        val spans = mutableListOf<SpanData>()

        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            this.spans.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }

    private fun realTelemetry(exporter: CollectingExporter): TelemetryService {
        val provider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(LangfuseTraceAttributesSpanProcessor())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        return object : TelemetryService {
            override val tracer: Tracer = provider.get("test")
        }
    }

    /** An executor standing in for the run: it starts one command step under the run's context. */
    private fun executorStartingAChildSpan(
        telemetry: TelemetryService,
        spanName: String = "command_execution_1",
    ): ProcessExecutor {
        val executor = mockk<ProcessExecutor>()
        coEvery { executor.execute(any(), any()) } answers {
            telemetry.tracer
                .spanBuilder(spanName)
                .setParent(secondArg<Context>())
                .startSpan()
                .end()
            ProcessCreationResult(processId = 99)
        }
        return executor
    }

    private fun retryAgent() =
        AgentFixtures
            .simpleAgent(name = "triage-decision")
            .copy(
                inputs = listOf(Param(paramName = "mr", envName = "MR_IID")),
                tags = Tags(fromInputs = listOf("mr")),
            )

    /**
     * The identity attributes every `startedRunAttributes` map carries, whichever of the three
     * paths built it: a refactor slip — dropping one, swapping one path's source for another's —
     * would otherwise pass the whole suite silently.
     */
    private fun assertRunIdentity(
        attributes: Attributes,
        source: String,
    ) {
        assertEquals(99L, attributes.get(AttributeKey.longKey("process.id")))
        assertEquals("test-server", attributes.get(AttributeKey.stringKey("server.id")))
        assertEquals("triage-decision", attributes.get(AttributeKey.stringKey("agent.name")))
        assertEquals(source, attributes.get(AttributeKey.stringKey("request.source")))
    }

    @Test
    fun `retry stamps the trace name and tags before the run starts`() =
        testApplication {
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "4211"),
                )

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = retryAgent(),
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            coVerifyOrder {
                span.setAttribute(AttributeKey.stringKey("langfuse.trace.name"), "triage-decision")
                span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), listOf("retry", "mr:4211"))
                mockProcessExecutor.execute(any(), any())
            }
        }

    @Test
    fun `resume stamps the trace name and tags before the run starts`() =
        testApplication {
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "4211"),
                )

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = retryAgent(),
                            threadId = "thread-1",
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/resume").status)

            coVerifyOrder {
                span.setAttribute(AttributeKey.stringKey("langfuse.trace.name"), "triage-decision")
                span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), listOf("resume", "mr:4211"))
                mockProcessExecutor.execute(any(), any())
            }
        }

    @Test
    fun `debug stamps the trace name and tags before the run starts`() =
        testApplication {
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/agents/{name}/debug") {
                        handleAgentDebug(
                            serverId = "test-server",
                            call = call,
                            agent = retryAgent(),
                            agentInputResolver = AgentInputResolver(),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response =
                client.post("/api/v1/agents/triage-decision/debug") {
                    contentType(ContentType.Application.Json)
                    // Debug inputs are keyed by paramName, not envName (`AgentInputResolver`).
                    setBody("""{"commandIndex":0,"queryParams":{"mr":"4211"}}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            coVerifyOrder {
                span.setAttribute(AttributeKey.stringKey("langfuse.trace.name"), "triage-decision")
                span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), listOf("debug", "mr:4211"))
                mockProcessExecutor.execute(any(), any())
            }
        }

    @Test
    fun `debug root span carries the run's identity attributes`() =
        testApplication {
            val span = TestFixtures.mockSpan()
            val mockProcessExecutor = mockk<ProcessExecutor>(relaxed = true)
            coEvery { mockProcessExecutor.execute(any(), any()) } returns ProcessCreationResult(processId = 99)

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/agents/{name}/debug") {
                        handleAgentDebug(
                            serverId = "test-server",
                            call = call,
                            agent = retryAgent(),
                            agentInputResolver = AgentInputResolver(),
                            processExecutor = mockProcessExecutor,
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = TestFixtures.mockTelemetryService(span),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response =
                client.post("/api/v1/agents/triage-decision/debug") {
                    contentType(ContentType.Application.Json)
                    // Debug inputs are keyed by paramName, not envName (`AgentInputResolver`).
                    setBody("""{"commandIndex":0,"queryParams":{"mr":"4211"}}""")
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val attributes = slot<Attributes>()
            verify { span.setAllAttributes(capture(attributes)) }
            assertRunIdentity(attributes.captured, "debug")
            assertEquals(0L, attributes.captured.get(AttributeKey.longKey("debug.command_index")))
        }

    @Test
    fun `a command step started inside the retried run inherits the name and tags`() =
        testApplication {
            // A span started inside `execute` picks both attributes up at its start, which the mock-based call-order
            // tests cannot reach and which puts a step or LLM generation inside a Langfuse v4 name/tags filter.
            val exporter = CollectingExporter()
            val telemetry = realTelemetry(exporter)
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "4211"),
                )

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = retryAgent(),
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = executorStartingAChildSpan(telemetry),
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = telemetry,
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            val root = exporter.spans.single { it.name == "agent_retry_request" }
            val child = exporter.spans.single { it.name == "command_execution_1" }
            assertEquals("triage-decision", root.attributes.get(LANGFUSE_TRACE_NAME))
            assertEquals(listOf("retry", "mr:4211"), child.attributes.get(LANGFUSE_TRACE_TAGS))
            assertEquals("triage-decision", child.attributes.get(LANGFUSE_TRACE_NAME))
        }

    @Test
    fun `a value secret for the source run stays off the tags of the retried run and its spans`() =
        testApplication {
            // Drift: MR_IID was secret when process 42 ran and is now `secret: false` in tags.fromInputs; the union
            // with the source snapshot must hold on every exported span, not only the root.
            val exporter = CollectingExporter()
            val telemetry = realTelemetry(exporter)
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to "tok-historical-secret"),
                    secretInputKeys = setOf("MR_IID"),
                )

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = retryAgent(),
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = executorStartingAChildSpan(telemetry),
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = telemetry,
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            val exported = exporter.spans.associateBy { it.name }
            for (name in listOf("agent_retry_request", "command_execution_1")) {
                assertEquals(
                    listOf("retry"),
                    exported.getValue(name).attributes.get(LANGFUSE_TRACE_TAGS),
                    "a historical secret leaked into the tags of $name",
                )
            }
        }

    @Test
    fun `a muted agent's replay exports neither a span nor a header`() =
        testApplication {
            val exporter = CollectingExporter()
            val telemetry = realTelemetry(exporter)
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(telemetry = AgentTelemetry(export = TelemetryExport.OFF))
            val sourceProcess =
                ProcessInfo(id = 42, status = ProcessStatus.FAILED, agentName = "triage-decision")

            application {
                install(ContentNegotiation) { json(apiJson) }
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = mockk(relaxed = true),
                            processExecutor = executorStartingAChildSpan(telemetry),
                            processFactory = mockk<ProcessFactory>(relaxed = true),
                            telemetryService = telemetry,
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, client.post("/api/v1/processes/42/retry").status)

            assertTrue(exporter.spans.isEmpty(), "exported: ${exporter.spans.map { it.name }}")
        }

    /**
     * CWE-770: retry replays the source run's stored inputs and builds its process directly, so the bound
     * is the executor's, not the HTTP body limit. The start path is real, so the refusal shows in the table.
     */
    @Test
    fun `a retry whose stored inputs exceed maxInputBytes is refused with 413 and writes no row`() =
        testApplication {
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(
                        inputs = listOf(Param(paramName = "mr", envName = "MR_IID", type = ParamType.STR, required = false)),
                    )
            val secret = "s3cret-".repeat(64)
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to secret),
                    owner = "alice",
                )
            val processManager = PersistenceTestSupport.processManager()
            val processExecutor =
                ProcessExecutor(
                    telemetryService = TestFixtures.mockTelemetryService(),
                    processManager = processManager,
                    runInputBytes = processManager,
                    maxInputBytes = 128,
                    questionsService = mockk<QuestionsService>(relaxed = true),
                    runMessages = mockk<RunMessageExpiry>(relaxed = true),
                    coroutineScope = CoroutineScope(Dispatchers.Default + Job()),
                    logger = TestFixtures.mockLogger(),
                )
            // The factory is stubbed only to hand the executor the oversized stored map the retry resolved;
            // the executor, its cap and the process table are real, so the refusal is the one under test.
            val oversizedProcess = mockk<Process>(relaxed = true)
            every { oversizedProcess.queryParams } returns mapOf("MR_IID" to secret)
            val processFactory = mockk<ProcessFactory>(relaxed = true)
            every {
                processFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns oversizedProcess

            application {
                install(ContentNegotiation) { json(apiJson) }
                configureStatusPages()
                routing {
                    post("/api/v1/processes/{id}/retry") {
                        handleRetry(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = processManager,
                            processExecutor = processExecutor,
                            processFactory = processFactory,
                            telemetryService = TestFixtures.mockTelemetryService(),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/retry")

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(-1, runBlocking { processManager.lastProcessId() }, "the refused retry writes no run row")
            val body = response.bodyAsText()
            assertFalse(body.contains(secret), "the 413 must not carry the stored input value")
            assertFalse(body.contains("MR_IID"), "the 413 must not name the input")
        }

    /**
     * CWE-770: resume replays the source run's stored inputs and builds its process directly, like retry —
     * it is the only path that replays inputs stored under an older, larger cap, so the bound is the
     * executor's, not the HTTP body limit. The start path is real, so the refusal shows in the table.
     */
    @Test
    fun `a resume whose stored inputs exceed maxInputBytes is refused with 413 and writes no row`() =
        testApplication {
            val agent =
                AgentFixtures
                    .simpleAgent(name = "triage-decision")
                    .copy(
                        inputs = listOf(Param(paramName = "mr", envName = "MR_IID", type = ParamType.STR, required = false)),
                    )
            val secret = "s3cret-".repeat(64)
            val sourceProcess =
                ProcessInfo(
                    id = 42,
                    status = ProcessStatus.FAILED,
                    agentName = "triage-decision",
                    inputParams = mapOf("MR_IID" to secret),
                    owner = "alice",
                )
            val processManager = PersistenceTestSupport.processManager()
            val processExecutor =
                ProcessExecutor(
                    telemetryService = TestFixtures.mockTelemetryService(),
                    processManager = processManager,
                    runInputBytes = processManager,
                    maxInputBytes = 128,
                    questionsService = mockk<QuestionsService>(relaxed = true),
                    runMessages = mockk<RunMessageExpiry>(relaxed = true),
                    coroutineScope = CoroutineScope(Dispatchers.Default + Job()),
                    logger = TestFixtures.mockLogger(),
                )
            // The factory is stubbed only to hand the executor the oversized stored map the resume resolved;
            // the executor, its cap and the process table are real, so the refusal is the one under test.
            val oversizedProcess = mockk<Process>(relaxed = true)
            every { oversizedProcess.queryParams } returns mapOf("MR_IID" to secret)
            val processFactory = mockk<ProcessFactory>(relaxed = true)
            every {
                processFactory.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            } returns oversizedProcess

            application {
                install(ContentNegotiation) { json(apiJson) }
                configureStatusPages()
                routing {
                    post("/api/v1/processes/{id}/resume") {
                        handleResume(
                            serverId = "test-server",
                            call = call,
                            sourceProcess = sourceProcess,
                            agent = agent,
                            threadId = "thread-1",
                            authorizer = Authorizer(emptyMap(), emptyMap(), enabled = false),
                            processManager = processManager,
                            processExecutor = processExecutor,
                            processFactory = processFactory,
                            telemetryService = TestFixtures.mockTelemetryService(),
                            costMeter = mockk(relaxed = true),
                            agentInputResolver = AgentInputResolver(),
                            maintenanceGate = mockk(relaxed = true),
                        )
                    }
                }
            }

            val response = client.post("/api/v1/processes/42/resume")

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertEquals(-1, runBlocking { processManager.lastProcessId() }, "the refused resume writes no run row")
            val body = response.bodyAsText()
            assertFalse(body.contains(secret), "the 413 must not carry the stored input value")
            assertFalse(body.contains("MR_IID"), "the 413 must not name the input")
        }
}
