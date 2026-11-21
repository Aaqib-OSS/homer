package homer.agentdebug.stubs

import homer.app.models.Command

/**
 * Stub for `!<langgraph>` in the server-less debugger: a durable workflow resumes through dispatch and
 * continuations, which agentdebug does not run. DebugAgentRunner skips these commands before execution;
 * this is the no-op fallback.
 */
class SkipLangGraphExecutor : SkipExecutor<Command.LangGraph>()
