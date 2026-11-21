package homer.agentdebug.stubs

import homer.app.models.Command

/**
 * Stub for `!<runnerCmd>` in the server-less debugger: it targets another runner alias through the DI-wired
 * RunnerRegistry, which agentdebug does not build. DebugAgentRunner skips these commands before execution;
 * this is the no-op fallback.
 */
class SkipRunnerCmdExecutor : SkipExecutor<Command.RunnerCmd>()
