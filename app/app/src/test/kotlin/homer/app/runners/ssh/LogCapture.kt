package homer.app.runners.ssh

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.apache.sshd.client.channel.ChannelExec
import org.apache.sshd.client.session.ClientSessionImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level as LogbackLevel
import ch.qos.logback.classic.Logger as LogbackLogger

/** A copy of the captured events, taken under the lock `doAppend` holds, so a late MINA thread cannot race it. */
private fun ListAppender<ILoggingEvent>.snapshot(): List<ILoggingEvent> = synchronized(this) { list.toList() }

/** Run [body] with [loggerClass]'s WARN output captured. */
internal suspend fun captureWarnings(
    loggerClass: Class<*>,
    body: suspend () -> Unit,
): List<ILoggingEvent> {
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    val logback = LoggerFactory.getLogger(loggerClass) as LogbackLogger
    val level = logback.level
    logback.level = LogbackLevel.WARN
    logback.addAppender(appender)
    try {
        body()
    } finally {
        logback.detachAppender(appender)
        logback.level = level
    }
    return appender.snapshot()
}

/**
 * Runs [body] with every log event captured: the root level at TRACE, and the two ssh loggers pinned
 * as logback.xml pins them, so a MINA line quoting the remote command stays out of the capture as it
 * stays out of app.log.
 */
internal suspend fun <T> captureAllEvents(body: suspend () -> T): Pair<T, List<ILoggingEvent>> {
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as LogbackLogger
    val pinned =
        listOf(
            ChannelExec::class.java to LogbackLevel.ERROR,
            ClientSessionImpl::class.java to LogbackLevel.INFO,
        ).map { (type, level) -> LoggerFactory.getLogger(type) as LogbackLogger to level }
    val rootLevel = root.level
    val pinnedLevels = pinned.map { it.first.level }
    root.level = LogbackLevel.TRACE
    pinned.forEach { (logger, level) -> logger.level = level }
    root.addAppender(appender)
    try {
        val result = body()
        return result to appender.snapshot()
    } finally {
        root.detachAppender(appender)
        root.level = rootLevel
        pinned.forEachIndexed { index, (logger, _) -> logger.level = pinnedLevels[index] }
    }
}
