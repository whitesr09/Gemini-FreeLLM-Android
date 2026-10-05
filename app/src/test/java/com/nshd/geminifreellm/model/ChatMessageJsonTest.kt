package com.nshd.geminifreellm.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageJsonTest {
    @Test
    fun preservesParentMessageIdAcrossSessionJsonRoundTrip() {
        val original = ChatSession(
            id = "session",
            title = "Test",
            createdAt = 1L,
            updatedAt = 2L,
            messages = listOf(
                ChatMessage(1L, "user", ChatMessage.Role.USER),
                ChatMessage(2L, "old answer", ChatMessage.Role.ASSISTANT),
                ChatMessage(3L, "regenerated", ChatMessage.Role.ASSISTANT, parentMessageId = 2L)
            )
        )
        val restored = ChatSession.fromJson(original.toJson())
        assertEquals(2L, restored.messages[2].parentMessageId)
    }
}
