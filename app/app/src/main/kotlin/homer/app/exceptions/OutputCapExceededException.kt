package homer.app.exceptions

import kotlinx.coroutines.CancellationException

/**
 * A command's stdout or stderr exceeded `server.maxOutputBytes`. A `CancellationException`, so it unwinds
 * like a TTL kill; `AgentExecutor` tells it apart by type to set `ExecutionInfo.outputTruncated`.
 */
class OutputCapExceededException(
    message: String,
) : CancellationException(message)
