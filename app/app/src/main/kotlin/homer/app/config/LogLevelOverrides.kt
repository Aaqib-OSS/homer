package homer.app.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.slf4j.ILoggerFactory

/**
 * Applies `HOMER_LOG_LEVELS` (comma-separated `logger=LEVEL` pairs, `trace`..`off`, case-insensitive)
 * to logback's logger context, over the levels `logback.xml` sets. Malformed pairs and unknown
 * levels are skipped.
 */
private const val ENV_LOG_LEVELS = "HOMER_LOG_LEVELS"
private val VALID_LEVELS = setOf("trace", "debug", "info", "warn", "error", "off")

fun logLevelOverrides(env: Map<String, String>): Map<String, String> {
    val raw = env[ENV_LOG_LEVELS]?.takeIf { it.isNotBlank() } ?: return emptyMap()
    return raw
        .split(",")
        .mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val logger = parts[0].trim()
            val level = parts[1].trim().lowercase()
            if (logger.isEmpty() || level !in VALID_LEVELS) return@mapNotNull null
            logger to level
        }.toMap()
}

fun applyLogLevelOverrides(
    env: Map<String, String>,
    factory: ILoggerFactory,
) {
    val context = factory as? LoggerContext ?: return
    val overrides = logLevelOverrides(env)
    if (overrides.isEmpty()) return
    overrides.forEach { (name, level) -> context.getLogger(name).level = Level.toLevel(level) }
}
