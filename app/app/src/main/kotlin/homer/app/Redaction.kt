package homer.app

import homer.app.models.Agent
import homer.app.models.TagValidation

/** Redacts secret inputs. The secret set is the union of the run-start snapshot and the agent's current
 * `secret: true` flags; a null agent, or a stored key matching no current input's envName, fails closed. See ADR-0052. */
internal fun redactInputParams(
    inputParams: Map<String, String>,
    agent: Agent?,
    storedSecretKeys: Set<String>? = null,
): Map<String, String> {
    if (agent == null) return inputParams.mapValues { "***" }
    val unprovable = unprovableKeyTest(agent, storedSecretKeys)
    return inputParams.mapValues { (k, v) -> if (unprovable(k)) "***" else v }
}

/** Single source of truth for the ADR-0052 union policy, shared by [redactInputParams] and [screenTags]; build once per pair. */
internal fun unprovableKeyTest(
    agent: Agent?,
    storedSecretKeys: Set<String>?,
): (String) -> Boolean {
    if (agent == null) return { true }
    val secretEnvNames = storedSecretKeys.orEmpty() + agent.secretEnvNames
    val allCurrentEnvNames =
        agent.effectiveInputs
            .orEmpty()
            .map { it.envName }
            .toSet()
    return { k -> k in secretEnvNames || k !in allCurrentEnvNames }
}

/** Read-time screen for a run's subject tags: drops any tag containing a value secret under the same union
 * [redactInputParams] uses; a missing definition fails closed. Floor per [TagValidation.SECRET_NEEDLE_MIN_LENGTH]. See ADR-0060. */
internal fun screenTags(
    tags: List<String>,
    inputParams: Map<String, String>,
    agent: Agent?,
    storedSecretKeys: Set<String>?,
): List<String> {
    if (tags.isEmpty()) return tags
    val unprovable = unprovableKeyTest(agent, storedSecretKeys)
    val needles =
        inputParams
            .filter { (k, _) -> unprovable(k) }
            .values
            .filter { it.length >= TagValidation.SECRET_NEEDLE_MIN_LENGTH }
    if (needles.isEmpty()) return tags
    return tags.filter { tag -> needles.none { tag.contains(it) } }
}

/** Redacts a dispatch/continuation row's stored `params` (keyed by paramName, not envName). The secret set is the
 * union of the registration snapshot, the target agent's current flags and the raw stored key; a null agent fails
 * closed. A key absent from the current definition is redacted only when the snapshot is null. See ADR-0052. */
internal fun redactStoredParams(
    params: Map<String, String>,
    storedSecretKeys: Set<String>?,
    agent: Agent?,
    storedSecretParamNames: Set<String>?,
): Map<String, String> {
    if (agent == null) return params.mapValues { "***" }
    val secretEnvNames = storedSecretKeys.orEmpty() + agent.secretEnvNames
    val inputs = agent.effectiveInputs.orEmpty()
    val secretParamNames =
        inputs.filter { it.envName in secretEnvNames }.map { it.paramName }.toSet() + storedSecretParamNames.orEmpty()
    val declaredParamNames = inputs.map { it.paramName }.toSet()
    return params.mapValues { (k, v) ->
        when {
            k in secretParamNames || k in secretEnvNames -> "***"
            storedSecretKeys == null && k !in declaredParamNames -> "***"
            else -> v
        }
    }
}
