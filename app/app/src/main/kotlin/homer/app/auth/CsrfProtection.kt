package homer.app.auth

import homer.app.exceptions.CsrfValidationException
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.header

/** Custom request header required on cookie/session-authenticated requests. */
const val CSRF_HEADER = "X-Homer-CSRF"

/**
 * Route-scoped CSRF guard, installed inside the `authenticate` block. The [AuthenticationChecked]
 * hook runs after the principal is populated (an `onCall` hook would see a null principal). Scoped
 * to `session:` principals: browsers auto-attach the session cookie cross-site but cannot set a
 * custom header cross-origin, so requiring [CSRF_HEADER] blocks the drive-by surface. Bearer/HMAC
 * and pre-login calls are a no-op (not browser-ambient), so the GET webhook/curl trigger keeps
 * working; guarding *all* session methods also covers GET-execute and SSE-GET. See ADR-0016.
 */
val CsrfProtection =
    createRouteScopedPlugin("CsrfProtection") {
        on(AuthenticationChecked) { call ->
            val principalName = call.principal<UserIdPrincipal>()?.name
            if (principalName?.startsWith(SESSION_PRINCIPAL_PREFIX) == true && call.request.header(CSRF_HEADER).isNullOrBlank()) {
                throw CsrfValidationException(
                    "Missing required $CSRF_HEADER header on a session-authenticated request " +
                        "(cookie-auth CSRF protection; see ADR-0016).",
                )
            }
        }
    }
