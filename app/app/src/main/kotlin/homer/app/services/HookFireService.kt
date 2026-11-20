package homer.app.services

import homer.app.EnvConstants
import homer.app.exceptions.HomerException
import homer.app.infra.AgentInputResolver
import homer.app.infra.coRunCatching
import homer.app.infra.hookControlParams
import homer.app.models.Agent
import homer.app.models.ProcessInfo
import homer.app.models.RunStartRequest
import homer.app.services.persistence.HookRepository
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger

/** Longest `HMR_HOOK_TAGS` value Homer will build; tags past it are dropped, never truncated mid-tag. */
internal const val MAX_HOOK_TAGS_CHARS = 1024

/**
 * Fires one pending hook once: claims the row, re-checks the target and the owner's grant, starts the
 * target with the `HMR_HOOK_*` metadata, and settles the row. The row writes around the start can
 * throw, so [HookService] isolates every call from the status write. See ADR-0107, ADR-0023.
 */
internal class HookFireService(
    private val repository: HookRepository,
    private val agentsService: AgentsService,
    private val authorizer: Authorizer,
    private val agentRunStarter: AgentRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val processManager: ProcessManager,
    private val logger: Logger,
    private val maxChainDepth: Int,
    private val nowSec: () -> Long,
) {
    /** Fires a single [hook] recorded by a status transition. */
    suspend fun fire(hook: PendingHook) {
        if (!repository.claim(hook.id)) return
        val now = nowSec()
        // A replayed row may outlive its target or the owner's grant: both are checked on delivery, and a
        // denial settles the hook and starts nothing (ADR-0107, ADR-0039, ADR-0047 rule 3).
        val target = agentsService.agents[hook.targetAgentName]
        if (target == null) {
            settle(hook, "Target agent '${hook.targetAgentName}' is not loaded", now)
            return
        }
        if (!authorizer.canDispatchHop(hook.owner, hook.declaringAgent, hook.targetAgentName)) {
            settle(
                hook,
                "agent '${hook.declaringAgent}' is not authorized to start '${hook.targetAgentName}'",
                now,
            )
            return
        }
        // The depth is read from the triggering run's row, not kept in memory, so a restart cannot
        // reset the chain bound. See ADR-0107.
        val run = runOrNull(hook.processId)
        val depth = nextDepthOrDrop(hook, run, now) ?: return
        if (depth > maxChainDepth) {
            // Shed, never retried: the chain bound is a property of the declarations, so the
            // next attempt would be refused for the same reason. See ADR-0107.
            repository.markDropped(
                hook.id,
                "hook chain depth $depth exceeds server.hooks.maxChainDepth ($maxChainDepth)",
                now,
            )
            logger.warn("Hook {} dropped: chain depth {} exceeds the limit {}", hook.id, depth, maxChainDepth)
            return
        }
        startTarget(hook, target, run, depth, now)
    }

    /**
     * The triggering run's row, or null when it can no longer be read — a purge or eviction
     * between the transition and this fire leaves the hook carrying only what the row held.
     * A cancellation is rethrown; every other failure is not this hook's to escalate.
     */
    private suspend fun runOrNull(processId: Int): ProcessInfo? =
        coRunCatching {
            processManager.getProcessInfo(processId)
        }.getOrElse { e ->
            logger.warn("Hook fire could not read run {}: {}", processId, e.message)
            null
        }

    /** Starts the target run for a claimed [hook] and settles the row. Never throws. */
    private suspend fun startTarget(
        hook: PendingHook,
        target: Agent,
        run: ProcessInfo?,
        depth: Int,
        now: Long,
    ) {
        val params = hookParams(hook, run, depth)
        try {
            val processId =
                agentRunStarter.start(
                    RunStartRequest(
                        agent = target,
                        // The declaring agent is the chain this fire extends, so the start
                        // preflight's cycle check refuses a hook that would re-enter it. See ADR-0008.
                        invocationChain = listOf(hook.declaringAgent),
                        source = "hook",
                        owner = hook.owner,
                        parentProcessId = hook.processId,
                        // From `run`: a rootless run is the flow root; a root the owner cannot see is
                        // dropped, never replaced by the run's id (CWE-200, ADR-0058, ADR-0107).
                        rootProcessId =
                            run?.let { info ->
                                if (info.rootProcessId == null) {
                                    info.id
                                } else {
                                    processManager.visibleRootProcessId(hook.owner, authorizer, info.rootProcessId)
                                }
                            },
                        // The metadata is merged back over the declared defaults: the resolver emits only
                        // declared inputs, and these names are reserved. See ADR-0107.
                        resolveInputs = { agentInputResolver.resolveFromParams(emptyMap(), target) + hookControlParams(params) },
                    ),
                    // The origin link is server-derived, so a hook fired while its run is still
                    // CREATED/WORKING is admitted rather than parked. ADR-0092.
                    serverDerivedParent = true,
                )
            repository.markDispatched(hook.id, processId, now)
            logger.info(
                "Hook {} fired ({} of run {}) -> run {}",
                hook.id,
                hook.triggeringStatus,
                hook.processId,
                processId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomerException) {
            settleFailedFire(hook, e, now)
        } catch (e: Exception) {
            settle(hook, e.message ?: "Unexpected hook fire failure", now)
        }
    }

    /**
     * Settles a fire that threw a [HomerException]: a transient refusal is retried on the continuation
     * back-off up to [ContinuationFireService.MAX_FIRE_ATTEMPTS]; anything else fails at once. See ADR-0107.
     */
    private suspend fun settleFailedFire(
        hook: PendingHook,
        e: HomerException,
        now: Long,
    ) {
        val msg = e.message ?: "Hook fire failed"
        val attempts = hook.fireAttempts + 1
        if (!ContinuationFireService.isTransientFireFailure(e) || attempts >= ContinuationFireService.MAX_FIRE_ATTEMPTS) {
            val reason =
                if (ContinuationFireService.isTransientFireFailure(e)) {
                    "$msg (gave up after $attempts attempts)"
                } else {
                    msg
                }
            settle(hook, reason, now)
            return
        }
        val delaySec = ContinuationFireService.retryDelaySec(attempts)
        repository.releaseForRetry(hook.id, msg, attempts, now + delaySec)
        logger.info(
            "Hook {} fire deferred (attempt {}/{}, retry in {}s): {}",
            hook.id,
            attempts,
            ContinuationFireService.MAX_FIRE_ATTEMPTS,
            delaySec,
            msg,
        )
    }

    /** Marks a claimed [hook] FAILED with [reason] and logs it. */
    private suspend fun settle(
        hook: PendingHook,
        reason: String,
        now: Long,
    ) {
        repository.markFailed(hook.id, reason, now)
        logger.warn("Hook {} failed: {}", hook.id, reason)
    }

    /**
     * One more than the triggering run's stored [EnvConstants.HOOK_DEPTH], 1 when it stores none. Fails
     * closed — drops [hook], returns null — when the run is unreadable or its depth is not a number, since
     * a fire restarting the chain at 1 would spawn without limit. See ADR-0107, ADR-0047 rule 4.
     */
    private suspend fun nextDepthOrDrop(
        hook: PendingHook,
        run: ProcessInfo?,
        now: Long,
    ): Int? {
        if (run == null) {
            return dropOverUnreadableDepth(
                hook,
                "the triggering run is no longer readable, so its hook-chain depth cannot be checked",
                now,
            )
        }
        val stored = run.inputParams[EnvConstants.HOOK_DEPTH] ?: return 1
        val current = stored.toIntOrNull()
        if (current == null) {
            return dropOverUnreadableDepth(hook, "the triggering run's stored hook-chain depth is not a number", now)
        }
        return current + 1
    }

    /** Marks [hook] dropped because its chain depth cannot be checked, logs it, and returns null. */
    private suspend fun dropOverUnreadableDepth(
        hook: PendingHook,
        reason: String,
        now: Long,
    ): Int? {
        repository.markDropped(hook.id, reason, now)
        logger.warn("Hook {} dropped: {}", hook.id, reason)
        return null
    }

    /**
     * The `HMR_HOOK_*` metadata of the run [hook] was recorded from: only Homer-recorded fields, the
     * failure reason already redacted (ADR-0098). No input value is ever copied. See ADR-0107.
     */
    private fun hookParams(
        hook: PendingHook,
        run: ProcessInfo?,
        depth: Int,
    ): Map<String, String> =
        buildMap {
            put(EnvConstants.HOOK_PROCESS_ID, hook.processId.toString())
            put(EnvConstants.HOOK_AGENT_NAME, hook.declaringAgent)
            put(EnvConstants.HOOK_STATUS, hook.triggeringStatus.name)
            put(EnvConstants.HOOK_DEPTH, depth.toString())
            run?.failureReason?.let { put(EnvConstants.HOOK_FAILURE_REASON, it) }
            hookTagsParam(run?.tags.orEmpty())?.let { put(EnvConstants.HOOK_TAGS, it) }
        }
}

/**
 * The run's derived subject tags joined for [EnvConstants.HOOK_TAGS], or null when none: control
 * characters dropped, and a tag past [MAX_HOOK_TAGS_CHARS] left out, never cut. See ADR-0060, ADR-0107.
 */
internal fun hookTagsParam(tags: List<String>): String? {
    val kept = mutableListOf<String>()
    var length = 0
    for (tag in tags) {
        val clean = tag.filterNot { it.isISOControl() }
        if (clean.isEmpty()) continue
        // The separator is charged from the second tag on: the first tag may use the whole
        // cap, since a lone tag joins to a value with no separator in it.
        val sep = if (kept.isEmpty()) 0 else 1
        if (length + sep + clean.length > MAX_HOOK_TAGS_CHARS) break
        kept += clean
        length += sep + clean.length
    }
    return kept.takeIf { it.isNotEmpty() }?.joinToString(",")
}
