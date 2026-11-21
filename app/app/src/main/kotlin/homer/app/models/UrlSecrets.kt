package homer.app.models

import java.net.URI
import java.net.URISyntaxException

/**
 * Drops userinfo, query and fragment from a URL, keeping scheme, host, port and path; null when
 * the value will not parse. Applied to `langfuse.serverUrl` in the OTLP log line and in
 * `langfuseTraceUrl`, so a credential in the configured URL leaks to neither. ADR-0050.
 */
internal fun stripUrlSecrets(raw: String): String? =
    try {
        val uri = URI(raw)
        if (uri.userInfo == null && uri.query == null && uri.fragment == null) {
            raw
        } else {
            // Rebuild from the raw authority: `URI.host` is null for a reg-name host such as
            // `homer_internal` and would drop it. CWE-522.
            val rawAuthority = uri.rawAuthority
            val authority = rawAuthority?.substringAfterLast('@')
            URI(uri.scheme, authority, uri.path, null, null).toString()
        }
    } catch (_: URISyntaxException) {
        null
    }
