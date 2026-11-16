package homer.app

/**
 * HTTP header names of the Homer API, modelled on [EnvConstants]: one kernel object every layer may
 * import, so a name cannot be mistyped at a call site and silently break a client contract.
 *
 * The strings are a published surface — a renamed header stops reaching existing clients and egress
 * proxies, so every value here is pinned by a test. Canonical for new code: the
 * `EnvConstants.*_HEADER` constants are transitional aliases, kept only while their call sites migrate.
 */
object HomerHeaders {
    /** Response header of `GET /api/v1/artifacts`: the run's current status name. */
    const val PROCESS_STATUS = "X-Process-Status"

    /** Response header of `GET /api/v1/artifacts`: `true` once the file can no longer grow. */
    const val FILE_COMPLETE = "X-File-Complete"

    /**
     * Request header an external caller of `POST /api/v1/agent/{name}` sets to link the new run to a
     * parent it can see. Read through `EnvConstants.PARENT_PROCESS_HEADER`. See ADR-0058.
     */
    const val PARENT_PROCESS_ID = "X-Homer-Parent-Process-Id"

    /**
     * Request header carrying a run's callback token to `GET /api/v1/callback/whoami`, read before
     * `Authorization`: at an egress proxy that header belongs to the upstream.
     * Read through `EnvConstants.CALLBACK_TOKEN_HEADER`. See ADR-0110.
     */
    const val CALLBACK_TOKEN = "X-Homer-Callback-Token"

    /**
     * Response headers of `GET /api/v1/callback/whoami`: the identified run's agent and id.
     * Read through `EnvConstants.AGENT_HEADER` / `EnvConstants.PROCESS_ID_HEADER`. See ADR-0110.
     */
    const val AGENT = "X-Homer-Agent"
    const val PROCESS_ID = "X-Homer-Process-Id"
}
