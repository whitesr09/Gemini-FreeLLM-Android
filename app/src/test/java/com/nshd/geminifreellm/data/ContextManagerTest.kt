package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
    @Test fun keepsRecentMessagesAndMarksTruncation() {
        val messages = (1..10).map {
            ChatMessage(it.toLong(), "message-$it" + "x".repeat(30), ChatMessage.Role.USER)
        }
        val result = ContextManager.trim(messages, 8_000)
        assertEquals(messages, result.messages)
        assertTrue(!result.truncated)
    }

    @Test fun enforcesMinimumAndReturnsNewestData() {
        val messages = (1..20).map {
            ChatMessage(it.toLong(), "message-$it" + "x".repeat(800), ChatMessage.Role.USER)
        }
        val result = ContextManager.trim(messages, 8_000)
        assertTrue(result.usedChars <= 8_000 + 900)
        assertTrue(result.messages.isNotEmpty())
        assertEquals(messages.last().id, result.messages.last().id)
    }
}
