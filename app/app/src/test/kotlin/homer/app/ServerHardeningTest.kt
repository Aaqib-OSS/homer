package homer.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import homer.app.infra.Dumpability
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

/** Startup reports whether the server JVM is non-dumpable; it never refuses to start (ADR-0078). */
class ServerHardeningTest {
    private fun logged(result: Dumpability.Result): ILoggingEvent {
        val logger = LoggerFactory.getLogger("server-hardening-test") as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            hardenServerJvm(harden = { result }, logger = logger)
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.single()
    }

    @Test
    fun `a hardened JVM is logged at info`() {
        val event = logged(Dumpability.Result.Hardened)
        assertEquals(Level.INFO, event.level)
        assertEquals("Server JVM is non-dumpable", event.formattedMessage)
    }

    @Test
    fun `a host without prctl is a warning naming the reason`() {
        val event = logged(Dumpability.Result.Skipped("prctl(PR_SET_DUMPABLE, 0) is Linux-only; running on Mac OS X"))
        assertEquals(Level.WARN, event.level)
        assertTrue("Linux-only" in event.formattedMessage, event.formattedMessage)
    }

    @Test
    fun `a failed call on Linux is an error naming the reason, and startup goes on`() {
        val event = logged(Dumpability.Result.Failed("prctl(PR_SET_DUMPABLE, 0) returned -1"))
        assertEquals(Level.ERROR, event.level)
        assertTrue("left dumpable" in event.formattedMessage && "returned -1" in event.formattedMessage, event.formattedMessage)
    }
}
