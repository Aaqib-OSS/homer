package homer.app.routes

import homer.app.ResetDrainingExtension
import homer.app.configureServer
import homer.app.fixtures.send
import homer.app.infra.PasswordVerifier
import homer.app.models.Action
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Grant
import homer.app.models.LangfuseConfig
import homer.app.models.McpServerConfig
import homer.app.models.NewRunRecord
import homer.app.models.OpenTelemetryExport
import homer.app.models.PasswordUserConfig
import homer.app.models.RateLimitConfig
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.RunnerConfig
import homer.app.models.Server
import homer.app.models.SrcType
import homer.app.models.TokenConfig
import homer.app.runners.RunnerRegistry
import homer.app.runners.external.RegisterInstanceRequest
import homer.app.services.ContinuationService
import homer.app.services.DispatchService
import homer.app.services.ProcessManager
import homer.app.services.QuestionsService
import homer.app.services.ResultTokenService
import io.ktor.server.application.Application
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.koin.ktor.ext.get
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runtime counterpart of the architecture module's static `RouteAuthorizationTest`: boots the real server with
 * every optional surface on, requires a [ROUTE_AUTHZ] row per registered route and drives its denial cases
 * (ADR-0112); MCP tools are held to [OPERATOR_MCP_TOOLS] and [QUESTIONS_MCP_TOOLS]; divergences live in [KNOWN_GAPS].
 */
@ExtendWith(ResetDrainingExtension::class)
class RouteAuthorizationMatrixTest {
    @TempDir
    lateinit var dir: File

    private val json = Json { ignoreUnknownKeys = true }

    private enum class Case(
        val label: String,
    ) {
        NO_GRANT("no grant"),
        WRONG_DOMAIN("wrong domain"),
        NONE("no credentials"),
    }

    /**
     * The seeded resources: all of [agentName], owned by the admin, except the no-grant principal's own
     * run. [ghost] names resources that do not exist, for the "answers like an unknown one" checks.
     */
    private class Seeds(
        val processId: Int,
        val questionId: String,
        val dispatchId: Int,
        val continuationId: Int,
        val resultToken: String,
        val instanceId: String,
        val hiddenRunToken: String,
        val noGrantRunToken: String,
        val agentName: String = HIDDEN_AGENT,
    ) {
        private val values =
            mapOf(
                "{processId}" to "$processId",
                "{questionId}" to questionId,
                "{dispatchId}" to "$dispatchId",
                "{continuationId}" to "$continuationId",
                "{resultToken}" to resultToken,
                "{instanceId}" to instanceId,
                "{agentName}" to agentName,
                "{...}" to "agent.yaml",
                "{key}" to "team",
                "{token}" to "x",
                "{sid}" to "x",
                "{opId}" to "x",
            )

        /** [template] with every placeholder filled; an unknown one (e.g. a bare `{id}`) needs a row `sample`. */
        fun fill(template: String): String {
            val filled = values.entries.fold(template) { acc, (key, value) -> acc.replace(key, value) }
            check(!PLACEHOLDER.containsMatchIn(filled)) { "No seed for a placeholder in '$template'" }
            return filled
        }

        fun ghost(): Seeds =
            Seeds(
                999_901,
                "ghost-question",
                999_902,
                999_903,
                "ghost-result-token",
                "ghost-instance",
                hiddenRunToken,
                noGrantRunToken,
                "ghost-agent",
            )

        /** [ghostText] (an answer about [ghost]'s resources) with each ghost value replaced by this seed's. */
        fun fromGhost(
            ghost: Seeds,
            ghostText: String,
        ): String = ghost.values.entries.fold(ghostText) { acc, (key, value) -> acc.replace(value, values.getValue(key)) }
    }

    private fun config(): AppConfig {
        val operators =
            AuthConfig.Bearer(
                tokens =
                    mapOf(
                        ADMIN to TokenConfig(token = ADMIN_TOKEN, role = "admin"),
                        NO_GRANT to TokenConfig(token = NO_GRANT_TOKEN, grants = listOf(Grant(listOf(VISIBLE_AGENT), listOf(Action.READ)))),
                    ),
            )
        val password =
            AuthConfig.Password(
                users = mapOf("carol" to PasswordUserConfig(passwordHash = PasswordVerifier().hash("pw", cost = 4), role = "admin")),
                sessionSecret = "matrix-session-secret-aaaaaaaaaaaa",
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
            langfuse = LangfuseConfig(serverUrl = "http://localhost:9", publicKey = "pk-test", secretKey = "sk-test"),
            openTelemetryExport = OpenTelemetryExport.LOG,
            runners =
                mapOf(
                    "workers" to RunnerConfig.External(token = RUNNER_TOKEN),
                    "others" to RunnerConfig.External(token = OTHER_RUNNER_TOKEN),
                ),
        )
    }

    private fun writeAgent(
        name: String,
        extra: String = "",
    ) {
        val agentDir = File(dir, "agents/$name").apply { mkdirs() }
        File(agentDir, "agent.yaml").writeText("name: $name\ncommands:\n  - !<cmd>\n    cmd: \"echo hi\"\n$extra")
    }

    private suspend fun ApplicationTestBuilder.boot(): Application {
        writeAgent(HIDDEN_AGENT, "dispatch:\n  enabled: true\nmcp:\n  enabled: true\n")
        writeAgent(VISIBLE_AGENT)
        lateinit var app: Application
        application {
            configureServer(config())
            app = this
        }
        startApplication()
        return app
    }

    private suspend fun seed(app: Application): Seeds {
        val processes = app.get<ProcessManager>()
        val questions = app.get<QuestionsService>()
        val hidden = processes.obtainProcessId(NewRunRecord(HIDDEN_AGENT, owner = ADMIN))
        val hiddenRunToken = questions.registerProcess(hidden, HIDDEN_AGENT)
        val question = questions.ask(hidden, "Proceed?")
        val dispatch = app.get<DispatchService>().create(hidden, "Pick?", emptyList(), HIDDEN_AGENT, "answer", emptyMap(), null)
        val watched = processes.obtainProcessId(NewRunRecord(HIDDEN_AGENT, owner = ADMIN))
        val continuation =
            app.get<ContinuationService>().create(hidden, watched, HIDDEN_AGENT, emptyMap(), "result", null, null)
        val resultToken =
            app.get<ResultTokenService>().mint(
                watched,
                "out.txt",
                ResultEnvParam(SrcType.STD_OUT, "OUT", ResultTransformer.JsonPath("$.x")),
                1,
                HIDDEN_AGENT,
            )
        val workers = app.get<RunnerRegistry>().externalDomains.single { it.alias == "workers" }
        val instance = workers.register(RegisterInstanceRequest(name = "workers-host"))
        val own = processes.obtainProcessId(NewRunRecord(VISIBLE_AGENT, owner = NO_GRANT))
        val noGrantRunToken = questions.registerProcess(own, VISIBLE_AGENT)
        return Seeds(
            hidden,
            question.id,
            dispatch.dispatchId,
            continuation,
            resultToken,
            instance.id,
            hiddenRunToken,
            noGrantRunToken,
        )
    }

    private fun RouteAuthz.expected(case: Case): Int =
        when (case) {
            Case.NO_GRANT -> noGrant
            Case.WRONG_DOMAIN -> wrongDomain
            Case.NONE -> none
        }

    /**
     * The credentials [case] sends to a route of [domain]: for no grant, the domain's own live credential
     * that owns no seeded resource; for the wrong domain, every other domain's live credential.
     */
    private fun credentials(
        case: Case,
        domain: CredentialDomain,
        seeds: Seeds,
    ): List<String?> =
        when (case) {
            Case.NO_GRANT -> {
                when (domain) {
                    CredentialDomain.OPERATOR, CredentialDomain.PUBLIC -> listOf(NO_GRANT_TOKEN)
                    CredentialDomain.CALLBACK -> listOf(seeds.noGrantRunToken)
                    CredentialDomain.RUNNER -> listOf(OTHER_RUNNER_TOKEN)
                }
            }

            Case.NONE -> {
                listOf(null)
            }

            Case.WRONG_DOMAIN -> {
                when (domain) {
                    CredentialDomain.OPERATOR, CredentialDomain.PUBLIC -> listOf(seeds.hiddenRunToken, RUNNER_TOKEN)
                    CredentialDomain.CALLBACK -> listOf(ADMIN_TOKEN, RUNNER_TOKEN)
                    CredentialDomain.RUNNER -> listOf(ADMIN_TOKEN, seeds.hiddenRunToken)
                }
            }
        }

    /** What is wrong with [row]'s [case] answer to [bearer], or null when it is the expected one. */
    private suspend fun ApplicationTestBuilder.denialFailure(
        row: RouteAuthz,
        case: Case,
        bearer: String?,
        seeds: Seeds,
    ): String? {
        val (method, path) = row.route.split(' ', limit = 2)
        val target = row.sample ?: path
        val (status, body) = send(method, seeds.fill(target), bearer, row.body?.let(seeds::fill), row.listFiltered)
        val namesResource = PLACEHOLDER.containsMatchIn(target + row.body.orEmpty())
        return when {
            status != row.expected(case) -> {
                "expected ${row.expected(case)}, got $status"
            }

            row.listFiltered && HIDDEN_AGENT in body.orEmpty() -> {
                "the list names $HIDDEN_AGENT"
            }

            case == Case.NO_GRANT && status == 404 && namesResource -> {
                existenceLeak(method, target, bearer, row.body, seeds, body.orEmpty())
            }

            else -> {
                null
            }
        }
    }

    /**
     * Whether the no-grant `404` [hiddenBody] for the seeded resource differs from the answer for a
     * resource that does not exist: a denial must hide existence (ADR-0047 rule 3), and a `404` that only
     * reports, say, a missing artifact file would otherwise pass for a denial.
     */
    private suspend fun ApplicationTestBuilder.existenceLeak(
        method: String,
        target: String,
        bearer: String?,
        body: String?,
        seeds: Seeds,
        hiddenBody: String,
    ): String? {
        val ghost = seeds.ghost()
        val (status, ghostBody) = send(method, ghost.fill(target), bearer, body?.let(ghost::fill))
        val unknown = "$status ${seeds.fromGhost(ghost, ghostBody.orEmpty())}"
        return "answers '404 $hiddenBody', an unknown one '$unknown'".takeIf { unknown != "404 $hiddenBody" }
    }

    /**
     * Fails unless the admin sees every seed — a `404` for the no-grant principal must not be a plain
     * not-found — and every credential the cases send is live, so a `401` means the wrong domain, not a
     * dead token.
     */
    private suspend fun ApplicationTestBuilder.assertSeedsExist(seeds: Seeds) {
        listOf(
            "/api/v1/status/${seeds.processId}",
            "/api/v1/dispatches/${seeds.dispatchId}",
            "/api/v1/continuations/${seeds.continuationId}",
            "/api/v1/agents/$HIDDEN_AGENT/files",
        ).forEach { assertEquals(200, send("GET", it, ADMIN_TOKEN).first, "admin must see $it") }
        val questions = send("GET", "/api/v1/questions", ADMIN_TOKEN, readBody = true).second.orEmpty()
        assertTrue(seeds.questionId in questions, "admin must see question ${seeds.questionId}")
        listOf(seeds.noGrantRunToken, seeds.hiddenRunToken).forEach {
            assertEquals(200, send("GET", "/api/v1/callback/whoami", it).first, "every callback token sent must be live")
        }
        listOf(RUNNER_TOKEN, OTHER_RUNNER_TOKEN).forEach {
            assertEquals(
                404,
                send("POST", "/api/v1/runner/instances/ghost-instance/claim", it).first,
                "every runner token sent must be live",
            )
        }
        assertEquals(200, send("GET", "/api/v1/heartbeat", NO_GRANT_TOKEN).first, "the no-grant operator token must be live")
    }

    @Test
    fun `every registered route has exactly one row and every row names a registered route`() =
        testApplication {
            val registered = registeredRoutes(boot())
            val rows = ROUTE_AUTHZ.map { it.route }

            assertEquals(rows.distinct(), rows, "duplicate rows")
            assertEquals(registered.distinct(), registered, "a route registered twice")
            assertEquals(emptySet(), registered.toSet() - rows.toSet(), "registered routes without a row in ROUTE_AUTHZ")
            assertEquals(emptySet(), rows.toSet() - registered.toSet(), "ROUTE_AUTHZ rows naming no registered route")
        }

    @Test
    fun `every route answers each denial case with the status its row expects`() =
        testApplication {
            val seeds = seed(boot())
            assertSeedsExist(seeds)

            val failures = mutableMapOf<String, String>()
            for (row in ROUTE_AUTHZ) {
                for (case in Case.entries) {
                    credentials(case, row.domain, seeds).forEach { bearer ->
                        denialFailure(row, case, bearer, seeds)?.let { failures["${row.route} [${case.label}]"] = it }
                    }
                }
            }

            assertGapsExactly(failures, prefix = null)
        }

    private suspend fun ApplicationTestBuilder.rpc(
        path: String,
        bearer: String,
        method: String,
        params: String,
    ): JsonObject {
        val request = """{"jsonrpc":"2.0","id":2,"method":"$method","params":$params}"""
        val (status, body) = send("POST", path, bearer, request, readBody = true)
        assertEquals(200, status, "$method on $path")
        val text = body.orEmpty().trim()
        if (text.startsWith("{")) return json.parseToJsonElement(text).jsonObject
        // The questions endpoint answers in SSE mode: the JSON-RPC message is a `data:` line.
        return text
            .lineSequence()
            .filter { it.startsWith("data: {") }
            .map { json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
            .first { "result" in it || "error" in it }
    }

    private suspend fun ApplicationTestBuilder.toolNames(
        path: String,
        bearer: String,
    ): Set<String> =
        rpc(path, bearer, "tools/list", "{}")["result"]!!
            .jsonObject["tools"]!!
            .jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
            .toSet()

    /** What is wrong with [tool]'s refusal of [bearer] (already holding [listed]), or null when it is refused as its row says. */
    private suspend fun ApplicationTestBuilder.toolDenialFailure(
        path: String,
        tool: McpToolAuthz,
        bearer: String,
        listed: Set<String>,
        seeds: Seeds,
    ): String? {
        if (tool.denial == McpDenial.OWN_RUN_ONLY) return null
        if (tool.denial == McpDenial.UNLISTED && tool.tool in listed) return "listed for the no-grant caller"
        val answer = callTool(path, bearer, tool.tool, seeds, tool.arguments)
        val refused =
            "error" in answer || answer["result"]
                ?.jsonObject
                ?.get("isError")
                ?.jsonPrimitive
                ?.content == "true"
        // Refused exactly like a resource (or, for an unlisted tool, a tool name) that does not exist
        // (ADR-0047 rule 3).
        val ghost = seeds.ghost()
        val ghostTool = if (tool.denial == McpDenial.UNLISTED) ghost.agentName else tool.tool
        val unknown = seeds.fromGhost(ghost, callTool(path, bearer, ghostTool, ghost, tool.arguments).toString())
        return when {
            !refused -> "not refused: $answer"
            unknown != answer.toString() -> "refused as '$answer', an unknown one as '$unknown'"
            else -> null
        }
    }

    private suspend fun ApplicationTestBuilder.callTool(
        path: String,
        bearer: String,
        tool: String,
        seeds: Seeds,
        arguments: String,
    ): JsonObject = rpc(path, bearer, "tools/call", """{"name":"$tool","arguments":${seeds.fill(arguments)}}""")

    @Test
    fun `operator MCP tools each have a row and a no-grant principal is refused every gated one`() =
        testApplication {
            val seeds = seed(boot())
            assertEquals(OPERATOR_MCP_TOOLS.map { it.tool }.toSet(), toolNames("/mcp", ADMIN_TOKEN), "the admin's /mcp tool set")

            val listed = toolNames("/mcp", NO_GRANT_TOKEN)
            val failures =
                OPERATOR_MCP_TOOLS
                    .mapNotNull { tool ->
                        toolDenialFailure("/mcp", tool, NO_GRANT_TOKEN, listed, seeds)?.let { "mcp:${tool.tool} [no grant]" to it }
                    }.toMap()

            assertGapsExactly(failures, prefix = "mcp:")
        }

    @Test
    fun `questions MCP tools each have a row and the no-grant principal's run is refused every gated one`() =
        testApplication {
            val seeds = seed(boot())
            val listed = toolNames("/mcp/questions", seeds.noGrantRunToken)
            assertEquals(QUESTIONS_MCP_TOOLS.map { it.tool }.toSet(), listed, "the /mcp/questions tool set")

            val failures =
                QUESTIONS_MCP_TOOLS
                    .mapNotNull { tool ->
                        toolDenialFailure("/mcp/questions", tool, seeds.noGrantRunToken, listed, seeds)
                            ?.let { "mcp/questions:${tool.tool} [no grant]" to it }
                    }.toMap()

            assertGapsExactly(failures, prefix = "mcp/questions:")
        }

    /** [failures] must be exactly the [KNOWN_GAPS] under [prefix] (route gaps when null): a fixed gap fails as loudly as a new one. */
    private fun assertGapsExactly(
        failures: Map<String, String>,
        prefix: String?,
    ) {
        val known = KNOWN_GAPS.keys.filter { key -> if (prefix == null) !key.startsWith("mcp") else key.startsWith(prefix) }
        val fixed = known - failures.keys
        assertEquals(
            known.toSortedSet(),
            failures.keys.toSortedSet(),
            "denials diverging from the matrix (update the row or KNOWN_GAPS):\n" +
                failures.entries.joinToString("\n") { "  ${it.key}: ${it.value}" } +
                fixed.joinToString("") { "\n  $it: in KNOWN_GAPS but no longer reproduces, remove it" },
        )
    }

    @Test
    fun `a known gap must name the GitHub issue tracking its fix`() {
        assertTrue(KNOWN_GAPS.values.all { ISSUE_REFERENCE.matches(it.issue) })
        assertEquals("#235", KnownGap("tracked", "#235").issue)
        listOf("", "TODO-ISSUE", "235", "#", "#12a", "https://github.com/livesport/homer/issues/235").forEach { issue ->
            assertFailsWith<IllegalArgumentException>("'$issue' must be refused") { KnownGap("untracked", issue) }
        }
    }

    private companion object {
        const val ADMIN = "ops"
        const val NO_GRANT = "nogrant"
        const val VISIBLE_AGENT = "visible-agent"
        const val ADMIN_TOKEN = "matrix-admin-token-aaaaaaaaaaaaaaaa"
        const val NO_GRANT_TOKEN = "matrix-nogrant-token-aaaaaaaaaaaaaaa"
        const val RUNNER_TOKEN = "matrix-runner-domain-token-aaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_RUNNER_TOKEN = "matrix-other-runner-domain-token-aaaaaaaaaaaaaaaa"
        val PLACEHOLDER = Regex("""\{[A-Za-z.]+}""")
    }
}
