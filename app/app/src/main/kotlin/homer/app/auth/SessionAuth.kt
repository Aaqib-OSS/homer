package homer.app.auth

import homer.app.models.AuthConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.response.respond
import io.ktor.server.sessions.SessionTransportTransformer
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.serialization.KotlinxSessionSerializer
import io.ktor.server.sessions.sessions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Payload of the HttpOnly session cookie from `POST /api/v1/auth/login`. HMAC-signed, not encrypted, so the
 * holder can read it: never put a secret in. `ver` lets the provider reject old payloads after a format change.
 * See ADR-0011.
 */
@Serializable
data class HomerSession(
    val username: String,
    val exp: Long,
    val ver: Int = 1,
)

/**
 * Prefix on the [UserIdPrincipal] name of a cookie/session login, so guards (CSRF, rate-limit keying) tell it
 * from bearer/HMAC. Reserved: Bearer/HMAC config keys may not start with it. See ADR-0016.
 */
const val SESSION_PRINCIPAL_PREFIX = "session:"

/**
 * `SessionTransportTransformer` that signs with `currentKey` and accepts `currentKey` or `previousKey` (Ktor's MAC
 * transformer is single-key). Rotation: set `sessionSecretPrevious` to the old and `sessionSecret` to the new,
 * deploy, wait `sessionTtlMin`, drop the previous. See ADR-0011.
 */
internal class MultiKeyMacTransformer(
    private val current: SessionTransportTransformerMessageAuthentication,
    private val previous: SessionTransportTransformerMessageAuthentication?,
) : SessionTransportTransformer {
    override fun transformRead(transportValue: String): String? =
        current.transformRead(transportValue) ?: previous?.transformRead(transportValue)

    override fun transformWrite(transportValue: String): String = current.transformWrite(transportValue)
}

/**
 * Install the `Sessions` plugin for password auth. Must be called BEFORE
 * `install(Authentication)` — Ktor enforces that order at plugin-install time.
 */
fun Application.installPasswordSessions(scheme: AuthConfig.Password) {
    val current =
        SessionTransportTransformerMessageAuthentication(
            scheme.sessionSecret.toByteArray(Charsets.UTF_8),
            "HmacSHA256",
        )
    val previous =
        scheme.sessionSecretPrevious?.let { prev ->
            SessionTransportTransformerMessageAuthentication(
                prev.toByteArray(Charsets.UTF_8),
                "HmacSHA256",
            )
        }
    install(Sessions) {
        cookie<HomerSession>(scheme.cookieName) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = scheme.cookieSecure
            cookie.maxAgeInSeconds = scheme.sessionTtlMin.toLong() * 60L
            cookie.extensions["SameSite"] = scheme.cookieSameSite.cookieValue
            serializer = KotlinxSessionSerializer(HomerSession.serializer(), Json)
            transform(MultiKeyMacTransformer(current, previous))
        }
    }
}

/**
 * Registers the `auth-session` provider in the `install(Authentication)` block; authenticates only, grants are
 * enforced at the route (ADR-0037). A missing or unsigned session, an expired `exp`, or a user absent from `users`
 * (revoked since login) gives 401. The caller adds `providerName` to `authNames`.
 */
fun AuthenticationConfig.passwordSessionProvider(
    providerName: String,
    scheme: AuthConfig.Password,
) {
    provider(providerName) {
        authenticate { context ->
            val call = context.call
            val session = call.sessions.get<HomerSession>()
            if (session == null) {
                context.challenge("SessionAuth", AuthenticationFailedCause.NoCredentials) { challenge, c ->
                    c.respond(HttpStatusCode.Unauthorized)
                    challenge.complete()
                }
                return@authenticate
            }
            if (session.exp <= Instant.now().epochSecond) {
                context.challenge("SessionAuth", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                    c.respond(HttpStatusCode.Unauthorized)
                    challenge.complete()
                }
                return@authenticate
            }
            val user = scheme.users[session.username]
            if (user == null) {
                context.challenge("SessionAuth", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                    c.respond(HttpStatusCode.Unauthorized)
                    challenge.complete()
                }
                return@authenticate
            }
            context.principal(UserIdPrincipal("$SESSION_PRINCIPAL_PREFIX${session.username}"))
        }
    }
}
