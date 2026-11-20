package homer.app.auth

import homer.app.infra.matchesTokenConstantTime
import homer.app.models.AuthConfig

/** The token an `Authorization` header value carries as `Bearer <token>`, or null when it carries none. */
internal fun bearerCredential(authorizationHeader: String?): String? =
    authorizationHeader?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else null }

/** The principal name of the token in this scheme equal to [credential], compared in constant time; null when none is. */
internal fun AuthConfig.Bearer.principalFor(credential: String): String? =
    tokens.entries.firstOrNull { matchesTokenConstantTime(it.value.token, credential) }?.key
