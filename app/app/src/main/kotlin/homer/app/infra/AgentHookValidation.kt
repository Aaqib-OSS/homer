package homer.app.infra

import homer.app.models.Agent

/**
 * Cross-agent `hooks` validation: a target must be a loaded agent and the hook graph acyclic.
 * Repeats until stable, since rejecting an agent can strand hooks that named it.
 */
internal object AgentHookValidation {
    /**
     * Removes every rejected agent from [agents] and returns its name → error messages. Runs
     * until stable, so an agent whose only hook target was itself rejected is rejected too.
     */
    fun apply(agents: MutableMap<String, Agent>): Map<String, List<String>> {
        val rejected = mutableMapOf<String, List<String>>()
        while (true) {
            val round = mutableMapOf<String, List<String>>()
            for ((name, agent) in agents) {
                val errors = errorsFor(name, agent, agents)
                if (errors.isNotEmpty()) round[name] = errors
            }
            if (round.isEmpty()) return rejected
            for ((name, errors) in round) {
                agents.remove(name)
                rejected[name] = errors
            }
        }
    }

    /** Why [name]'s hooks are invalid against [agents], or an empty list when they are. */
    private fun errorsFor(
        name: String,
        agent: Agent,
        agents: Map<String, Agent>,
    ): List<String> {
        val unknown =
            agent.hooks
                .map { it.agent }
                .filter { it !in agents }
                .distinct()
                .map { "hook target '$it' is not a loaded agent" }
        if (unknown.isNotEmpty()) return unknown
        return if (reachesItself(name, agents)) {
            listOf("hook cycle: agent '$name' is reachable from its own hooks")
        } else {
            emptyList()
        }
    }

    /** Whether following [start]'s hooks reaches [start] again. */
    private fun reachesItself(
        start: String,
        agents: Map<String, Agent>,
    ): Boolean {
        val pending = ArrayDeque(agents.getValue(start).hooks.map { it.agent })
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (node == start) return true
            if (!seen.add(node)) continue
            agents[node]?.hooks?.forEach { pending.addLast(it.agent) }
        }
        return false
    }
}
