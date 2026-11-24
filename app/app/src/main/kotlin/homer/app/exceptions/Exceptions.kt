package homer.app.exceptions

import homer.app.models.RunnerFilter
import kotlin.time.Duration

sealed class HomerException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class AgentException(
    message: String,
    val agentName: String,
    cause: Throwable? = null,
) : HomerException("Agent '$agentName': $message", cause)

class CommandExecutionException(
    message: String,
    val exitCode: Int,
    val command: String,
) : HomerException("Command '$command' failed with exit code $exitCode: $message")

class ConfigurationException(
    message: String,
    cause: Throwable? = null,
) : HomerException("Configuration error: $message", cause)

class ValidationException(
    message: String,
    val field: String? = null,
) : HomerException(
        if (field != null) {
            "Validation error for '$field': $message"
        } else {
            "Validation error: $message"
        },
    )

// A `session:` auth request lacks the CSRF header; token/HMAC callers are exempt. Maps to 403.
// See ADR-0016.
class CsrfValidationException(
    message: String,
) : HomerException(message)

class WorkspaceException(
    message: String,
    cause: Throwable? = null,
) : HomerException("Workspace error: $message", cause)

class AnotherProcessStillActive(
    val activeProcessId: Int,
) : HomerException("The process $activeProcessId is still active, stopping current")

// A lock-key `{{NAME}}` the run's env does not hold: denied before a slot, lock or workspace.
// [placeholders] carries the unresolved names only — never the rendered key or a value. See ADR-0098.
class UnresolvedLockKeyPlaceholderException(
    names: Collection<String>,
) : HomerException(denialMessage(namedPlaceholders(names))) {
    val placeholders: List<String> = namedPlaceholders(names)
}

/** Bounds how many names a denial echoes: a key with more is still denied, just not fully named. */
private const val MAX_NAMED_PLACEHOLDERS = 5

/** Bounds each name, so the echoed sentence stays short whatever a template's placeholder is called. */
private const val MAX_PLACEHOLDER_NAME_LENGTH = 50

private fun namedPlaceholders(names: Collection<String>): List<String> =
    names
        .distinct()
        .sorted()
        .take(MAX_NAMED_PLACEHOLDERS)
        .map { it.take(MAX_PLACEHOLDER_NAME_LENGTH) }

// Shared by the denial's log sentence and the stored pre-start failure reason, so the two cannot
// drift. The names come from the agent definition, never from the rendered key or a value. See ADR-0098.
internal fun placeholderSentence(names: List<String>): String =
    if (names.size == 1) {
        "placeholder {{${names.single()}}} has no value"
    } else {
        "placeholders ${names.joinToString(", ") { "{{$it}}" }} have no value"
    }

private fun denialMessage(names: List<String>): String = "Lock key ${placeholderSentence(names)}"

class MaxParallelRunsExceededException(
    val agentName: String,
    val maxParallelRuns: Int,
) : HomerException("Agent '$agentName' has reached the maximum of $maxParallelRuns parallel runs")

// Under WAIT: each waiting run holds a non-evictable row, so an unbounded queue on one agent could
// exhaust global process capacity. The surplus run fails instead of parking.
class MaxQueuedRunsExceededException(
    val agentName: String,
    val maxQueuedRuns: Int,
) : HomerException("Agent '$agentName' has reached the maximum of $maxQueuedRuns queued runs")

// A lock-waiting run holds no parallel-run slot, so the wait needs its own local bound or one
// agent's queue could pin just as many rows. The surplus run fails instead of parking. See ADR-0104.
class MaxLockWaitersExceededException(
    val agentName: String,
    val bound: Int,
) : HomerException("Agent '$agentName' has reached the maximum of $bound runs waiting for a lock")

// A start over `server.maxInputBytes` is refused before the run's row exists, so retained input bytes
// are bounded by `maxProcesses` x the cap. Sizes only, never a key or value (CWE-770, ADR-0047 rule 4).
class RunInputsTooLargeException(
    val maxBytes: Long,
    val actualBytes: Long,
) : HomerException("Run inputs exceed the server limit of $maxBytes bytes (got $actualBytes)")

// Bounds RunAgent chain depth, so recursion cannot burn unbounded tokens. The chain is threaded
// across HTTP hops via the X-Homer-Invocation-Chain header. See ADR-0008.
class InvocationDepthExceededException(
    val agentName: String,
    val maxDepth: Int,
    val chain: List<String>,
) : HomerException(
        "Agent '$agentName' invocation rejected: chain depth ${chain.size + 1} exceeds " +
            "configured maximum of $maxDepth (chain: ${chain.joinToString(" → ")})",
    )

// A cycle (A -> B -> A) can fire well below the depth cap, so it is denied on its own. Same 422
// mapping as [InvocationDepthExceededException].
class InvocationCycleDetectedException(
    val agentName: String,
    val chain: List<String>,
) : HomerException(
        "Agent '$agentName' invocation rejected: cycle detected — '$agentName' is already " +
            "in the upstream chain (chain: ${chain.joinToString(" → ")})",
    )

// No terminal process can be evicted to make room; mapped to 503 so clients see backpressure
// rather than an OOM under sustained burst load.
class ProcessManagerAtCapacityException(
    val maxProcesses: Int,
) : HomerException(
        "ProcessManager is at capacity ($maxProcesses processes) and no terminal " +
            "processes are available for eviction; refusing new process",
    )

// A harness command that exited 0 but whose stdout is not its documented JSON event stream.
// Fail-closed: the surrounding process is marked FAILED rather than treated as success.
class MalformedLlmOutputException(
    val reason: String,
    cause: Throwable? = null,
) : HomerException("LLM output validation failed: $reason", cause)

// The pinned [server.requireClaudeCodeVersion] differs from the reported one, or none was reported;
// the executor rethrows without consuming retry attempts.
class ClaudeCliVersionMismatchException(
    val expected: String,
    val actual: String?,
) : HomerException(
        "Claude CLI version mismatch: expected '$expected', actual '${actual ?: "<missing>"}' " +
            "(set server.requireClaudeCodeVersion to the version you have validated, or null to disable)",
    )

// The agent's cooldown has not elapsed since its previous run. Maps to 429, like the other
// backpressure refusals. See ADR-0051.
class CooldownActiveException(
    val agentName: String,
    val retryAfterSec: Long,
    reason: String,
) : HomerException(
        "Agent '$agentName' is in cooldown ($reason); retry in ${retryAfterSec}s",
    )

// A `cooldown.key` that does not resolve, or renders empty, too long or outside its charset.
// Fails closed: no shared-key or no-key fallback. See ADR-0106.
class CooldownKeyInvalidException(
    val agentName: String,
    reason: String,
) : HomerException("Agent '$agentName' has an unusable cooldown.key: $reason")

// The rolling cost window would exceed a configured cap. Maps to 429 so callers see backpressure.
class CostCapExceededException(
    val capName: String,
    val limitUsd: Double,
    val currentUsd: Double,
) : HomerException(
        "Cost cap '$capName' exceeded: limit \$$limitUsd, current spend \$$currentUsd",
    )

// StatusPages answers [httpStatusCode] (default 400) with the message as is, unlike
// [ValidationException], which prefixes it.
class QueryParamValidationException(
    message: String,
    val httpStatusCode: io.ktor.http.HttpStatusCode = io.ktor.http.HttpStatusCode.BadRequest,
    val paramName: String? = null,
) : HomerException(message)

// Answered as a bare `400` with no body: the contract of the SSE stream endpoints.
class BodylessBadRequestException : HomerException("Bad request")

// A body that is valid JSON but violates the agent's `inputSchema`. Maps to 422.
class PayloadSchemaValidationException(
    message: String,
    val errors: List<String> = emptyList(),
) : HomerException(message)

// A question id that does not exist; a question of another process maps here too, on purpose, so
// ids cannot be probed. Maps to 404. See ADR-0047 rule 3.
class QuestionNotFoundException(
    val questionId: String,
) : HomerException("Question '$questionId' not found")

// Answering a question no longer OPEN. Maps to 409.
class QuestionNotOpenException(
    val questionId: String,
    val status: String,
) : HomerException("Question '$questionId' is $status, not OPEN")

// A command asking for a process not accepting questions (terminal, or never registered). Maps to 409.
class QuestionsClosedForProcessException(
    val processId: Int,
) : HomerException("Process $processId is not accepting questions")

// A process at `server.questions.maxOpenPerProcess`. Maps to 429.
class TooManyOpenQuestionsException(
    val processId: Int,
    val maxOpen: Int,
) : HomerException("Process $processId already has $maxOpen open questions")

// An operator message sent to a run that ended (or never existed). Maps to 409. See ADR-0097.
class RunNotAcceptingMessagesException(
    val processId: Int,
) : HomerException("Process $processId is no longer accepting messages")

// A run already storing `server.messages.maxPerRun` messages. Maps to 429.
class TooManyRunMessagesException(
    val processId: Int,
    val maxMessages: Int,
) : HomerException("Process $processId already has $maxMessages messages")

// Bounds the connections/coroutines a workspace command can pin open (each holds for up to
// `maxWaitSec`). Maps to 429.
class TooManyConcurrentWaitsException(
    val processId: Int,
    val maxWaits: Int,
) : HomerException("Process $processId already has $maxWaits answer-waits in flight")

/** Raised when a dispatch targets an agent that does not exist, or one the hop is not authorized
 *  to reach — the two are indistinguishable by design (ADR-0047 rule 3). Maps to 404. */
class DispatchAgentNotFoundException(
    val agent: String,
) : HomerException("Dispatch target agent '$agent' not found")

/** Raised when ask-and-dispatch targets an agent that exists but has not opted in
 *  (`agent.dispatch.enabled` is not true). Maps to 403 Forbidden. */
class AgentNotDispatchableException(
    val agent: String,
) : HomerException("Agent '$agent' is not an allowed dispatch target (set dispatch.enabled: true)")

/** Raised when a dispatch id does not exist. Maps to 404. */
class DispatchNotFoundException(
    val id: Int,
) : HomerException("Dispatch '$id' not found")

/** Raised when cancelling a dispatch that is not PENDING. Maps to 409. */
class DispatchNotPendingException(
    val id: Int,
    val status: String,
) : HomerException("Dispatch '$id' is $status, not PENDING")

/** Raised at the global PENDING-dispatch cap. Maps to 429. */
class TooManyPendingDispatchesException(
    val maxPending: Int,
) : HomerException("Already at the maximum of $maxPending pending dispatches")

/** A dispatch group's size or aggregate bound was exceeded. Maps to 400. */
open class DispatchGroupLimitException(
    message: String,
) : HomerException(message)

// More questions than `server.dispatch.maxGroupSize`, refused at create before any row is written.
class DispatchGroupTooLargeException(
    val maxGroupSize: Int,
) : DispatchGroupLimitException("A dispatch group may hold at most $maxGroupSize questions")

// Aggregated answers over the payload bound: the group settles FAILED rather than staying PENDING.
class DispatchGroupAnswersTooLargeException(
    val maxBytes: Int,
) : DispatchGroupLimitException("Aggregated dispatch-group answers exceed the $maxBytes-byte bound")

/** Raised when a continuation id does not exist. Maps to 404. */
class ContinuationNotFoundException(
    val id: Int,
) : HomerException("Continuation '$id' not found")

/** Raised when cancelling a continuation that is not PENDING. Maps to 409. */
class ContinuationNotPendingException(
    val id: Int,
    val status: String,
) : HomerException("Continuation '$id' is $status, not PENDING")

/** Raised at the global PENDING-continuation cap. Maps to 429. */
class TooManyPendingContinuationsException(
    val maxPending: Int,
) : HomerException("Already at the maximum of $maxPending pending continuations")

// No runner claimed the run within its claim TTL. [publicReason] is [reason] without the
// admin-only runner alias (ADR-0077); both are required. See ADR-0066.
class RunnerClaimTimeoutException(
    val timeout: Duration,
    val filter: RunnerFilter,
    val reason: String,
    val publicReason: String,
) : HomerException("No runner claimed the run within ${timeout.inWholeSeconds} s (${filter.describe()}): $reason")

/** The claim queue is at `server.maxPendingRunnerClaims`; the run fails without waiting. See ADR-0047 rule 4. */
class ClaimQueueFullException(
    val maxPending: Int,
) : HomerException("Runner claim queue is full ($maxPending pending claims)")

// The maintenance start queue is at a bound; the start is shed rather than accumulated.
// [perPrincipal] says which bound tripped (the total cap or this caller's share). See ADR-0047 rules 4-5, ADR-0092.
class MaintenanceQueueFullException(
    val bound: Int,
    val perPrincipal: Boolean = false,
) : HomerException("Maintenance mode is on and the start queue is full (bound: $bound queued starts)")

// A queued start waited past `server.maintenance.queueWaitSec`, or the queue was released on
// shutdown. Maps to 503. See ADR-0047 rule 5, ADR-0092.
class MaintenanceWaitTimeoutException(
    timeout: Duration,
) : HomerException("Maintenance mode is on and the start did not leave the queue within ${timeout.inWholeSeconds} s")

// A start outside the work the drain already admitted is shed, not queued. Maps to 503.
// See ADR-0047 rule 5, ADR-0102.
class ServerDrainingException : HomerException("Server is shutting down and is not accepting new runs")

/** A pull-mode runner instance announced something the domain does not accept (tag shape, `allowedTags`, `concurrency` cap). Maps to 422. */
class RunnerRegistrationRejectedException(
    domain: String,
    reason: String,
    cause: Throwable? = null,
) : HomerException("Runner domain '$domain' rejected the registration: $reason", cause)

/** Raised at `maxInstances` for a domain. Maps to 429. */
class TooManyRunnerInstancesException(
    domain: String,
    maxInstances: Int,
) : HomerException("Runner domain '$domain' already has $maxInstances registered instances")

/** Raised when an instance opens more concurrent claim polls than its declared `concurrency`. Maps to 429. */
class TooManyRunnerPollsException(
    instance: String,
    concurrency: Int,
) : HomerException("Runner instance '$instance' already has $concurrency claim polls open")

// Bounds the connections and coroutines one instance can park on a single session. Maps to 429.
class TooManySessionPollsException(
    instance: String,
    maxPolls: Int,
) : HomerException("Runner instance '$instance' already has $maxPolls next polls open on this session")

// A second transfer for the same op while one is in flight. Maps to 429.
class TooManyRunnerTransfersException(
    instance: String,
) : HomerException("Runner instance '$instance' already has this transfer in flight")

/** An output chunk arrived out of order (gap or repeat). Maps to 409. */
class RunnerOutputSequenceException(
    expected: Long,
    got: Long,
) : HomerException("Output chunk sequence mismatch: expected $expected, got $got")

/** A posted tar exceeded the domain's `maxTransferBytes`. Maps to 413. */
class RunnerTransferTooLargeException(
    maxBytes: Long,
) : HomerException("Transfer exceeds the domain limit of $maxBytes bytes")

// A transfer handler outran the domain's `transferTimeoutSec`. The handler owns this bound; the
// one in `ExternalRunnerSession.await` is a different component's (ADR-0047 rule 4). Maps to 408.
class RunnerTransferTimeoutException(
    domain: String,
    timeoutSec: Long,
) : HomerException("Runner domain '$domain' transfer exceeded ${timeoutSec}s")
