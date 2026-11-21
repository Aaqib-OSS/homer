package homer.app.models

import kotlinx.serialization.Serializable

/** The last agent scan's load errors: at most the server's cap, with the dropped remainder in [truncated]. */
@Serializable
data class AgentLoadErrorsResponse(
    val errors: List<String>,
    val truncated: Int,
)
