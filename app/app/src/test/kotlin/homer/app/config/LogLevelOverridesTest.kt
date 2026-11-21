package homer.app.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogLevelOverridesTest {
    @Test
    fun `returns empty map when env var is absent`() {
        assertTrue(logLevelOverrides(emptyMap()).isEmpty())
    }

    @Test
    fun `returns empty map when env var is blank`() {
        assertTrue(logLevelOverrides(mapOf("HOMER_LOG_LEVELS" to "   ")).isEmpty())
    }

    @Test
    fun `maps a single logger=level pair`() {
        val props = logLevelOverrides(mapOf("HOMER_LOG_LEVELS" to "homer.app.executors.ProcessExecutor=debug"))
        assertEquals(
            mapOf("homer.app.executors.ProcessExecutor" to "debug"),
            props,
        )
    }

    @Test
    fun `maps multiple comma-separated pairs and lowercases the level`() {
        val props =
            logLevelOverrides(
                mapOf("HOMER_LOG_LEVELS" to "homer.app.executors.ProcessExecutor=TRACE, io.ktor=warn"),
            )
        assertEquals(
            mapOf(
                "homer.app.executors.ProcessExecutor" to "trace",
                "io.ktor" to "warn",
            ),
            props,
        )
    }

    @Test
    fun `skips entries with an unknown level`() {
        val props =
            logLevelOverrides(
                mapOf("HOMER_LOG_LEVELS" to "homer.app=verbose,homer.app.services=debug"),
            )
        assertEquals(
            mapOf("homer.app.services" to "debug"),
            props,
        )
    }

    @Test
    fun `skips malformed pairs and empty logger names`() {
        val props =
            logLevelOverrides(
                mapOf("HOMER_LOG_LEVELS" to "no-equals,=debug,homer.app=info"),
            )
        assertEquals(
            mapOf("homer.app" to "info"),
            props,
        )
    }

    @Test
    fun `changes the effective level of a named logger and ignores an invalid entry`() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val target = context.getLogger("homer.test.loglevels.Target")
        val untouched = context.getLogger("homer.test.loglevels.Other")
        val before = untouched.effectiveLevel
        try {
            applyLogLevelOverrides(
                mapOf("HOMER_LOG_LEVELS" to "homer.test.loglevels.Target=trace,homer.test.loglevels.Other=bogus"),
                context,
            )
            assertEquals(Level.TRACE, target.effectiveLevel)
            assertEquals(before, untouched.effectiveLevel)
        } finally {
            target.level = null
        }
    }

    @Test
    fun `leaves logback untouched without overrides or without a logback context`() {
        val context = LoggerContext()
        val logger = context.getLogger("homer.test.loglevels.Noop")
        applyLogLevelOverrides(emptyMap(), context)
        applyLogLevelOverrides(mapOf("HOMER_LOG_LEVELS" to "homer.test.loglevels.Noop=error"), org.slf4j.helpers.NOPLoggerFactory())
        assertEquals(null, logger.level)
    }
}
