package pro.dockhand.mobile.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseTest {

    @Test
    fun parserDecodesConnectedAndLogEvents() {
        val parser = ContainerLogSSEParser()

        assertNull(parser.consume("event: connected"))
        assertNull(parser.consume("""data: {"containerId":"container-1"}"""))
        assertEquals(ContainerLogEvent.Connected, parser.consume(""))

        assertNull(parser.consume("event: log"))
        assertNull(parser.consume("""data: {"text":"first\nsecond\n"}"""))
        assertEquals(ContainerLogEvent.Log("first\nsecond\n"), parser.consume(""))
    }

    @Test
    fun parserHandlesHeartbeatErrorAndEnd() {
        val parser = ContainerLogSSEParser()

        assertNull(parser.consume(": keepalive"))
        assertNull(parser.consume(""))

        assertNull(parser.consume("event: error"))
        assertNull(parser.consume("""data: {"error":"Docker API error: 500"}"""))
        assertEquals(
            ContainerLogEvent.ServerError("Docker API error: 500"),
            parser.consume("")
        )

        assertNull(parser.consume("event: end"))
        assertNull(parser.consume("""data: {"reason":"stream ended"}"""))
        assertEquals(ContainerLogEvent.Ended, parser.consume(""))
    }

    @Test
    fun parserFallsBackToReasonAndRawPayloadForErrors() {
        val parser = ContainerLogSSEParser()

        assertNull(parser.consume("event: error"))
        assertNull(parser.consume("""data: {"reason":"driver gone"}"""))
        assertEquals(ContainerLogEvent.ServerError("driver gone"), parser.consume(""))

        assertNull(parser.consume("event: error"))
        assertNull(parser.consume("data: plain failure"))
        assertEquals(ContainerLogEvent.ServerError("plain failure"), parser.consume(""))
    }

    @Test
    fun decoderHandlesLinesSplitAcrossNetworkChunks() {
        val decoder = ContainerLogSSEDecoder()

        assertTrue(decoder.consume("event: log\r\nda".toByteArray()).isEmpty())
        val events = decoder.consume("ta: {\"text\":\"live line\\n\"}\r\n\r\n".toByteArray())

        assertEquals(1, events.size)
        assertEquals(ContainerLogEvent.Log("live line\n"), events.first())
    }

    @Test
    fun decoderDecodesDataFieldsAndFlushesTrailingLine() {
        val decoder = ContainerLogSSEDecoder()

        val events = decoder.consume(
            ": heartbeat\nevent: connected\ndata: {\"containerId\":\"c1\"}\n\n" +
                "event: log\ndata: {\"text\":\"line-1\\nline-2\\n\"}\n\n"
        )
        assertEquals(2, events.size)
        assertEquals(ContainerLogEvent.Connected, events[0])
        assertEquals(ContainerLogEvent.Log("line-1\nline-2\n"), events[1])

        assertTrue(decoder.consume("event: end\ndata: {\"reason\":\"done\"}").isEmpty())
        assertTrue(decoder.finish().isEmpty())

        val finishDecoder = ContainerLogSSEDecoder()
        assertTrue(finishDecoder.consume("event: end\ndata: {\"reason\":\"done\"}\n\r").isEmpty())
        assertEquals(listOf(ContainerLogEvent.Ended), finishDecoder.finish())
    }
}
