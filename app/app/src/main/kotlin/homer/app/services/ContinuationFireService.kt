package homer.app.services

import homer.app.exceptions.CooldownActiveException
import homer.app.exceptions.CostCapExceededException
import homer.app.exceptions.HomerException
import homer.app.exceptions.MaxLockWaitersExceededException
import homer.app.exceptions.MaxQueuedRunsExceededException
import homer.app.exceptions.ProcessManagerAtCapacityException
import homer.app.exceptions.ServerDrainingException
import homer.app.infra.AgentInputResolver
import homer.app.infra.ResultToEnvMapper
import homer.app.infra.reservedControlParams
import homer.app.models.Agent
import homer.app.models.CommandResult
import homer.app.models.ExecutionInfo
import homer.app.models.ProcessStatus
import homer.app.models.ResultEnvParam
import homer.app.models.ResultRef
import homer.app.models.RunStartRequest
import homer.app.services.persistence.ContinuationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.math.min

/**
 * Fires a continuation once what it watches is terminal: claims the row (PENDING→DISPATCHING CAS), starts
 * the re-checked target with the resume payload, and settles DISPATCHED, deferred or FAILED. Never throws.
 * See ADR-0023, ADR-0026.
 */
internal class ContinuationFireService(
    private val repository: ContinuationRepository,
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    private val agentRunStarter: AgentRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val processManager: ProcessManager,
    private val resultTokenService: ResultTokenService,
    private val logger: Logger,
    private val nowSec: () -> Long,
) {
    /** Fires a single-watch [continuation] whose watched process ended with [status]. */
    suspend fun fire(
        continuation: Continuation,
        status: ProcessStatus,
    ) {
        if (!repository.claim(continuation.id)) return
        val now = nowSec()
        val agent = dispatchableTarget(continuation, now, "Continuation") ?: return
        startTarget(continuation, agent, now, "Continuation") {
            val extracted =
                extractResult(continuation.watchedProcessId, continuation.resultTransformer, continuation.targetAgentName)
            buildResumePayload(status, continuation.watchedProcessId, extracted)
        }
    }

    /**
     * The target agent of a claimed [continuation] while it is still loaded, a dispatch
     * target, and reachable from the origin agent; otherwise the continuation is marked
     * FAILED and null is returned. [label] prefixes the log line.
     */
    private suspend fun dispatchableTarget(
        continuation: Continuation,
        now: Long,
        label: String,
    ): Agent? {
        val agent = agentsService.agents[continuation.targetAgentName]
        val msg =
            if (agent == null) {
                "Target agent '${continuation.targetAgentName}' not found"
            } else if (!authorizer.canDispatchHop(continuation.owner, continuation.originAgentName, continuation.targetAgentName)) {
                "agent '${continuation.originAgentName}' is not authorized to dispatch to '${continuation.targetAgentName}'"
            } else if (agent.dispatch?.enabled != true) {
                "Target agent '${continuation.targetAgentName}' is no longer an allowed dispatch target"
            } else {
                return agent
            }
        repository.markFailed(continuation.id, msg, now)
        logger.warn("$label {} failed: {}", continuation.id, msg)
        return null
    }

    /**
     * Starts [agent] for a claimed [continuation] with the resume payload [payload] builds
     * under [Continuation.resumeParam], and settles the continuation DISPATCHED, deferred or
     * FAILED. [label] prefixes the log lines and the fallback failure messages.
     */
    private suspend fun startTarget(
        continuation: Continuation,
        agent: Agent,
        now: Long,
        label: String,
        payload: suspend () -> String,
    ) {
        try {
            // With `continuation.inheritInputs`, the origin run's stored inputs ride under the params,
            // so a resumed segment keeps its lock-key subject and fromInputs tags. See ADR-0101.
            val inherited =
                inheritedOriginInputs(
                    { processManager.getProcessInfo(continuation.originProcessId) },
                    continuation.originAgentName,
                    continuation.owner,
                    agent,
                    label,
                    logger,
                )
            val inputParams = inherited.params + continuation.params + (continuation.resumeParam to payload())
            // The fired run is the owner's, so its inherited root is gated on the owner's visibility:
            // a foreign flow root reaches no row, env or span (CWE-200, ADR-0058).
            val rootProcessId = processManager.visibleOriginRootProcessId(continuation.originProcessId, continuation.owner, authorizer)
            val processId =
                agentRunStarter.start(
                    RunStartRequest(
                        agent = agent,
                        invocationChain = emptyList(),
                        source = "continuation",
                        owner = continuation.owner,
                        parentProcessId = continuation.originProcessId,
                        rootProcessId = rootProcessId,
                        resolveInputs = {
                            // The inherited values are the origin run's stored, already-transformed
                            // ones — validate them, but never run the transformer a second time.
                            agentInputResolver.resolveFromParams(
                                inputParams,
                                agent,
                                skipTransformFor = inherited.storedParamNames(continuation.params.keys + continuation.resumeParam),
                            ) + reservedControlParams(inputParams)
                        },
                    ),
                    extraSecretInputKeys = continuation.secretInputKeys.orEmpty() + inherited.secretInputKeys,
                    // The origin link is server-derived: a fire whose origin is still `WORKING` is
                    // admitted, not parked. ADR-0092.
                    serverDerivedParent = true,
                )
            repository.markDispatched(continuation.id, processId, now)
            logger.info("$label {} fired -> run {}", continuation.id, processId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomerException) {
            settleFailedFire(continuation, e, "$label failed", now)
        } catch (e: Exception) {
            val msg = e.message ?: "Unexpected ${label.lowercase()} failure"
            repository.markFailed(continuation.id, msg, now)
            logger.warn("$label {} failed to fire (unexpected): {}", continuation.id, msg, e)
        }
    }

    /**
     * Fires a JOIN [continuation] once, claimed like [fire]: the target resumes with one map keyed by
     * interrupt id, each value a single fan-out's payload. Never throws. See ADR-0026.
     */
    suspend fun fireJoin(continuation: Continuation) {
        if (!repository.claim(continuation.id)) return
        val now = nowSec()
        val agent = dispatchableTarget(continuation, now, "Join continuation") ?: return
        startTarget(continuation, agent, now, "Join continuation") {
            val perSub =
                buildMap<String, JsonElement> {
                    for (watch in continuation.watches) {
                        val status = processManager.getProcessStatus(watch.watchedProcessId)
                        val extracted =
                            extractResult(watch.watchedProcessId, watch.resultTransformer, continuation.targetAgentName)
                        // Each entry is the same JSON string a single fanout delivers, kept as a string
                        // value so the parent's homer_fanout decodes its own per-branch payload unchanged.
                        put(
                            watch.interruptId,
                            JsonPrimitive(buildResumePayload(status, watch.watchedProcessId, extracted)),
                        )
                    }
                }
            Json.encodeToString(JsonObject.serializer(), JsonObject(perSub))
        }
    }

    /**
     * The inline result and/or read-by-reference [ResultRef] for a continuation's resume payload;
     * [tooLarge] when the result was dropped for being over [RESULT_TRANSFORM_MAX_BYTES].
     */
    private data class ExtractedResult(
        val result: String?,
        val ref: ResultRef?,
        val tooLarge: Boolean = false,
    )

    /**
     * The [transformer]'s value of [watchedProcessId]'s last stdout, or over [MAX_RESULT_BYTES] a minted
     * [ResultRef] instead. Empty without a transformer or a readable stdout. See ADR-0025.
     */
    private suspend fun extractResult(
        watchedProcessId: Int,
        transformer: ResultEnvParam?,
        consumerAgent: String,
    ): ExtractedResult {
        val none = ExtractedResult(null, null)
        if (transformer == null) return none
        val (execution, artifactsDir) = lastExecution(watchedProcessId) ?: return none
        // stdOut and stdErr are relative to the artifacts dir, as LogTailService resolves them; the
        // fetch endpoint resolves the same relative stdOut path.
        val stdOutRel = execution.stdOut ?: return none
        val stdErrRel = execution.stdErr ?: return none
        val stdOut = artifactsDir.resolve(stdOutRel)
        val stdErr = artifactsDir.resolve(stdErrRel)
        val size = runCatching { stdOut.fileSize() }.getOrNull() ?: return none
        if (size > MAX_RESULT_BYTES) return oversizedResult(watchedProcessId, stdOutRel, transformer, size, consumerAgent)
        val result =
            runCatching {
                ResultToEnvMapper(CommandResult(execution.resultCode, stdOut, stdErr))
                    .mapToEnv(listOf(transformer))[transformer.envName]
            }.getOrNull()
        return ExtractedResult(result, null)
    }

    /** [watchedProcessId]'s last execution and the artifacts dir its paths are relative to; null when either is unknown. */
    private suspend fun lastExecution(watchedProcessId: Int): Pair<ExecutionInfo, Path>? {
        val execution = processManager.getProcessExecutions(watchedProcessId).lastOrNull() ?: return null
        val artifactsDir = processManager.getProcessInfo(watchedProcessId)?.artifactsDir ?: return null
        return execution to artifactsDir
    }

    /**
     * A result over [MAX_RESULT_BYTES] resumes by reference with a minted [ResultRef]; past
     * [RESULT_TRANSFORM_MAX_BYTES], where a ref could never be redeemed, with none and `tooLarge` set.
     */
    private suspend fun oversizedResult(
        watchedProcessId: Int,
        stdOutRel: Path,
        transformer: ResultEnvParam,
        size: Long,
        consumerAgent: String,
    ): ExtractedResult {
        if (size > RESULT_TRANSFORM_MAX_BYTES) {
            // The fetch endpoint answers 413 for a transform this large, so mint no ref and
            // mark the payload `resultTooLarge` instead. ADR-0025.
            logger.warn(
                "Watched process {} result of {} bytes exceeds the transform cap {}; resuming with resultTooLarge",
                watchedProcessId,
                size,
                RESULT_TRANSFORM_MAX_BYTES,
            )
            return ExtractedResult(null, null, tooLarge = true)
        }
        val token =
            resultTokenService.mint(
                watchedProcessId = watchedProcessId,
                path = stdOutRel.toString(),
                transformer = transformer,
                sizeBytes = size,
                consumerAgent = consumerAgent,
            )
        logger.info(
            "Watched process {} result of {} bytes exceeds cap {}; resuming by reference (token minted)",
            watchedProcessId,
            size,
            MAX_RESULT_BYTES,
        )
        return ExtractedResult(
            result = null,
            ref = ResultRef(watchedProcessId, stdOutRel.toString(), token, size),
        )
    }

    private fun buildResumePayload(
        status: ProcessStatus,
        processId: Int,
        extracted: ExtractedResult,
    ): String =
        Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                buildMap {
                    put("status", JsonPrimitive(status.name))
                    put("processId", JsonPrimitive(processId))
                    put("result", extracted.result?.let { JsonPrimitive(it) } ?: JsonNull)
                    if (extracted.ref != null) put("resultRef", Json.encodeToJsonElement(extracted.ref))
                    if (extracted.tooLarge) put("resultTooLarge", JsonPrimitive(true))
                },
            ),
        )

    /**
     * Settles a fire that threw [e]: a transient refusal returns to PENDING for a backed-off retry, bounded
     * by [MAX_FIRE_ATTEMPTS] and the TTL; any other fails at once. See ADR-0023.
     */
    private suspend fun settleFailedFire(
        continuation: Continuation,
        e: HomerException,
        fallbackMessage: String,
        now: Long,
    ) {
        val msg = e.message ?: fallbackMessage
        val attempts = continuation.fireAttempts + 1
        if (!isTransientFireFailure(e) || attempts >= MAX_FIRE_ATTEMPTS) {
            val reason = if (isTransientFireFailure(e)) "$msg (gave up after $attempts attempts)" else msg
            repository.markFailed(continuation.id, reason, now)
            logger.warn("Continuation {} failed to fire: {}", continuation.id, reason)
            return
        }
        val delaySec = retryDelaySec(attempts)
        repository.releaseForRetry(continuation.id, msg, attempts, now + delaySec)
        logger.info(
            "Continuation {} fire deferred (attempt {}/{}, retry in {}s): {}",
            continuation.id,
            attempts,
            MAX_FIRE_ATTEMPTS,
            delaySec,
            msg,
        )
    }

    internal companion object {
        // Inline results over this size resume by reference (result = null) rather than
        // inflating the resume payload / env. See ADR-0023.
        const val MAX_RESULT_BYTES = 64L * 1024

        // A transient failure retries on a delay doubling up to an hour, bounded by MAX_FIRE_ATTEMPTS
        // and the TTL; HookFireService shares it. See ADR-0023, ADR-0107.
        const val MAX_FIRE_ATTEMPTS = 8
        const val RETRY_BASE_DELAY_SEC = 60L
        const val RETRY_MAX_DELAY_SEC = 3600L
        const val BACKOFF_SHIFT_CAP = 30

        /**
         * The one retryability policy of [ContinuationFireService] and [HookFireService]: true for a
         * start-time refusal that reflects momentary server or agent state. See ADR-0023, ADR-0107.
         */
        internal fun isTransientFireFailure(e: HomerException): Boolean =
            e is CooldownActiveException ||
                e is CostCapExceededException ||
                e is ProcessManagerAtCapacityException ||
                e is MaxQueuedRunsExceededException ||
                e is MaxLockWaitersExceededException ||
                // A drain refusal is released for the next boot's sweep, so the fire is not lost. ADR-0102.
                e is ServerDrainingException

        /** The doubling back-off delay before a transient fire failure's attempt number [attempts] (1-based). */
        internal fun retryDelaySec(attempts: Int): Long =
            min(RETRY_MAX_DELAY_SEC, RETRY_BASE_DELAY_SEC shl (attempts - 1).coerceAtMost(BACKOFF_SHIFT_CAP))
    }
}
