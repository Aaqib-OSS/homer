package homer.app.services

import homer.app.EnvConstants
import homer.app.models.CreateJoinRequest
import homer.app.models.CreateJoinResponse
import homer.app.models.CreateRunRequest
import homer.app.models.CreateRunResponse
import homer.app.models.JoinSubSpec
import homer.app.models.ResultEnvParam
import homer.app.models.ResultTransformer
import homer.app.models.SrcType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.Logger

/**
 * The start path of a continuation: the two callback bodies that dispatch the run(s) and register the
 * continuation watching them, with [ContinuationService] owning the row and the fire path. Split out
 * of that class so neither exceeds the size limit (root CLAUDE.md §2). See ADR-0023, ADR-0026.
 */
internal class ContinuationStartService(
    private val continuations: ContinuationService,
    private val dispatchService: DispatchService,
    private val processManager: ProcessManager,
    private val logger: Logger,
) {
    /**
     * Starts the run a `POST /api/v1/callback/runs` body asks for: a plain dispatch when it names no
     * [CreateRunRequest.continueAgent], otherwise the sub plus the completion continuation watching
     * it. Every continuation input is validated BEFORE the sub starts, and a failure only visible
     * after the start (the pending cap) kills the sub, so a 4xx never orphans a run. See ADR-0023.
     */
    suspend fun startWithContinuation(
        originProcessId: Int,
        request: CreateRunRequest,
    ): CreateRunResponse {
        val continueAgent = request.continueAgent
        if (continueAgent == null) {
            return CreateRunResponse(dispatchService.dispatchNow(originProcessId, request.agent, request.params))
        }
        // `params` go to the sub (dispatchNow); `continueParams` (+ the thread id) go to the
        // continuation run — the resumed parent. The LangGraph interrupt id rides here so it
        // reaches the parent, not the useless sub. See ADR-0023.
        val continuationParams = continuationRunParams(request.continueThread, request.continueParams)
        continuations.validate(
            originProcessId = originProcessId,
            target = continueAgent,
            resumeParam = request.resumeParam,
            params = continuationParams,
            ttlSec = request.ttlSec,
        )
        val resultTransformer = request.resultJsonPath?.let { path -> jsonPathResult(path, request.resumeParam) }
        val (dispatchedProcessId, continuationId) =
            killStartedOnFailure { started ->
                val subId = dispatchService.dispatchNow(originProcessId, request.agent, request.params).also { started += it }
                subId to
                    continuations.create(
                        originProcessId = originProcessId,
                        watchedProcessId = subId,
                        target = continueAgent,
                        params = continuationParams,
                        resumeParam = request.resumeParam,
                        resultTransformer = resultTransformer,
                        ttlSec = request.ttlSec,
                    )
            }
        return CreateRunResponse(dispatchedProcessId, continuationId)
    }

    /**
     * Starts the parallel fan-out a `POST /api/v1/callback/joins` body asks for: every sub, plus ONE
     * join continuation resuming the parent after the last of them ends. The join and every sub are
     * validated before the first start, and a failure only visible once subs start kills every sub
     * this request started, so a 4xx cannot orphan one. See ADR-0026.
     */
    suspend fun startJoin(
        originProcessId: Int,
        request: CreateJoinRequest,
    ): CreateJoinResponse {
        val continuationParams = continuationRunParams(request.continueThread, request.continueParams)
        continuations.validateJoin(
            originProcessId = originProcessId,
            target = request.continueAgent,
            subAgents = request.subs.map { it.agent },
            resumeParam = request.resumeParam,
            params = continuationParams,
            ttlSec = request.ttlSec,
        )
        request.subs.forEach { dispatchService.validateDispatchNow(originProcessId, it.agent, it.params) }
        val (watches, continuationId) =
            killStartedOnFailure { started ->
                val watches = request.subs.map { sub -> startJoinSub(originProcessId, sub, request.resumeParam, started) }
                watches to
                    continuations.createJoin(
                        originProcessId = originProcessId,
                        watches = watches,
                        target = request.continueAgent,
                        params = continuationParams,
                        resumeParam = request.resumeParam,
                        ttlSec = request.ttlSec,
                    )
            }
        return CreateJoinResponse(watches.map { it.watchedProcessId }, continuationId)
    }

    /** Starts one join [sub] and records it in [started], with the result transformer its JsonPath asks for. */
    private suspend fun startJoinSub(
        originProcessId: Int,
        sub: JoinSubSpec,
        resumeParam: String,
        started: MutableList<Int>,
    ): JoinWatch {
        val subProcessId = dispatchService.dispatchNow(originProcessId, sub.agent, sub.params).also { started += it }
        return JoinWatch(
            watchedProcessId = subProcessId,
            interruptId = sub.interruptId,
            resultTransformer = sub.resultJsonPath?.let { path -> jsonPathResult(path, resumeParam) },
        )
    }

    /**
     * The params of a continuation run, the resumed parent: [continueParams] plus, when given, the
     * LangGraph [continueThread] the parent resumes. See ADR-0023.
     */
    private fun continuationRunParams(
        continueThread: String?,
        continueParams: Map<String, String>,
    ): Map<String, String> =
        buildMap {
            continueThread?.let { put(EnvConstants.LG_THREAD, it) }
            putAll(continueParams)
        }

    /** The transformer reading a sub's result into [envName]: the JsonPath [path] applied to its stdout. */
    private fun jsonPathResult(
        path: String,
        envName: String,
    ): ResultEnvParam =
        ResultEnvParam(
            src = SrcType.STD_OUT,
            envName = envName,
            transformer = ResultTransformer.JsonPath(path),
        )

    /**
     * Runs [block], which records every sub it starts in the list it is given. If [block] throws
     * (or the request is cancelled), each recorded sub is killed before the exception propagates,
     * so a failed request never leaves a sub running that no continuation tracks. See ADR-0026.
     */
    @Suppress("SuspendFunSwallowedCancellation") // kills the started subs under NonCancellable and rethrows
    private suspend fun <T> killStartedOnFailure(block: suspend (started: MutableList<Int>) -> T): T {
        val started = mutableListOf<Int>()
        try {
            return block(started)
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                started.forEach { subId ->
                    try {
                        processManager.killProcess(subId, "Never registered: the callback request that started it failed")
                    } catch (killError: Exception) {
                        logger.warn("Failed to kill unregistered sub-process {}: {}", subId, killError.message)
                    }
                }
            }
            throw e
        }
    }
}
