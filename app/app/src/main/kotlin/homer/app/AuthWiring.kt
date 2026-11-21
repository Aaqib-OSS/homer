package homer.app

import homer.app.auth.bearerCredential
import homer.app.auth.installPasswordSessions
import homer.app.auth.passwordSessionProvider
import homer.app.auth.principalFor
import homer.app.infra.HmacReplayCache
import homer.app.infra.HmacVerifier
import homer.app.infra.PasswordVerifier
import homer.app.models.AuthConfig
import homer.app.models.AuthConfig.Bearer
import homer.app.routes.ApiDeps
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("homer.app.Application")

/**
 * Outcome of [configureAuth]: the registered provider names (in registration
 * order), plus the [AuthConfig.Password] and [AuthConfig.Bearer] schemes and HMAC body-capture attribute
 * key that downstream routing / [ApiDeps] wiring needs to reference.
 */
internal class AuthSetup(
    val authNames: List<String>,
    val passwordScheme: AuthConfig.Password?,
    val bearerScheme: AuthConfig.Bearer?,
    val hmacBodyKey: AttributeKey<ByteArray>,
)

/**
 * Wires a single [Bearer] scheme as the `auth-bearer` provider: presence, then constant-time token match.
 * Authenticates only; grants are enforced at the route. See ADR-0037.
 */
internal fun io.ktor.server.auth.AuthenticationConfig.buildBearerProvider(scheme: Bearer) {
    provider("auth-bearer") {
        authenticate { context ->
            val call = context.call
            val tokenCredential = bearerCredential(call.request.headers[HttpHeaders.Authorization])

            if (tokenCredential == null) {
                context.challenge("BearerAuth", AuthenticationFailedCause.NoCredentials) { challenge, c ->
                    c.respond(HttpStatusCode.Unauthorized)
                    challenge.complete()
                }
                return@authenticate
            }

            val principal = scheme.principalFor(tokenCredential)

            if (principal == null) {
                context.challenge("BearerAuth", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                    c.respond(HttpStatusCode.Unauthorized)
                    challenge.complete()
                }
                return@authenticate
            }

            context.principal(UserIdPrincipal(principal))
        }
    }
}

/**
 * Wires a single [AuthConfig.Hmac] scheme as the `auth-hmac` provider: verifies the body captured under
 * [hmacBodyKey] (empty if absent) via [hmacVerifier]. Authenticates only; grants are enforced at the route.
 * See ADR-0037.
 */
internal fun io.ktor.server.auth.AuthenticationConfig.buildHmacProvider(
    scheme: AuthConfig.Hmac,
    hmacVerifier: HmacVerifier,
    hmacBodyKey: AttributeKey<ByteArray>,
) {
    provider("auth-hmac") {
        authenticate { context ->
            val call = context.call
            val body = call.attributes.getOrNull(hmacBodyKey) ?: ByteArray(0)
            val result =
                hmacVerifier.verify(
                    keys = scheme.keys,
                    method = call.request.httpMethod.value,
                    path = call.request.path(),
                    query = call.request.queryString(),
                    headers = { name -> call.request.headers[name] },
                    rawBody = body,
                )
            when (result) {
                is HmacVerifier.Result.Rejected -> {
                    context.challenge("HmacAuth", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                        c.respond(HttpStatusCode.Unauthorized)
                        challenge.complete()
                    }
                }

                is HmacVerifier.Result.Authenticated -> {
                    context.principal(UserIdPrincipal("hmac:${result.keyId}"))
                }
            }
        }
    }
}

/**
 * The `HmacBodyCapture` plugin reads the body of every body-carrying request (except runner-protocol paths)
 * under [hmacBodyKey] for `auth-hmac`. Needs DoubleReceive and itself installed BEFORE Authentication
 * (see [configureAuth]).
 */
internal fun hmacBodyCapturePlugin(hmacBodyKey: AttributeKey<ByteArray>) =
    createApplicationPlugin(name = "HmacBodyCapture") {
        onCall { call ->
            // Runner-protocol routes sit outside authenticate(...) (ADR-0066): no HMAC provider can satisfy them, and
            // capturing their body would let an anonymous POST buffer up to maxTransferBytes into heap. Skip them.
            if (call.request.path().startsWith("/api/v1/runner/")) return@onCall
            val m = call.request.httpMethod
            val hasBody =
                m == HttpMethod.Post ||
                    m == HttpMethod.Put ||
                    m == HttpMethod.Patch ||
                    m == HttpMethod.Delete
            if (hasBody) {
                try {
                    call.attributes.put(hmacBodyKey, call.receive<ByteArray>())
                } catch (_: Throwable) {
                    // Body not readable; HMAC sees empty bytes and rejects.
                }
            }
        }
    }

/**
 * Installs Sessions (Password scheme), the HMAC body-capture plugins (Hmac scheme) and Authentication with one
 * provider per scheme. Order is load-bearing: Sessions, DoubleReceive and HmacBodyCapture BEFORE Authentication.
 * Returns provider names in registration order, the Password and Bearer schemes and the HMAC body key.
 */
internal fun Application.configureAuth(
    schemes: List<AuthConfig>,
    passwordVerifier: PasswordVerifier,
): AuthSetup {
    // Sessions BEFORE Authentication: the auth-session provider reads call.sessions. Combined.init guarantees at
    // most one Password scheme. ADR-0011.
    val passwordScheme = schemes.filterIsInstance<AuthConfig.Password>().singleOrNull()
    val bearerScheme = schemes.filterIsInstance<AuthConfig.Bearer>().singleOrNull()
    installPasswordAuthSessions(passwordScheme, passwordVerifier)

    val hmacBodyKey = AttributeKey<ByteArray>("HmacBody")

    if (schemes.any { it is AuthConfig.Hmac }) {
        install(DoubleReceive)
        install(hmacBodyCapturePlugin(hmacBodyKey))
    }

    val authNames = installAuthProviders(schemes, hmacBodyKey)

    return AuthSetup(authNames = authNames, passwordScheme = passwordScheme, bearerScheme = bearerScheme, hmacBodyKey = hmacBodyKey)
}

/**
 * With a [passwordScheme], installs its session plugin and pre-warms [passwordVerifier]'s dummy hash
 * at its cost, warning when that cost is operationally fragile.
 */
private fun Application.installPasswordAuthSessions(
    passwordScheme: AuthConfig.Password?,
    passwordVerifier: PasswordVerifier,
) {
    if (passwordScheme != null) {
        installPasswordSessions(passwordScheme)
        // Pre-warm the dummy hash at the configured cost, so the first unknown-user login is as
        // timing-stable as later ones.
        passwordVerifier.dummyHashAtCost(passwordScheme.bcryptCost)
        // Per-IP login limiter defaults to 5/min, so a bcryptCost above ~13 (about 8 s/verify) lets a burst block
        // legitimate logins for over a minute; ADR-0011 permits 4..15, so warn above 13.
        if (passwordScheme.bcryptCost > 13) {
            logger.warn(
                "AuthConfig.Password.bcryptCost = {} is operationally fragile: each verify takes seconds, " +
                    "so the per-IP login limiter at 5/min can starve legitimate users under a burst of attempts. " +
                    "Stay at 12 (default) unless you understand the tradeoff.",
                passwordScheme.bcryptCost,
            )
        }
    }
}

/**
 * Installs the Authentication plugin, when there are [schemes] (already flattened), with one
 * provider per scheme, and returns the provider names in registration order.
 */
private fun Application.installAuthProviders(
    schemes: List<AuthConfig>,
    hmacBodyKey: AttributeKey<ByteArray>,
): List<String> {
    val hmacVerifier = HmacVerifier(replayCache = HmacReplayCache())
    val authNames = mutableListOf<String>()

    if (schemes.isNotEmpty()) {
        install(Authentication) {
            schemes.forEach { scheme ->
                when (scheme) {
                    is Bearer -> {
                        buildBearerProvider(scheme)
                        authNames += "auth-bearer"
                    }

                    is AuthConfig.Hmac -> {
                        buildHmacProvider(scheme, hmacVerifier, hmacBodyKey)
                        authNames += "auth-hmac"
                    }

                    is AuthConfig.Password -> {
                        passwordSessionProvider("auth-session", scheme)
                        authNames += "auth-session"
                    }

                    is AuthConfig.Combined,
                    is AuthConfig.Unsecured,
                    -> {
                        error("unreachable: flatten() removes Combined and Unsecured")
                    }
                }
            }
        }
    }

    return authNames
}
