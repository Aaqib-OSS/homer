package homer.app.models

/**
 * Character sets and ceilings for run subject tags, shared by `fromInputs` derivation, agent-load
 * validation, the runtime-tag callback route and the `?tag=` cap on `/status/all`. ADR-0060.
 */
internal object TagValidation {
    const val MAX_TAG_VALUE_LENGTH = 128

    /**
     * A tag value: `^[A-Za-z0-9._-]{1,128}$`, colon-free so `key:value` splits on its first `:`.
     * An off-charset or oversized value is dropped, never truncated or rewritten. ADR-0060.
     */
    val VALUE_PATTERN = Regex("^[A-Za-z0-9._-]{1,$MAX_TAG_VALUE_LENGTH}$")

    /**
     * A tag key (`fromInputs` paramName or `runtime` key): `^[A-Za-z0-9._-]{1,64}$`, so a stored
     * `key:value` stays within [MAX_TAG_QUERY_LENGTH] and URL-safe. ADR-0060, CWE-400.
     */
    val KEY_PATTERN = Regex("^[A-Za-z0-9._-]{1,64}$")

    /** Per-run ceiling on runtime-declared tags; a write past it is refused (400). ADR-0047 rule 4. */
    const val MAX_RUNTIME_TAGS_PER_RUN = 20

    /**
     * Per-span ceiling on exported trace tags, equal to [MAX_RUNTIME_TAGS_PER_RUN]; stored `?tag=`
     * rows keep every tag, only the export truncates. ADR-0060, CWE-400.
     */
    const val MAX_TRACE_TAGS = MAX_RUNTIME_TAGS_PER_RUN

    /** Per-request ceiling on `?tag=` params on `/status/all`; over it is refused (400). ADR-0047 rule 4. */
    const val MAX_TAG_QUERY_PARAMS = 10

    /**
     * Longest a stored `key:value` tag can be (key 64 + `:` + value 128); a longer `?tag=` value
     * matches nothing and is refused (400) before the DB bind. ADR-0060, CWE-400.
     */
    const val MAX_TAG_QUERY_LENGTH = 64 + 1 + MAX_TAG_VALUE_LENGTH

    /**
     * Shortest secret the tag guards match against, at write time and at read time; same floor
     * as the artifact-stem screen. ADR-0060.
     */
    const val SECRET_NEEDLE_MIN_LENGTH = MIN_SCREENED_SECRET_LENGTH
}
