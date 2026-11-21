package homer.app.config

import homer.app.models.LlmPriceConfig

/**
 * Resolves `@SYSENV:VAR` placeholders in `llmPrice.sessionSpend.headers`. Null-safe.
 * Public top-level (not `internal` in `ConfigValidation.kt`) so the separate `agentdebug` module can share it.
 */
fun resolveLlmPrice(
    llmPrice: LlmPriceConfig?,
    resolve: (String) -> String,
): LlmPriceConfig? = llmPrice?.copy(sessionSpend = llmPrice.sessionSpend?.withResolvedHeaders(resolve))
