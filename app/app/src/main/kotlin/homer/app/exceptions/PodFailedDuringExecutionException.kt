package homer.app.exceptions

import homer.app.models.PodTermination

/**
 * A worker pod vanished or failed terminally mid-command, or the exec channel into it was lost.
 * A channel-lost [message] already opens with the lost-channel sentence, so it gets no prefix.
 */
class PodFailedDuringExecutionException(
    val reason: String,
    message: String,
    val events: List<String>,
    cause: Throwable? = null,
    val termination: PodTermination? = null,
    stoppedSummary: String? = null,
    channelLost: Boolean = false,
) : RuntimeException(
        when {
            stoppedSummary != null -> "Kubernetes stopped the pod: $stoppedSummary"
            channelLost -> message
            else -> "Pod failed during execution ($reason): $message"
        },
        cause,
    )
