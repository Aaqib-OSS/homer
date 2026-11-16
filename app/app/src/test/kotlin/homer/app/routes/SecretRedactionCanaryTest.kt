package homer.app.routes

import homer.app.ResetDrainingExtension
import homer.app.configureServer
import homer.app.fixtures.eventually
import homer.app.infra.PasswordVerifier
import homer.app.infra.SecretRedactor
import homer.app.infra.WorkspaceConfigFactory
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.LangfuseConfig
import homer.app.models.McpServerConfig
import homer.app.models.NewRunRecord
import homer.app.models.OpenTelemetryExport
import homer.app.models.PasswordUserConfig
import homer.app.models.ProcessStatus
import homer.app.models.RateLimitConfig
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.TagValidation
import homer.app.models.TokenConfig
import homer.app.services.AgentsService
import homer.app.services.ContinuationService
import homer.app.services.DispatchService
import homer.app.services.ProcessEvent
import homer.app.services.ProcessEventBroadcaster
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readLine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.koin.ktor.ext.get
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import homer.app.models.Workspace as WorkspaceAgentConfig

/**
 * The redaction canary: one run started with a canary secret input and a canary `@SYSENV`-backed
 * global, then every response body and SSE frame that run can produce, read back and asserted
 * canary-free.
 *
 * The payloads come from [CANARY_ROUTES], one row per registered route, so a new response type is
 * either covered here or fails `` `every registered route has exactly one canary row and every row
 * names a registered route` `` (app/CLAUDE.md, "Adding a new HTTP endpoint"). Removing a redaction
 * call must fail this test: the two sites it exercises per request are `redactInputParams` (the
 * run's stored inputs, `ResponseMapping.kt`) and `redactStoredParams` (a dispatch's and a
 * continuation's stored params, `DispatchContinuationRoutes.kt`).
 *
 * Two canaries, because they enter the run by different routes and are masked by different
 * mechanisms: [secretCanary] is a `secret: true` input value, [sysEnvCanary] is a `globalEnv` value
 * the operator declared `@SYSENV`-backed, so its name is in every run's secret screen.
 *
 * The run is seeded in two phases, and the second one is load-bearing. A `tags.fromInputs` tag may
 * only name a *declared, non-secret* input (AGENT.md), so the tag that carries the canary exists
 * only while its input is non-secret — and while it is, `redactInputParams` would echo that input's
 * value in the clear. Rewriting `agent.yaml` to make the input `secret: true` and reloading is the
 * documented "flipped to `secret: true` after the run" case: the stored snapshot then masks the
 * original secret input, the current flag masks the flipped one, and `screenTags` drops the tag that
 * contains either. See ADR-0052 and ADR-0060.
 *
 * The run's own output is deliberately canary-free. `/logs` and `/artifacts` are never masked
 * (ADR-0070's accepted residual), so a canary written to stdout would leave this test with nothing
 * to assert but a leak the ticket forbids fixing here; the script writes `hi` instead. What the last
 * test asserts about that output is that it is real.
 */
@ExtendWith(ResetDrainingExtension::class)
class SecretRedactionCanaryTest {
    @TempDir
    lateinit var dir: File

    private lateinit var app: Application

    private val json = Json { ignoreUnknownKeys = true }

    private val secretCanary = "HMR-CANARY-SECRET-${UUID.randomUUID()}"

    private val sysEnvCanary = "HMR-CANARY-SYSENV-${UUID.randomUUID()}"

    private val canaries get() = listOf(secretCanary, sysEnvCanary)

    /** The seeded canary run and the pending resources the sweep probes. */
    private class Canary(
        val processId: Int,
        val watchedId: Int,
        val questionId: String,
        val dispatchId: Int,
        val continuationId: Int,
    )

    @Test
    fun `every registered route has exactly one canary row and every row names a registered route`() =
        testApplication {
            boot()
            val rows = CANARY_ROUTES.map { it.route }
            assertEquals(rows.distinct(), rows, "a route has more than one canary row")
            val registered = registeredRoutes(app)
            assertEquals(registered.distinct(), registered, "a route is registered twice")
            assertEquals(
                emptySet<String>(),
                rows.toSet() - registered.toSet(),
                "a canary row names a route that is not registered",
            )
            assertEquals(
                emptySet<String>(),
                registered.toSet() - rows.toSet(),
                "a registered route has no canary row: add one to CANARY_ROUTES (app/CLAUDE.md, " +
                    "'Adding a new HTTP endpoint')",
            )
        }

    @Test
    fun `every exempt row states a reason`() {
        val exempt = CANARY_ROUTES.filter { it.payload == null }
        assertTrue(exempt.isNotEmpty(), "a table with no exempt row would make this test vacuous")
        assertEquals(
            emptyList<String>(),
            exempt.filter { it.note.isBlank() }.map { it.route },
            "an exempt row must say why the route carries no run data",
        )
        assertEquals(
            emptyList<String>(),
            CANARY_ROUTES.filter { it.payload != null && it.note.isBlank() }.map { it.route },
            "a served row must say what its body is",
        )
    }

    @Test
    fun `no served response body carries the secret-input or SYSENV canary`() =
        testApplication {
            val canary = seedCanaryRun()
            val served = CANARY_ROUTES.filter { it.payload != null && it.payload != CanaryPayload.SSE }
            assertTrue(served.isNotEmpty(), "the sweep must probe something")
            val failures = served.mapNotNull { row -> probe(row, canary) }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }

    @Test
    fun `no SSE frame of the four streams carries either canary`() =
        testApplication {
            val canary = seedCanaryRun()
            val streams = CANARY_ROUTES.filter { it.payload == CanaryPayload.SSE }
            assertEquals(
                listOf(
                    "GET /api/v1/stream/processes",
                    "GET /api/v1/stream/processes/{id}/logs",
                    "GET /api/v1/stream/processes/{id}/llm-events",
                    "GET /api/v1/stream/agents",
                ),
                streams.map { it.route },
                "all four streams must be covered",
            )
            val failures = streams.mapNotNull { row -> sseProbe(row, canary) }
            assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        }

    @Test
    fun `the canary run holds both canaries in its stored inputs and its output is real and canary-free`() =
        testApplication {
            val canary = seedCanaryRun()
            val info = app.get<ProcessManager>().getProcessInfo(canary.processId)
            requireNotNull(info) { "the canary run must still have a row" }
            assertEquals(secretCanary, info.inputParams["CANARY_IN"], "the run's stored secret input must hold the canary")
            assertEquals(secretCanary, info.inputParams["TAGGED_IN"], "the run's stored tag input must hold the canary")
            assertEquals(setOf("CANARY_IN"), info.secretInputKeys, "the run's secret-input snapshot is taken at start")
            assertTrue(
                info.tags.any { secretCanary in it },
                "the stored run must carry the canary in a tag, or the screen has nothing to drop: ${info.tags}",
            )

            // The SYSENV canary reaches no route of its own: its only surface is the run's env, so
            // this is the proof that it is a live needle rather than a config line nothing read.
            val workspace =
                app.get<WorkspaceConfigFactory>().createWorkspaceConfig(
                    processId = canary.processId,
                    agentEnv = emptyMap(),
                    lockKeyTemplate = "lock",
                    queryParams = emptyMap(),
                    workspaceAgentConfig = WorkspaceAgentConfig(),
                )
            assertEquals(sysEnvCanary, workspace.env[SYSENV_KEY], "the SYSENV canary must reach the run's env")
            assertTrue(SYSENV_KEY in workspace.secretEnvNames, "an @SYSENV-backed global declares its value secret")
            assertEquals(
                SecretRedactor.REDACTED,
                SecretRedactor
                    .from(
                        workspace.env,
                        workspace.secretEnvNames,
                        hostEnv = { null },
                        minNeedleLength = TagValidation.SECRET_NEEDLE_MIN_LENGTH,
                    ).redact(sysEnvCanary),
                "the run's screen must mask the SYSENV canary",
            )

            val artifacts = requireNotNull(info.artifactsDir) { "the canary run must have an artifacts dir" }
            val stdOut = artifacts.resolve("cmd_0.out").toFile().readText()
            assertTrue("hi" in stdOut, "the canary run's stdout must be its own real output: ${preview(stdOut)}")
            assertTrue(canaries.none { it in stdOut }, "the never-masked stdout tail must hold no canary: ${preview(stdOut)}")
            val written = artifacts.resolve("out.txt").toFile().readText()
            assertTrue("there" in written, "the canary run must really write into its artifacts dir: ${preview(written)}")
        }

    // ---------- the canary run ----------

    /** Boots the server, starts the canary run, flips the agent, then seeds the pending resources. */
    private suspend fun ApplicationTestBuilder.seedCanaryRun(): Canary {
        boot()
        val started =
            send(
                "POST",
                "/api/v1/agent/$CANARY_AGENT",
                ADMIN_TOKEN,
                CANARY_START_BODY.replace("{canary}", secretCanary),
                readBody = true,
            )
        assertEquals(200, started.first, "the canary run must start: ${preview(started.second.orEmpty())}")
        val processId =
            json
                .parseToJsonElement(started.second!!)
                .jsonObject
                .getValue("processId")
                .jsonPrimitive.int
        awaitStatus(processId, ProcessStatus.FINISHED)
        // The response above echoed `TAGGED_IN` in the clear, which is exactly what a declared
        // non-secret input is for; every read below happens after the flip, so the sweep asserts
        // what the API serves for this run *now* — the case ADR-0052 exists for.
        flipCanaryAgentToSecretTagged()

        // The pending resources hang off runs created after the reload: `/agents/reload` kills the
        // CREATED runs of an agent it updated, so a run of the canary agent made before the flip
        // would be gone by the time the message and question rows are probed.
        val processes = app.get<ProcessManager>()
        val watched = processes.obtainProcessId(NewRunRecord(CANARY_AGENT, owner = ADMIN))
        val questions = app.get<QuestionsService>()
        questions.registerProcess(watched, CANARY_AGENT)
        val question = questions.ask(watched, "Proceed?")
        val dispatch =
            app
                .get<DispatchService>()
                .create(watched, "Pick?", emptyList(), CANARY_AGENT, "answer", mapOf("canary" to secretCanary), null)
        val watchedToo = processes.obtainProcessId(NewRunRecord(CANARY_AGENT, owner = ADMIN))
        val continuation =
            app
                .get<ContinuationService>()
                .create(watched, watchedToo, CANARY_AGENT, mapOf("canary" to secretCanary), "result", null, null)
        return Canary(processId, watched, question.id, dispatch.dispatchId, continuation)
    }

    /**
     * Rewrites the canary agent so its tag input is secret too, and reloads.
     *
     * This is what makes the tag assertion non-vacuous — see the class KDoc — and what puts the
     * flipped input into `redactInputParams`' masking set. The reload also kills the CREATED runs of
     * the agent it updated, which is why the seeded resources are created afterwards.
     */
    private suspend fun ApplicationTestBuilder.flipCanaryAgentToSecretTagged() {
        writeAgent(phaseOne = false)
        val (status, body) = send("POST", "/api/v1/agents/reload", ADMIN_TOKEN, readBody = true)
        assertEquals(200, status, "the canary agent must reload: ${preview(body.orEmpty())}")
        assertEquals(
            setOf("CANARY_IN", "TAGGED_IN"),
            app
                .get<AgentsService>()
                .agents
                .getValue(CANARY_AGENT)
                .secretEnvNames,
            "the flip must leave both canary inputs secret, or the sweep below proves nothing",
        )
    }

    private suspend fun ApplicationTestBuilder.boot() {
        writeAgent(phaseOne = true)
        application {
            configureServer(config())
            app = this
        }
        startApplication()
    }

    /** Waits for the canary run to reach [status]; the run is local and its script is trivial. */
    private suspend fun ApplicationTestBuilder.awaitStatus(
        processId: Int,
        status: ProcessStatus,
    ) {
        eventually(timeout = 30.seconds) {
            val (code, body) = send("GET", "/api/v1/status/$processId", ADMIN_TOKEN, readBody = true)
            assertEquals(200, code, "the canary run's status must be readable")
            assertEquals(
                status.name,
                json
                    .parseToJsonElement(body!!)
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
        }
    }

    // ---------- the sweep ----------

    /** What is wrong with [row]'s answer to the admin, or null when it is expected and canary-free. */
    private suspend fun ApplicationTestBuilder.probe(
        row: CanaryRoute,
        canary: Canary,
    ): String? {
        val (status, body) =
            send(
                row.route.substringBefore(' '),
                fill(row.target ?: row.route.substringAfter(' '), canary),
                ADMIN_TOKEN,
                row.body?.let { fill(it, canary) },
                readBody = true,
            )
        if (status != row.expect) return "${row.route}: expected ${row.expect}, got $status ${preview(body.orEmpty())}"
        if (row.startsRun) {
            // The row really starts a run; wait it out, so no run of the canary agent outlives the
            // test and fails its teardown through a closed scope.
            awaitStatus(startedProcessId(body.orEmpty()), ProcessStatus.FINISHED)
        }
        val leaked = canaries.count { it in body.orEmpty() }
        return "${row.route}: leaks $leaked canary value(s) in ${preview(body.orEmpty())}".takeIf { leaked > 0 }
    }

    /** The run a start response's [body] reports. */
    private fun startedProcessId(body: String): Int =
        json
            .parseToJsonElement(body)
            .jsonObject
            .getValue("processId")
            .jsonPrimitive.int

    /** What is wrong with [row]'s stream, or null when it ended on its own marker and carried no canary. */
    private suspend fun ApplicationTestBuilder.sseProbe(
        row: CanaryRoute,
        canary: Canary,
    ): String? {
        val frames = readFrames(row, fill(row.target ?: row.route.substringAfter(' '), canary), canary)
        val stop = sseStop(row.route)
        if (stop !in frames) return "${row.route}: the stream never reached its $stop marker in ${preview(frames)}"
        val leaked = canaries.count { it in frames }
        return "${row.route}: leaks $leaked canary value(s) in ${preview(frames)}".takeIf { leaked > 0 }
    }

    /**
     * The frames [row]'s stream writes, read until the marker its own end carries: `log.end` /
     * `llm.end` for the two tails, a sentinel event for the two broadcast streams. The read is
     * bounded, so a stream that never reaches its marker is a failure ([sseProbe]) rather than a hang.
     */
    private suspend fun ApplicationTestBuilder.readFrames(
        row: CanaryRoute,
        target: String,
        canary: Canary,
    ): String {
        val stop = sseStop(row.route)
        val frames = StringBuilder()
        client
            .prepareGet(target) {
                header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                header(HttpHeaders.Accept, "text/event-stream")
            }.execute { response ->
                assertEquals(200, response.status.value, "${row.route} must open")
                coroutineScope {
                    // Repeats: an event emitted before the subscriber attached is dropped (the flow
                    // keeps no replay), so a single shot would race the handshake.
                    val trigger =
                        launch {
                            delay(300)
                            while (true) {
                                drive(row, canary)
                                delay(100)
                            }
                        }
                    try {
                        withTimeoutOrNull(20_000) {
                            val channel = response.bodyAsChannel()
                            while (true) {
                                val line = channel.readLine() ?: break
                                frames.append(line).append('\n')
                                if (stop in line) break
                            }
                        }
                    } finally {
                        trigger.cancel()
                    }
                }
            }
        return frames.toString()
    }

    /** Emits the event [row]'s broadcast stream needs to write a frame; the tails need no driver. */
    private fun drive(
        row: CanaryRoute,
        canary: Canary,
    ) {
        val broadcaster = app.get<ProcessEventBroadcaster>()
        when (sseStop(row.route)) {
            PROCESS_SENTINEL -> {
                broadcaster.emit(ProcessEvent.StatusChanged(canary.processId, ProcessStatus.FINISHED, CANARY_AGENT))
                broadcaster.emit(ProcessEvent.QuestionAsked(canary.processId, PROCESS_SENTINEL, CANARY_AGENT))
            }

            AGENT_SENTINEL -> {
                broadcaster.emit(ProcessEvent.AgentsReloaded(listOf(AGENT_SENTINEL), emptyList(), emptyList()))
            }

            // The two tails end on their own file, so they need no event to drive them.
            else -> {
                return
            }
        }
    }

    /** The line [route]'s stream stops on, which doubles as the key naming the event that drives it. */
    private fun sseStop(route: String): String =
        when {
            route.endsWith("/logs") -> "log.end"
            route.endsWith("/llm-events") -> "llm.end"
            route.endsWith("/stream/processes") -> PROCESS_SENTINEL
            else -> AGENT_SENTINEL
        }

    // ---------- requests, samples and config ----------

    /** The status of [method] [target] sent with the admin bearer, and the body. */
    private suspend fun ApplicationTestBuilder.send(
        method: String,
        target: String,
        bearer: String,
        body: String? = null,
        readBody: Boolean = false,
    ): Pair<Int, String?> =
        client
            .prepareRequest(target) {
                this.method = HttpMethod.parse(if (method == "*") "GET" else method)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                header(HttpHeaders.Authorization, "Bearer $bearer")
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }.execute { response ->
                val status = response.status.value
                status to (if (readBody || status != 200) response.bodyAsText() else null)
            }

    /** [template] with every placeholder the canary run fills; an unfilled one is a broken row. */
    private fun fill(
        template: String,
        canary: Canary,
    ): String {
        val values =
            mapOf(
                "{processId}" to "${canary.processId}",
                "{watchedId}" to "${canary.watchedId}",
                "{questionId}" to canary.questionId,
                "{dispatchId}" to "${canary.dispatchId}",
                "{continuationId}" to "${canary.continuationId}",
                "{agentName}" to CANARY_AGENT,
                "{canary}" to secretCanary,
            )
        val filled = values.entries.fold(template) { acc, (key, value) -> acc.replace(key, value) }
        check(!PLACEHOLDER.containsMatchIn(filled)) { "No sample for a placeholder in '$template'" }
        return filled
    }

    /**
     * [text] as a failure message may carry it: every canary replaced by `<canary>`, then cut short.
     * A body that leaks is the finding, so the message must name the route, never the value.
     */
    private fun preview(text: String): String = canaries.fold(text) { acc, canary -> acc.replace(canary, "<canary>") }.take(200)

    /** The canary agent as phase one (its tag input is not secret) or phase two (it is). */
    private fun writeAgent(phaseOne: Boolean) {
        val agentDir = File(dir, "agents/$CANARY_AGENT").apply { mkdirs() }
        File(agentDir, "agent.yaml").writeText(agentYaml(phaseOne))
    }

    private fun agentYaml(phaseOne: Boolean): String =
        buildString {
            append("name: $CANARY_AGENT\n")
            append("dispatch:\n  enabled: true\n")
            append("inputs:\n")
            append("  - paramName: canary\n    envName: CANARY_IN\n    src: body\n    type: string\n    required: true\n    secret: true\n")
            append("  - paramName: tagged\n    envName: TAGGED_IN\n    src: body\n    type: string\n    required: false\n")
            if (!phaseOne) append("    secret: true\n")
            if (phaseOne) append("tags:\n  fromInputs:\n    - tagged\n")
            // No canary in the output: /logs and /artifacts are never masked (ADR-0070), so the
            // script's own output is what proves the run really ran, and nothing else.
            append("commands:\n  - !<script>\n    content: |\n      echo hi\n      echo there > \"\$HMR_ARTIFACTS_DIR/out.txt\"\n")
        }

    private fun config(): AppConfig {
        val operators = AuthConfig.Bearer(tokens = mapOf(ADMIN to TokenConfig(token = ADMIN_TOKEN, role = "admin")))
        val password =
            AuthConfig.Password(
                users = mapOf("carol" to PasswordUserConfig(passwordHash = PasswordVerifier().hash("pw", cost = 4), role = "admin")),
                sessionSecret = "canary-session-secret-aaaaaaaaaaaa",
                cookieSecure = false,
                bcryptCost = 4,
            )
        val roomy = RateLimitConfig(limit = 10_000, refillPeriod = 60)
        return AppConfig(
            server =
                Server(
                    apiAuth = AuthConfig.Combined(listOf(operators, password)),
                    mcp = McpServerConfig(enabled = true),
                    corsAllowedOrigins = listOf("http://localhost:3000"),
                    rateLimit = roomy,
                    perCallerRateLimit = roomy,
                    loginRateLimit = roomy,
                ),
            agentsSearchPath = File(dir, "agents").absolutePath,
            workspaceRootPath = File(dir, "workspace").absolutePath,
            databasePath = File(dir, "homer.db").absolutePath,
            // The operator's `@SYSENV`-backed global. `configureServer` does not re-resolve it, so
            // the value here is the run's; the name set is what `SysEnvResolver.secretNamesFor`
            // produces for `@SYSENV:CANARY_TOKEN`, and it is what makes the value a needle in every
            // screen the run builds.
            globalEnv = mapOf(SYSENV_KEY to sysEnvCanary),
            sysEnvBackedGlobalEnvNames = setOf(SYSENV_KEY),
            // The tag the canary run carries, so `GET /costs/by-tag` screens its values on read.
            costTrackedTagKeys = listOf(TAG_KEY),
            langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
            openTelemetryExport = OpenTelemetryExport.LOG,
            runners =
                mapOf(
                    "workers" to RunnerConfig.External(token = RUNNER_TOKEN),
                    "others" to RunnerConfig.External(token = OTHER_RUNNER_TOKEN),
                ),
        )
    }

    private companion object {
        const val ADMIN = "ops"
        const val ADMIN_TOKEN = "canary-admin-token-aaaaaaaaaaaaaaaaa"
        const val RUNNER_TOKEN = "canary-runner-domain-token-aaaaaaaaaaaaaaaaaaa"
        const val OTHER_RUNNER_TOKEN = "canary-other-runner-domain-token-aaaaaaaaaaaa"
        const val CANARY_AGENT = "canary-agent"
        const val SYSENV_KEY = "CANARY_TOKEN"
        const val TAG_KEY = "tagged"
        const val PROCESS_SENTINEL = "canary-sweep-question"
        const val AGENT_SENTINEL = "canary-sweep-agent"
        val PLACEHOLDER = Regex("""\{[A-Za-z.]+}""")
    }
}
