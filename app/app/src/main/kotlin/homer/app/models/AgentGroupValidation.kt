package homer.app.models

/**
 * Shape rules for an agent's optional `group` label (one console Agents tab each): any printable text, bounded
 * and free of control characters because it is echoed verbatim onto every process response (CWE-79, CWE-117).
 * Sibling of [RunnerTagValidation].
 */
object AgentGroupValidation {
    /** Longest accepted `group`; bounds the label repeated on every process row (ADR-0047 rule 4). */
    const val MAX_GROUP_LENGTH = 64

    /**
     * Validates an optional [group] and returns it unchanged. [owner] prefixes every
     * message (e.g. `Agent 'deploy'`) so a config-load failure names the offending
     * block. The offending value is never echoed back: it is the untrusted input.
     */
    fun validate(
        owner: String,
        group: String?,
    ): String? {
        if (group == null) return null
        require(group.isNotBlank()) { "$owner: group must not be blank" }
        require(group.length <= MAX_GROUP_LENGTH) {
            "$owner: group must be at most $MAX_GROUP_LENGTH characters, got ${group.length}"
        }
        require(group.none { it.isISOControl() }) { "$owner: group must not contain control characters" }
        return group
    }
}
