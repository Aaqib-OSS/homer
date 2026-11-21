package homer.app.routes

import homer.app.models.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import io.ktor.utils.io.writeStringUtf8
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.coroutines.CoroutineContext

/**
 * Stateless Streamable HTTP MCP endpoint at `/mcp` (JSON-response mode, no sessions, no SSE): a fresh transport
 * and server per POST keep the tool list current across agent reloads. Same auth wrapper as the REST API (ADR-0018).
 */
internal fun Route.mcpRoutes(deps: McpRouteDeps) {
    route("/mcp") {
        // SDK JSONRPCMessage types are polymorphic and must serialize through McpJson; route-scoped so the
        // global ContentNegotiation Json is untouched.
        install(ContentNegotiation) {
            json(McpJson)
        }
        post {
            val transport =
                StreamableHttpServerTransport(
                    StreamableHttpServerTransport.Configuration(enableJsonResponse = true),
                ).also { it.setSessionIdGenerator(null) }
            val principal = call.principal<UserIdPrincipal>()?.name
            val server = deps.mcpToolFactory.buildServer(principal)
            server.createSession(transport)
            transport.handleRequest(null, call)
        }
        get {
            call.respond(
                HttpStatusCode.MethodNotAllowed,
                ErrorResponse(msg = "Method not allowed: the MCP endpoint is stateless and accepts POST only"),
            )
        }
        delete {
            call.respond(
                HttpStatusCode.MethodNotAllowed,
                ErrorResponse(msg = "Method not allowed: the MCP endpoint is stateless and accepts POST only"),
            )
        }
    }
}

/**
 * Stateless MCP endpoint at `/mcp/questions` for the question tools: like [mcpRoutes], but authenticated by
 * `HMR_CALLBACK_TOKEN` outside `authenticate(...)` and in SSE response mode, so a blocking `ask_user_and_wait`
 * survives client timeouts (ADR-0019).
 */
internal fun Route.questionsMcpRoutes(deps: McpRouteDeps) {
    route("/mcp/questions") {
        install(ContentNegotiation) {
            json(McpJson)
        }
        post {
            val token =
                call.request.headers[HttpHeaders.Authorization]
                    ?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null }
            val processId = token?.takeIf { it.isNotEmpty() }?.let { deps.questionsService.resolveToken(it) }
            if (processId == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Missing or invalid callback token"))
                return@post
            }
            val transport =
                StreamableHttpServerTransport(
                    StreamableHttpServerTransport.Configuration(enableJsonResponse = false),
                ).also { it.setSessionIdGenerator(null) }
            val server = deps.questionsMcpToolFactory.buildServer(processId)
            server.createSession(transport)
            handleSsePost(transport, call)
        }
        get {
            call.respond(
                HttpStatusCode.MethodNotAllowed,
                ErrorResponse(msg = "Method not allowed: the MCP questions endpoint is stateless and accepts POST only"),
            )
        }
        delete {
            call.respond(
                HttpStatusCode.MethodNotAllowed,
                ErrorResponse(msg = "Method not allowed: the MCP questions endpoint is stateless and accepts POST only"),
            )
        }
    }
}

private const val SSE_HEARTBEAT_MS = 15_000L

/**
 * Bridges the SDK transport's SSE response mode onto Homer's SSE: the stream must not start before the transport's
 * first send, as its reject paths (401/406/415) respond plain HTTP. `: ping` heartbeats fill idle gaps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun handleSsePost(
    transport: StreamableHttpServerTransport,
    call: ApplicationCall,
) {
    coroutineScope {
        val events = Channel<ServerSentEvent>(Channel.UNLIMITED)
        val firstEvent = CompletableDeferred<Unit>()
        val session =
            object : ServerSSESession {
                override val call: ApplicationCall = call
                override val coroutineContext: CoroutineContext = this@coroutineScope.coroutineContext

                override suspend fun send(event: ServerSentEvent) {
                    events.send(event)
                    firstEvent.complete(Unit)
                }

                override suspend fun close() {
                    events.close()
                }
            }
        val writer =
            launch {
                firstEvent.await()
                // No contentType here: the transport already appended
                // text/event-stream before its first send.
                call.respondBytesWriter {
                    while (true) {
                        // select, not withTimeoutOrNull(receiveCatching): a timeout cancelling a plain receive can drop an
                        // element already taken off the channel, losing the final JSON-RPC response.
                        val received =
                            select<ChannelResult<ServerSentEvent>?> {
                                events.onReceiveCatching { it }
                                onTimeout(SSE_HEARTBEAT_MS) { null }
                            }
                        when {
                            received == null -> writeStringUtf8(": ping\n\n")
                            received.isClosed -> break
                            else -> writeStringUtf8(received.getOrThrow().render())
                        }
                        flush()
                    }
                }
            }
        try {
            // Suspends through the whole tool execution; the final JSON-RPC
            // response closes the session, which ends the writer loop.
            transport.handleRequest(session, call)
        } finally {
            events.close()
            // Rejected before any SSE byte (bad Accept, parse error): the
            // transport responded plain HTTP and the writer never started.
            if (!firstEvent.isCompleted) writer.cancel()
        }
    }
}

private fun ServerSentEvent.render(): String =
    buildString {
        comments?.lineSequence()?.forEach { append(": ").append(it).append('\n') }
        event?.let { append("event: ").append(it).append('\n') }
        id?.let { append("id: ").append(it).append('\n') }
        retry?.let { append("retry: ").append(it).append('\n') }
        data?.lineSequence()?.forEach { append("data: ").append(it).append('\n') }
        append('\n')
    }
