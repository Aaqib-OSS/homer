package homer.app

object EnvConstants {
    const val WORK_DIR = "HMR_WORK_DIR"
    const val ARTIFACTS_DIR = "HMR_ARTIFACTS_DIR"
    const val PROCESS_ID = "HMR_PROCESS_ID"
    const val SERVER_ID = "HMR_SERVER_ID"
    const val AGENT_NAME = "HMR_AGENT_NAME"
    const val AGENT_DIR = "HMR_AGENT_DIR"
    const val API_URL = "HMR_API_URL"

    /** Per-process random token; authorizes only this process's callback routes. Never an operator credential. See ADR-0019. */
    const val CALLBACK_TOKEN = "HMR_CALLBACK_TOKEN"

    const val MCP_CONFIG = "HMR_MCP_CONFIG"

    const val INVOCATION_CHAIN = "HMR_INVOCATION_CHAIN"

    const val PARENT_PROCESS_ID = "HMR_PARENT_PROCESS_ID"
    const val ROOT_PROCESS_ID = "HMR_ROOT_PROCESS_ID"
    const val PARENT_PROCESS_HEADER = HomerHeaders.PARENT_PROCESS_ID

    /** Read before `Authorization`: at an egress proxy that header belongs to the upstream. See ADR-0110. */
    const val CALLBACK_TOKEN_HEADER = HomerHeaders.CALLBACK_TOKEN

    const val AGENT_HEADER = HomerHeaders.AGENT
    const val PROCESS_ID_HEADER = HomerHeaders.PROCESS_ID

    const val LG_THREAD = "HMR_LG_THREAD"
    const val LG_RESUME = "HMR_LG_RESUME"
    const val LG_INTERRUPT = "HMR_LG_INTERRUPT"

    /** Set by `POST /processes/{id}/resume`; ignored when [LG_RESUME] is present (an interrupt-resume replays its value). See ADR-0044. */
    const val LG_RECOVER = "HMR_LG_RECOVER"

    const val LG_INPUT = "HMR_LG_INPUT"

    /** Durable LangGraph checkpoint dir; uses `HOMER_` (Python-runtime consumer), not the `HMR_` env contract. See ADR-0024. */
    const val LG_STATE_DIR = "HOMER_LG_STATE_DIR"

    /** Template-only for `artifactNameTemplate`; never exported into a command's process env. See AGENT.md. */
    const val CMD_INDEX = "HMR_CMD_INDEX"
    const val CMD_ATTEMPT = "HMR_CMD_ATTEMPT"

    /** Set only in an `escalate[].when` predicate's env, never in a command's process env. See ADR-0079. */
    const val LLM_OUTCOME = "HMR_LLM_OUTCOME"
    const val LLM_EXIT_CODE = "HMR_LLM_EXIT_CODE"

    /** Server-minted from the stored run only — never a secret input value, never a caller-supplied param. See ADR-0107. */
    const val HOOK_PROCESS_ID = "HMR_HOOK_PROCESS_ID"
    const val HOOK_AGENT_NAME = "HMR_HOOK_AGENT_NAME"
    const val HOOK_STATUS = "HMR_HOOK_STATUS"
    const val HOOK_FAILURE_REASON = "HMR_HOOK_FAILURE_REASON"
    const val HOOK_TAGS = "HMR_HOOK_TAGS"

    /** Hook hops deep this run is; bounded by `server.hooks.maxChainDepth`. See ADR-0107. */
    const val HOOK_DEPTH = "HMR_HOOK_DEPTH"

    /** Set only in an agent's `onStop:` steps' env, never in a regular command's. See ADR-0108. */
    const val STOP_REASON = "HMR_STOP_REASON"
    const val STOP_STEP_INDEX = "HMR_STOP_STEP_INDEX"
    const val STOP_STEP_LABEL = "HMR_STOP_STEP_LABEL"
    const val STOP_DEADLINE = "HMR_STOP_DEADLINE"
    const val STOP_SESSION_ID = "HMR_STOP_SESSION_ID"

    const val STOP_REASON_STEP_TIMEOUT = "step_timeout"
    const val STOP_REASON_RUN_DEADLINE = "run_deadline"

    /** Single source shared by the model default, `ExecutionContext.artifactNames` and `AgentExecutor`'s fallback — do not drift. */
    const val DEFAULT_ARTIFACT_NAME_TEMPLATE = "cmd_{{$CMD_INDEX}}"
}
