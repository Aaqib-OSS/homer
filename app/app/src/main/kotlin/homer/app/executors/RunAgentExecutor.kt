package homer.app.executors

import com.jayway.jsonpath.JsonPath
import homer.app.EnvConstants
import homer.app.SpanAttributes
import homer.app.exceptions.HomerException
import homer.app.infra.AgentInputResolver
import homer.app.infra.BoundedRegex
import homer.app.infra.TextCompiler
import homer.app.infra.jsonPathText
import homer.app.models.Command
import homer.app.models.CommandResult
import homer.app.models.ResultTransformer
import homer.app.models.RunStartRequest
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import kotlin.io.path.writeText

/**
 * Runs `!<agent>` by dispatching the target in-process through [SubRunStarter], the core the HTTP route and
 * `DispatchService` share, so it works whatever authenticated the run. The invocation chain's cycle and depth
 * checks and the ancestor lock keys pass to [SubRunStarter.start] (ADR-0030).
 */
internal class RunAgentExecutor(
    private val subRuns: SubRunStarter,
    private val agentInputResolver: AgentInputResolver,
    private val textCompiler: TextCompiler,
) : CommandExecutor<Command.RunAgent> {
    override suspend fun execute(
        command: Command.RunAgent,
        context: ExecutionContext,
        span: Span,
    ): CommandResult {
        val params = resolveQueryParams(command, context)
        val agentName = textCompiler.replaceArguments(command.agentName, context.workspace.config.env)
        span.setAttribute(SpanAttributes.AGENT_NAME, agentName)
        span.setAttribute("agent.params", params.keys.joinToString(", "))

        // An unauthorized hop reads exactly like an unknown agent: the target name can come from
        // env, so this must not let agent code probe which agents exist (ADR-0047 rule 3).
        val target = subRuns.agent(agentName)?.takeIf { subRuns.canDispatchHop(context.owner, context.agentName, agentName) }
        if (target == null) {
            context.stdErr.writeText("agent '$agentName' not found")
            return CommandResult(exitCode = 255, stdOut = context.stdOut, stdErr = context.stdErr)
        }

        val invocationChain = invocationChainOf(context)
        // This run's own key joins the flow's, so a descendant with acceptParentLock passes through
        // instead of colliding with its ancestor (ADR-0029).
        val ancestorLockKeys = context.workspace.config.ancestorLockKeys + context.workspace.config.lockKey

        return try {
            val processId =
                subRuns.start(
                    RunStartRequest(
                        agent = target,
                        invocationChain = invocationChain,
                        source = "agent",
                        ancestorLockKeys = ancestorLockKeys,
                        owner = context.owner,
                        parentProcessId = context.processId.takeIf { it > 0 },
                        // CWE-200: the child's inherited root is gated on this run's owner seeing it, so a
                        // foreign flow-root id reaches no row, env or span (ADR-0058, ADR-0047 rule 3).
                        rootProcessId = subRuns.visibleOriginRootProcessId(context.processId, context.owner),
                        resolveInputs = { agentInputResolver.resolveFromParams(params, target) },
                    ),
                )
            context.stdOut.writeText("processId: $processId")
            span.setAttribute("agent.processId", processId.toString())
            CommandResult(exitCode = 0, stdOut = context.stdOut, stdErr = context.stdErr)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomerException) {
            // A refused dispatch (cycle, depth, cost cap, validation) exits 255 like a denied hop,
            // so continueIfFail and finishExitCode apply (ADR-0039).
            context.stdErr.writeText(e.message ?: "agent dispatch failed")
            CommandResult(exitCode = 255, stdOut = context.stdOut, stdErr = context.stdErr)
        }
    }

    /** The target's query params from this run's env, each through its transformer; an unset or unmatched one is dropped. */
    private fun resolveQueryParams(
        command: Command.RunAgent,
        context: ExecutionContext,
    ): Map<String, String> {
        // One bounded regex stall per dispatch, not one per param: once a match is shed, the later
        // regex params are dropped too, the other kinds still run (ADR-0047 rule 4).
        var regexTimedOut = false
        return command.queryParams
            .mapNotNull { param ->
                val envValue = context.workspace.config.env[param.envName] ?: return@mapNotNull null
                if (regexTimedOut && param.transformer is ResultTransformer.Regex) return@mapNotNull null
                val transformed =
                    applyTransformer(param.envName, envValue, param.transformer) { regexTimedOut = true }
                        ?: return@mapNotNull null
                param.paramName to transformed
            }.toMap()
    }

    private fun invocationChainOf(context: ExecutionContext): List<String> =
        context.workspace.config.env[EnvConstants.INVOCATION_CHAIN]
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun applyTransformer(
        envName: String,
        value: String,
        transformer: ResultTransformer,
        onRegexTimeout: () -> Unit,
    ): String? =
        when (transformer) {
            ResultTransformer.Pass -> {
                value
            }

            is ResultTransformer.JsonPath -> {
                runCatching {
                    jsonPathText(JsonPath.parse(value).read<Any>(transformer.path))
                }.getOrNull()
            }

            is ResultTransformer.Regex -> {
                // Author-written pattern, caller-influenced subject: matched under `resultEnvParam`'s
                // bound, and a shed match drops the param as a non-match does (ADR-0047 rule 4).
                BoundedRegex.findOrOmit(transformer.regex, value, envName, logger, onRegexTimeout)?.let { match ->
                    if (transformer.group != null) match.groupValues.getOrNull(transformer.group) else match.value
                }
            }
        }

    private companion object {
        private val logger = LoggerFactory.getLogger(RunAgentExecutor::class.java)
    }
}
