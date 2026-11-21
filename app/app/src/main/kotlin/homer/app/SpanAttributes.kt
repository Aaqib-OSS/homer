package homer.app

/**
 * Span attribute keys and event names Homer writes on its own spans; each value is pinned by a test, as a
 * rename breaks existing trace queries. A key used at one call site stays a literal; the Langfuse
 * exporter's `langfuse.*`, `llm.*` and `usage_details.*` vocabulary stays with the emitter.
 */
object SpanAttributes {
    /** Status of the run, as its `ProcessStatus` name; written by `ProcessExecutor` and `Process`. */
    const val PROCESS_STATUS = "process.status"

    /**
     * Prefix of the `process.status.<status>` span events written next to [PROCESS_STATUS], one per
     * status transition (`process.status.created`, `process.status.finished`, …).
     */
    const val PROCESS_STATUS_EVENT_PREFIX = "process.status."

    /** Id of the run a span belongs to; written by `ProcessExecutor`, `AgentRunStarter` and the run routes. */
    const val PROCESS_ID = "process.id"

    /** Id of the run that caused this one, when it has a parent. See ADR-0058. */
    const val PARENT_PROCESS_ID = "parent.process.id"

    /** Id of the flow's first run, the root of the invocation chain. See ADR-0058. */
    const val ROOT_PROCESS_ID = "root.process.id"

    /** Id of the Homer server that started the run. */
    const val SERVER_ID = "server.id"

    /** Name of the agent a run executes; on a `!<agent>` step, the hop's target. */
    const val AGENT_NAME = "agent.name"

    /** How the run was started (`http`, `mcp`, `hook`, `agent`, `cron`, `debug`, `retry`, `resume`, …). */
    const val REQUEST_SOURCE = "request.source"

    /** The redacted command line, script or prompt a command executor sends to its harness. */
    const val INPUT_VALUE = "input.value"

    /** The redacted stdout a step produced, or a pointer to its file when it is too large to trace. */
    const val OUTPUT_VALUE = "output.value"

    /** Retry attempts a step actually used; written by the retry loop on its last outcome. */
    const val RETRY_ATTEMPTS_USED = "retry.attempts.used"

    /** Why a telemetry hand-off failed; the step itself still finishes on the harness figure. */
    const val TELEMETRY_ERROR = "telemetry.error"

    /** Set when a telemetry hand-off was shed at its concurrency or wait bound. See ADR-0089. */
    const val TELEMETRY_LLM_HANDOFF_SHED = "telemetry.llm.handoff_shed"

    /** Alias of the runner that took the run's claim; stamped by the kubernetes sessions and `StepBookkeeping`. */
    const val RUNNER_ALIAS = "runner.alias"

    /** Namespace of the pod a kubernetes runner session runs in. */
    const val POD_NAMESPACE = "pod.namespace"

    /** Name of that session's pod: its own name, or the Job's pod in Job mode (ADR-0100). */
    const val POD_NAME = "pod.name"

    /** Name of the Job a kubernetes session runs in Job mode, instead of [POD_NAME]. See ADR-0100. */
    const val JOB_NAME = "job.name"
}
