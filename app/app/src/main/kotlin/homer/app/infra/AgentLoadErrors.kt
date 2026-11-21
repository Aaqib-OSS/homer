package homer.app.infra

/**
 * The load errors of the last completed agent scan, as the admin endpoint serves them: at most
 * [MAX_KEPT] messages, each one line and at most [MAX_MESSAGE_CHARS] characters. See ADR-0047 rule 4.
 */
internal data class AgentLoadErrors(
    val errors: List<String>,
    val truncated: Int,
) {
    companion object {
        val EMPTY = AgentLoadErrors(emptyList(), 0)

        /** Messages kept per scan; the rest are counted in [truncated] and never stored. */
        const val MAX_KEPT = 500

        /** One message's cap: a YAML parse error quotes its source lines, and a directory name is unbounded. */
        const val MAX_MESSAGE_CHARS = 2048

        /** One scan's [errors], bounded and one-line; a later scan replaces this, never appends to it. */
        fun of(errors: List<String>): AgentLoadErrors {
            if (errors.isEmpty()) return EMPTY
            return AgentLoadErrors(
                errors = errors.take(MAX_KEPT).map(::sanitize),
                truncated = (errors.size - MAX_KEPT).coerceAtLeast(0),
            )
        }

        /** [message] on one line and within [MAX_MESSAGE_CHARS]: it reaches a JSON body and a console panel. */
        private fun sanitize(message: String): String = message.filterNot { it.isISOControl() }.take(MAX_MESSAGE_CHARS)
    }
}
