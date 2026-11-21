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
 * Route-scoped CSRF guard inside `authenticate`, on [AuthenticationChecked] (an `onCall` hook sees a null principal).
 * Guards every method of `session:` principals by requiring [CSRF_HEADER], which browsers cannot set cross-origin;
 * Bearer/HMAC and pre-login calls pass through. See ADR-0016.
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
