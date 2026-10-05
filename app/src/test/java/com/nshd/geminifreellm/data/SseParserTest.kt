package com.nshd.geminifreellm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test fun parsesTextDelta() {
        val event = SseParser.parseDataLine(
            """data: {"choices":[{"delta":{"content":"hello"}}]}"""
        )
        assertEquals(SseParser.Event.Delta("hello"), event)
    }

    @Test fun parsesToolDeltaWithId() {
        val event = SseParser.parseDataLine(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"calculator","arguments":"{\"expression\":\"2+2\"}"}}]}}]}"""
        )
        assertTrue(event is SseParser.Event.ToolDelta)
        event as SseParser.Event.ToolDelta
        assertEquals("call_1", event.id)
        assertEquals("calculator", event.name)
    }

    @Test fun parsesDone() {
        assertEquals(SseParser.Event.Done, SseParser.parseDataLine("data: [DONE]"))
    }

    @Test fun ignoresBlankAndNonDataLines() {
        assertEquals(null, SseParser.parseDataLine("event: ping"))
        assertEquals(null, SseParser.parseDataLine("data:"))
    }
}
