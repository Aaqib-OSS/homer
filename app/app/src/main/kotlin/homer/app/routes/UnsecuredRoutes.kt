package homer.app.routes

import homer.app.auth.HomerSession
import homer.app.auth.bearerCredential
import homer.app.auth.principalFor
import homer.app.infra.PasswordVerifier
import homer.app.models.AuthConfig
import homer.app.models.ErrorResponse
import homer.app.models.LivenessResponse
import homer.app.models.LoginRequest
import homer.app.models.LoginResponse
import homer.app.models.MeResponse
import homer.app.models.ReadinessResponse
import homer.app.services.Authorizer
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set

/**
 * The unsecured /api/v1 routes: liveness/readiness probes and the
 * /api/v1/auth login/logout/me endpoints — mounted outside the
 * bearer/HMAC authenticate wrapper so Kubernetes probes and the cookie
 * auth handshake work without a token.
 */
internal fun Route.apiV1UnsecuredRoutes(deps: ApiDeps) {
    // Outside the auth wrapper: kubelet and reverse-proxy probes send no Authorization header.
    probeRoutes(deps.unsecuredDeps())

    // Auth routes sit outside the wrapper: /login is unauthenticated, /logout works with a stale cookie, and
    // /me reads its one credential itself, never FirstSuccessful, which would mix principals. See ADR-0011.
    val passwordScheme = deps.passwordScheme
    if (passwordScheme != null) {
        sessionAuthRoutes(deps.unsecuredDeps(), passwordScheme)
    }
    val bearerScheme = deps.bearerScheme
    if (passwordScheme != null || bearerScheme != null) {
        meRoute(deps.authorizer, passwordScheme, bearerScheme)
    }
}

/** `GET /api/v1/livez` and `GET /api/v1/readyz`: the liveness and readiness probes. */
private fun Route.probeRoutes(deps: UnsecuredDeps) {
    with(deps) {
        route("/api/v1") {
            // Liveness — always 200. Wire `livenessProbe` here. Never wire
            // it to /readyz: a 503 during drain would restart the pod
            // mid-drain and defeat server.shutdownDrainSec.
            get("/livez") {
                call.respond(LivenessResponse())
            }
            // Readiness — 503 once ApplicationStopping fires, 200 otherwise.
            // Wire `readinessProbe` and any upstream reverse-proxy health
            // check here so the LB removes this pod from rotation as soon
            // as drain starts while in-flight jobs finish.
            get("/readyz") {
                if (isDraining.get()) {
                    call.respond(HttpStatusCode.ServiceUnavailable, ReadinessResponse(status = "draining"))
                } else {
                    call.respond(ReadinessResponse(status = "ready"))
                }
            }
        }
    }
}

/** `POST /api/v1/auth/login` and `POST /api/v1/auth/logout`: the session-cookie handshake. */
private fun Route.sessionAuthRoutes(
    deps: UnsecuredDeps,
    passwordScheme: AuthConfig.Password,
) {
    with(deps) {
        route("/api/v1/auth") {
            rateLimit(RateLimitName("login")) {
                post("/login") {
                    val req = call.receiveOr400<LoginRequest>("Invalid login body: expected { username, password }")
                    if (!credentialsMatch(passwordVerifier, passwordScheme, req)) {
                        call.respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Invalid credentials"))
                        return@post
                    }
                    val now =
                        java.time.Instant
                            .now()
                            .epochSecond
                    call.sessions.set(
                        HomerSession(
                            username = req.username,
                            exp = now + passwordScheme.sessionTtlMin.toLong() * 60L,
                        ),
                    )
                    call.respond(LoginResponse(username = req.username))
                }
            }
            post("/logout") {
                call.sessions.clear<HomerSession>()
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/**
 * `GET /api/v1/auth/me`: the username, role and grants of the caller's own principal — the bearer
 * token's when the request carries one and [bearerScheme] is configured, else the session cookie's.
 */
private fun Route.meRoute(
    authorizer: Authorizer,
    passwordScheme: AuthConfig.Password?,
    bearerScheme: AuthConfig.Bearer?,
) {
    get("/api/v1/auth/me") {
        val credential = bearerCredential(call.request.headers[HttpHeaders.Authorization])
        when {
            bearerScheme != null && credential != null -> call.respondBearerMe(authorizer, bearerScheme, credential)
            passwordScheme != null -> call.respondSessionMe(passwordScheme)
            else -> call.respond(HttpStatusCode.Unauthorized)
        }
    }
}

/** The `/me` answer for [credential]: `401` with no body, as every secured route refuses it, unless it is a token of [scheme]. */
private suspend fun ApplicationCall.respondBearerMe(
    authorizer: Authorizer,
    scheme: AuthConfig.Bearer,
    credential: String,
) {
    val principal = scheme.principalFor(credential)
    val held = principal?.let(authorizer::principalGrants)
    if (principal == null || held == null) {
        respond(HttpStatusCode.Unauthorized)
        return
    }
    respond(MeResponse(username = principal, role = if (held.admin) "admin" else null, grants = held.grants))
}

/** The `/me` answer for the session cookie: its user's role and grants, or `401` when it is absent, expired or revoked. */
private suspend fun ApplicationCall.respondSessionMe(passwordScheme: AuthConfig.Password) {
    val session = sessions.get<HomerSession>()
    if (session == null ||
        session.exp <=
        java.time.Instant
            .now()
            .epochSecond
    ) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "Not authenticated"))
        return
    }
    val user = passwordScheme.users[session.username]
    if (user == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponse(msg = "User no longer exists"))
        return
    }
    respond(MeResponse(username = session.username, role = user.role, grants = user.grants))
}

/**
 * Whether [req] names a user of [passwordScheme] and that user's password. Constant-time-ish: when
 * the user does not exist, still run a bcrypt verify against a dummy hash so a 401 takes the same
 * wall-clock time as wrong-password. The dummy is bound to the configured `bcryptCost` (memoized in
 * PasswordVerifier) — a fixed cost-12 constant would leak which usernames exist whenever the
 * operator deviates from 12.
 */
private fun credentialsMatch(
    passwordVerifier: PasswordVerifier,
    passwordScheme: AuthConfig.Password,
    req: LoginRequest,
): Boolean {
    val user = passwordScheme.users[req.username]
    return if (user == null) {
        passwordVerifier.verify(
            req.password,
            passwordVerifier.dummyHashAtCost(passwordScheme.bcryptCost),
        )
        false
    } else {
        passwordVerifier.verify(req.password, user.passwordHash)
    }
}
