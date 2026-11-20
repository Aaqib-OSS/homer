package homer.app.executors.llm

import homer.app.executors.addBoolOpt
import homer.app.executors.addRepeatableOpt
import homer.app.executors.addStringOpt
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.OpencodeCmdOptions

/**
 * Builds the opencode argv, `opencode run --format json` plus what [OpencodeCmdOptions] configures, resolving `{{VAR}}`
 * against [env]. [OpencodeHarness] and the agentdebug printer share it, so a dry run shows the argv that would run.
 */
class OpencodeArgvBuilder(
    private val textCompiler: TextCompiler = DefaultTextCompiler(),
) {
    fun build(
        cmd: String,
        opts: OpencodeCmdOptions,
        env: Map<String, String>,
    ): List<String> =
        buildList {
            add(cmd)
            add("run")

            addStringOpt("--model", opts.model)
            addStringOpt("--agent", opts.agent)
            addStringOpt("--dir", opts.dir)
            addRepeatableOpt("--file", opts.file)

            // Security: opt-in — auto-approves every permission the opencode
            // configuration does not explicitly deny (see OpencodeCmdOptions.auto).
            addBoolOpt("--auto", opts.auto)

            opts.options?.let { addAll(it) }
            add("--format")
            add("json")
            // No positional message: `opencode run` reads the prompt from stdin
            // when no message argument is given, as Codex's `-` sentinel arranges.
        }.map {
            // Resolve {{VAR}} placeholders in option values (see AGENT.md).
            textCompiler.replaceArguments(it, env)
        }
}
