package homer.app.fixtures

import homer.app.apiJson
import homer.app.buildBearerProvider
import homer.app.configureStatusPages
import homer.app.models.AppConfig
import homer.app.models.AuthConfig
import homer.app.models.Server
import homer.app.models.TokenConfig
import homer.app.routes.ApiDeps
import homer.app.routes.apiV1Routes
import homer.app.routes.callbackDeps
import homer.app.routes.callbackRoutes
import homer.app.services.Authorizer
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.every
import io.mockk.mockk

/**
 * Mounts the production [apiV1Routes] inside a `testApplication` with a
 * relaxed [ApiDeps] mock; [stubDeps] wires the real dependencies a test
 * needs (e.g. `every { processManager } returns realPm`). ContentNegotiation
 * is installed routing-scoped with the production [apiJson], matching the
 * application wiring. Single edit point for every route-level test suite.
 */
internal fun ApplicationTestBuilder.mountApiV1Routes(stubDeps: ApiDeps.() -> Unit = {}): ApiDeps {
    val deps =
        mockk<ApiDeps>(relaxed = true) {
            // Unauthenticated/no-grants by default, matching unsecured-mode
            // production behavior — every Authorizer check passes. Tests that
            // exercise grants enforcement stub `authorizer` themselves.
            every { authorizer } returns Authorizer(emptyMap(), emptyMap(), enabled = false)
            stubDeps()
        }
    application {
        // Matches production wiring: handlers signal error statuses by
        // throwing HomerException subclasses (e.g. QuestionNotFound → 404).
        configureStatusPages()
        routing {
            install(ContentNegotiation) { json(apiJson) }
            apiV1Routes(deps)
        }
    }
    return deps
}

/**
 * Same shape as [mountApiV1Routes] for the token-authenticated
 * [callbackRoutes]. StatusPages is installed because the handlers signal
 * 404/409/429 via HomerException subclasses, matching production wiring.
 */
internal fun ApplicationTestBuilder.mountCallbackRoutes(stubDeps: ApiDeps.() -> Unit = {}): ApiDeps {
    val deps = mockk<ApiDeps>(relaxed = true, block = stubDeps)
    application {
        configureStatusPages()
        routing {
            install(ContentNegotiation) { json(apiJson) }
            callbackRoutes(deps.callbackDeps())
        }
    }
    return deps
}

/** The production [Authorizer] for a bearer-only config holding [tokens]. */
internal fun authorizerFor(tokens: Map<String, TokenConfig>): Authorizer =
    Authorizer.from(
        AppConfig(
            server = Server(apiAuth = AuthConfig.Bearer(tokens = tokens)),
            agentsSearchPath = "unused",
            workspaceRootPath = "unused",
            databasePath = "unused",
        ),
    )

/**
 * The status of [method] [target] sent as JSON, with [bearer] when it holds one, and the body —
 * read whenever the caller asked for it or the status was not `200`, so a failure case never
 * returns a body the caller cannot assert on. Shared by the matrix and canary sweeps.
 */
internal suspend fun ApplicationTestBuilder.send(
    method: String,
    target: String,
    bearer: String?,
    body: String? = null,
    readBody: Boolean = false,
): Pair<Int, String?> =
    client
        .prepareRequest(target) {
            this.method = HttpMethod.parse(if (method == "*") "GET" else method)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }.execute { response ->
            val status = response.status.value
            status to (if (readBody || status != 200) response.bodyAsText() else null)
        }

/**
 * Mounts the production [apiV1Routes] behind the production bearer provider
 * (`buildBearerProvider`) for [tokens], nested in `authenticate(...)` as
 * `configureRouting` does, so grants and owner tests run the real
 * authentication → principal → Authorizer path.
 */
internal fun Application.installGrantsApp(
    tokens: Map<String, TokenConfig>,
    deps: ApiDeps,
) {
    install(Authentication) { buildBearerProvider(AuthConfig.Bearer(tokens = tokens)) }
    configureStatusPages()
    routing {
        install(ContentNegotiation) { json(apiJson) }
        authenticate("auth-bearer", strategy = AuthenticationStrategy.FirstSuccessful) {
            apiV1Routes(deps)
        }
    }
}
