package homer.app.infra

import homer.app.models.SYSENV_NAME

/** Resolves `@SYSENV:VAR` placeholders against [env], the host environment by default. */
class SysEnvResolver(
    private val env: (String) -> String? = System::getenv,
) {
    fun resolve(value: String): String =
        pattern.replace(value) { matchResult ->
            val varName = matchResult.groupValues[1]
            env(varName)
                ?: error("System environment variable '$varName' not found (referenced as '@SYSENV:$varName')")
        }

    fun resolveMapValues(map: Map<String, String>): Map<String, String> = map.mapValues { (_, v) -> resolve(v) }

    /**
     * The map keys whose *raw* value referenced `@SYSENV:`: [secretNamesFor] without the referenced host variable
     * names. Call on the raw map, before [resolveMapValues]. Callers deciding whether a template may *name* an entry
     * read this half: a name that is only a referenced host variable can also be a plain env entry or an input.
     */
    fun secretEnvKeysFor(map: Map<String, String>): Set<String> = map.filterValues { pattern.containsMatchIn(it) }.keys

    /**
     * Names to redact for the raw [map] (call before [resolveMapValues], which removes the placeholders): the keys
     * whose value references `@SYSENV:`, plus the referenced host variables, so `Bearer @SYSENV:TOKEN` cannot leak
     * the bare token to telemetry.
     */
    fun secretNamesFor(map: Map<String, String>): Set<String> = secretEnvKeysFor(map) + referencedNames(map)

    companion object {
        private val pattern = Regex("@SYSENV:($SYSENV_NAME)")

        /** The host variable names [map]'s values reference: exactly the names [resolve] would read. */
        fun referencedNames(map: Map<String, String>): Set<String> =
            map.values.flatMapTo(mutableSetOf()) { value -> pattern.findAll(value).map { it.groupValues[1] } }
    }
}
