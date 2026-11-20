package homer.app.runners

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveSessionsRegistryTest {
    @Test
    fun `closeAll closes every tracked session`() {
        val registry = ActiveSessionsRegistry()
        val s1 = mockk<RunnerSession>(relaxed = true)
        val s2 = mockk<RunnerSession>(relaxed = true)
        registry.add(s1)
        registry.add(s2)

        registry.closeAll()

        verify(exactly = 1) { s1.close() }
        verify(exactly = 1) { s2.close() }
        assertEquals(0, registry.size())
    }

    @Test
    fun `closeAll logs a failing session by runner alias and still closes the others`() {
        val registry = ActiveSessionsRegistry()
        val bad =
            mockk<RunnerSession>(relaxed = true) {
                every { runnerAlias } returns "ssh-box"
                every { close() } throws RuntimeException("boom")
            }
        val good = mockk<RunnerSession>(relaxed = true)
        registry.add(bad)
        registry.add(good)
        val logger = LoggerFactory.getLogger(ActiveSessionsRegistry::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            registry.closeAll()
        } finally {
            logger.detachAppender(appender)
        }

        verify(exactly = 1) { good.close() }
        assertEquals(0, registry.size())
        val warning = appender.list.single { it.level == Level.WARN }
        assertTrue("ssh-box" in warning.formattedMessage, warning.formattedMessage)
        assertEquals("boom", warning.throwableProxy.message)
    }

    @Test
    fun `remove drops a session so it is not closed on shutdown`() {
        val registry = ActiveSessionsRegistry()
        val s = mockk<RunnerSession>(relaxed = true)
        registry.add(s)
        registry.remove(s)

        registry.closeAll()

        verify(exactly = 0) { s.close() }
    }
}
