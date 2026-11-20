package homer.app.runners.kubernetes

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kubernetes.client.Exec
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ch.qos.logback.classic.Logger as LogbackLogger

class KubernetesApiExecClientTest {
    @Test
    fun `an unhandled exec error logs neither the env value nor the exec URL`() {
        val secret = "s3cr3t/t0ken+value"
        val encoded = URLEncoder.encode("TOKEN=$secret", StandardCharsets.UTF_8)
        val url = "wss://api/api/v1/namespaces/homer/pods/pod-1/exec?command=env&command=$encoded&command=SHORT%3Dabc&command=true"
        val handler = slot<Consumer<Throwable>>()
        val builder = mockk<Exec.ExecutionBuilder>()
        every { builder.setStdin(any()) } returns builder
        every { builder.setTty(any()) } returns builder
        every { builder.setOnUnhandledError(capture(handler)) } returns builder
        every { builder.execute() } returns mockk<Process>(relaxed = true)
        val upstream = mockk<Exec> { every { newExecutionBuilder(any(), any(), any()) } returns builder }
        val logger = LoggerFactory.getLogger(KubernetesApiExecClient::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            KubernetesApiExecClient(upstream).start("homer", "pod-1", listOf("env", "TOKEN=$secret", "SHORT=abc", "true"), stdin = false)
            handler.captured.accept(java.io.IOException("$url failed; raw TOKEN=$secret, short SHORT=abc"))
        } finally {
            logger.detachAppender(appender)
        }

        val event = appender.list.single()
        val line = event.formattedMessage
        assertFalse(line.contains(secret), "the raw env value must not be logged: $line")
        assertFalse(line.contains(encoded.substringAfter("%3D")), "the encoded env value must not be logged: $line")
        assertFalse(line.contains("command="), "the exec URL must not be logged: $line")
        assertTrue(line.contains("IOException"), "the line keeps the error's class: $line")
        assertNull(event.throwableProxy, "no stack trace, whose text could quote the URL")
    }

    @Test
    fun `unhandledErrorLine masks only the leading env assignments`() {
        val line =
            unhandledErrorLine(
                listOf("env", "A=first-secret", "B=second-secret", "sh", "-c", "X=not-env-value"),
                IllegalStateException("first-secret second-secret X=not-env-value at wss://h/exec?stdin=false&command=env"),
            )

        assertEquals("IllegalStateException: *** *** X=not-env-value at [exec URL]", line)
    }
}
