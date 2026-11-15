package homer.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Pins the exact string of every span key, span event name and HTTP header name Homer exports.
 * A rename here silently breaks an existing trace query or an API client, so each value is
 * asserted against its literal rather than against another constant.
 */
class SpanAttributesAndHeadersPinTest {
    @Test
    fun `span attribute key and event prefix are byte-identical to the literals`() {
        assertEquals("process.status", SpanAttributes.PROCESS_STATUS)
        assertEquals("process.status.", SpanAttributes.PROCESS_STATUS_EVENT_PREFIX)
    }

    @Test
    fun `the event prefix builds the span event names the executor writes`() {
        assertEquals("process.status.created", "${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}created")
        assertEquals("process.status.finished", "${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}finished")
        assertEquals("process.status.failed", "${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}failed")
        assertEquals("process.status.killed", "${SpanAttributes.PROCESS_STATUS_EVENT_PREFIX}killed")
    }

    @Test
    fun `header names are byte-identical to the literals`() {
        assertEquals("X-Process-Status", HomerHeaders.PROCESS_STATUS)
        assertEquals("X-File-Complete", HomerHeaders.FILE_COMPLETE)
        assertEquals("X-Homer-Parent-Process-Id", HomerHeaders.PARENT_PROCESS_ID)
        assertEquals("X-Homer-Callback-Token", HomerHeaders.CALLBACK_TOKEN)
        assertEquals("X-Homer-Agent", HomerHeaders.AGENT)
        assertEquals("X-Homer-Process-Id", HomerHeaders.PROCESS_ID)
    }

    @Test
    fun `the EnvConstants header aliases carry the HomerHeaders values`() {
        assertEquals(HomerHeaders.PARENT_PROCESS_ID, EnvConstants.PARENT_PROCESS_HEADER)
        assertEquals(HomerHeaders.CALLBACK_TOKEN, EnvConstants.CALLBACK_TOKEN_HEADER)
        assertEquals(HomerHeaders.AGENT, EnvConstants.AGENT_HEADER)
        assertEquals(HomerHeaders.PROCESS_ID, EnvConstants.PROCESS_ID_HEADER)
    }
}
