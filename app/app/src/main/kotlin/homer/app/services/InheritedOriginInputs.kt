package homer.app.services

import homer.app.infra.ReservedEnvNames
import homer.app.models.Agent
import homer.app.models.ProcessInfo
import org.slf4j.Logger

/** Origin run's stored inputs a continuation/dispatch to the same agent inherits. See ADR-0101. */
internal data class InheritedOriginInputs(
    val params: Map<String, String> = emptyMap(),
    val secretInputKeys: Set<String> = emptySet(),
) {
    companion object {
        val NONE = InheritedOriginInputs()
    }
}

// The origin run's inputs a fire to [target] inherits, or NONE. Fails closed to NONE on every gate:
// target must opt in (`continuation.inheritInputs`), [originAgentName] must equal `target.name`, and
// [origin]'s owner must equal [originOwner]. See ADR-0101.
internal suspend fun inheritedOriginInputs(
    origin: suspend () -> ProcessInfo?,
    originAgentName: String,
    originOwner: String?,
    target: Agent,
    label: String,
    logger: Logger,
): InheritedOriginInputs {
    if (target.continuation?.inheritInputs != true) return InheritedOriginInputs.NONE
    if (originAgentName != target.name) return InheritedOriginInputs.NONE
    val originInfo = origin()
    if (originInfo == null) {
        logger.warn("$label to agent '{}': origin run row is gone, inheriting no inputs", target.name)
        return InheritedOriginInputs.NONE
    }
    if (originInfo.owner != originOwner) {
        logger.warn(
            "$label to agent '{}': origin run {} belongs to another owner, inheriting no inputs",
            target.name,
            originInfo.id,
        )
        return InheritedOriginInputs.NONE
    }
    return InheritedOriginInputs(
        params = declaredParams(target, originInfo.inputParams),
        secretInputKeys = originInfo.secretInputKeys.orEmpty(),
    )
}

// Inherited paramNames whose value survives the fire's merge: those values are already transformed,
// so `resolveFromParams` must validate but never transform them again. See ADR-0101.
internal fun InheritedOriginInputs.storedParamNames(ownParamNames: Set<String>): Set<String> = params.keys - ownParamNames

/** Origin's envName-keyed stored inputs as paramName-keyed params on [target]; drops undeclared and reserved names. */
private fun declaredParams(
    target: Agent,
    storedInputs: Map<String, String>,
): Map<String, String> {
    val byEnvName = target.effectiveInputs.orEmpty().associateBy { it.envName }
    return storedInputs
        .mapNotNull { (envName, value) ->
            val param = byEnvName[envName] ?: return@mapNotNull null
            if (ReservedEnvNames.isReserved(envName)) null else param.paramName to value
        }.toMap()
}
