package homer.app.infra

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
     * The map keys whose *raw* value referenced `@SYSENV:` — the keys half of
     * [secretNamesFor], without the referenced host variable names. Call on the
     * *raw* map, before [resolveMapValues] — afterwards the placeholders are gone.
     * Callers deciding whether a template may *name* one of these entries read
     * this half, not the composite: a name that is only a referenced host
     * variable can also be a plain env entry or an input envName.
     */
    fun secretEnvKeysFor(map: Map<String, String>): Set<String> = map.filterValues { pattern.containsMatchIn(it) }.keys

    /**
     * Names to redact for [map], for callers marking host-sourced values secret.
     * Call on the *raw* map, before [resolveMapValues] — afterwards the
     * placeholders are gone. Two kinds of name, because [resolve] substitutes
     * *inside* a string: for `TOKEN: "@SYSENV:REPO_TOKEN"` the key suffices, but
     * for `HEADER: "Bearer @SYSENV:REPO_TOKEN"` the bare token could otherwise
     * reach telemetry in the clear, so the referenced host variables ride along.
     */
    fun secretNamesFor(map: Map<String, String>): Set<String> = secretEnvKeysFor(map) + referencedNames(map)

    companion object {
        private val pattern = Regex("@SYSENV:([A-Za-z_][A-Za-z0-9_]*)")

        /** The host variable names [map]'s values reference: exactly the names [resolve] would read. */
        fun referencedNames(map: Map<String, String>): Set<String> =
            map.values.flatMapTo(mutableSetOf()) { value -> pattern.findAll(value).map { it.groupValues[1] } }
    }
}
