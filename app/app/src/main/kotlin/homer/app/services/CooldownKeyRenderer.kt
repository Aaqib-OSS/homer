package homer.app.services

import homer.app.exceptions.CooldownKeyInvalidException
import homer.app.infra.DefaultTextCompiler
import homer.app.infra.TextCompiler
import homer.app.models.Agent

// Rendered once at start from the run's env and resolved inputs, and stored on the process row
// inside the INSERT's transaction: re-deriving it later could read a changed definition or a
// HMR_PROCESS_ID that did not exist yet. See ADR-0051, ADR-0106.
internal object CooldownKeyRenderer {
    // Local fixed bound, not a database constraint: the refusal must reach the caller as a 400.
    const val MAX_KEY_LENGTH = 128

    // A stored, indexed token compared by exact equality: keep it one — no whitespace, quote,
    // `%`/`_` for a later LIKE, or brace that would let a placeholder-looking value pass as a template.
    private val allowedKeyChars = Regex("^[A-Za-z0-9._:/-]+$")

    private val compiler: TextCompiler = DefaultTextCompiler()

    // The rendered key for [agent] with [env] in scope, or null when it sets no key.
    // [extraSecretInputKeys] keeps names that were secret for the source run secret on retry/resume/fire. See ADR-0052.
    fun render(
        agent: Agent,
        env: Map<String, String>,
        extraSecretInputKeys: Set<String> = emptySet(),
    ): String? {
        val cooldown = agent.cooldown ?: return null
        val template = cooldown.key ?: return null
        // A placeholder naming a secret must never render: the key is stored in plain text on the
        // row and in its index. Checked against the template's own placeholder names, never the
        // rendered text, so a refusal cannot echo a value. §12 rule 7.
        cooldown.keyRefs.firstOrNull { it in agent.secretEnvNames || it in extraSecretInputKeys }?.let { name ->
            throw CooldownKeyInvalidException(agent.name, "placeholder {{$name}} names a secret input")
        }
        cooldown.keyRefs.firstOrNull { it !in env }?.let { name ->
            throw CooldownKeyInvalidException(agent.name, "placeholder {{$name}} does not resolve from the run's env or inputs")
        }
        val rendered = compiler.replaceArguments(template, env)
        if (rendered.isBlank()) {
            throw CooldownKeyInvalidException(agent.name, "the rendered value is empty")
        }
        if (rendered.length > MAX_KEY_LENGTH) {
            throw CooldownKeyInvalidException(
                agent.name,
                "the rendered value is ${rendered.length} characters, the limit is $MAX_KEY_LENGTH",
            )
        }
        if (!allowedKeyChars.matches(rendered)) {
            throw CooldownKeyInvalidException(
                agent.name,
                "the rendered value uses characters outside ${allowedKeyChars.pattern}",
            )
        }
        return rendered
    }
}

// Renders from `agent.env + inputs`: HMR_AGENT_DIR is present, HMR_PROCESS_ID is not yet. See ADR-0106.
internal fun Agent.renderCooldownKey(
    inputs: Map<String, String>,
    extraSecretInputKeys: Set<String> = emptySet(),
): String? = CooldownKeyRenderer.render(this, env + inputs, extraSecretInputKeys)
