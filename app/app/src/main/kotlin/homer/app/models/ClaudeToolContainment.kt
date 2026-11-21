package homer.app.models

/**
 * True when this block runs Claude with the permission gate off and `allowedTools` as its only tool setting:
 * `allowedTools` only pre-approves, so every other tool still runs; containment is `tools` or `disallowedTools`.
 * Mirrors `ClaudeArgvBuilder`: a `permissionMode` suppresses the skip flag. See ADR-0017.
 */
fun ClaudeCmdOptions.bypassesPermissionsWithoutToolLimit(): Boolean =
    bypassesPermissions && allowedTools != null && tools == null && disallowedTools == null

/** Whether the permission gate is off: `bypassPermissions`, or the skip flag with no mode set. */
private val ClaudeCmdOptions.bypassesPermissions: Boolean
    get() = permissionMode == "bypassPermissions" || (permissionMode == null && dangerouslySkipPermissions)
