package homer.app.config

/**
 * Translates `HOMER_LOG_LEVELS` into slf4j-simple per-logger system-property overrides, so
 * operators can re-target log levels at runtime (e.g. in a container) without editing the bundled
 * `simplelogger.properties`. Format: comma-separated `logger=level` pairs; levels are
 * slf4j-simple's (`trace`..`off`, case-insensitive), and malformed pairs and unknown levels are
 * skipped. The caller applies each entry only when the property is not already set, so an explicit
 * `-D` flag wins; a logger built before the override keeps its initial level.
 */
private const val ENV_LOG_LEVELS = "HOMER_LOG_LEVELS"
private const val SLF4J_LOG_PREFIX = "org.slf4j.simpleLogger.log."
private val VALID_LEVELS = setOf("trace", "debug", "info", "warn", "error", "off")

fun logLevelSystemProps(env: Map<String, String>): Map<String, String> {
    val raw = env[ENV_LOG_LEVELS]?.takeIf { it.isNotBlank() } ?: return emptyMap()
    return raw
        .split(",")
        .mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val logger = parts[0].trim()
            val level = parts[1].trim().lowercase()
            if (logger.isEmpty() || level !in VALID_LEVELS) return@mapNotNull null
            "$SLF4J_LOG_PREFIX$logger" to level
        }.toMap()
}
