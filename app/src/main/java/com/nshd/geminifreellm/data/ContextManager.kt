package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.ChatMessage

object ContextManager {
    const val DEFAULT_MAX_CHARS = 120_000
    data class ContextResult(val messages: List<ChatMessage>, val usedChars: Int, val truncated: Boolean)

    fun trim(messages: List<ChatMessage>, maxChars: Int = DEFAULT_MAX_CHARS): ContextResult {
        if (messages.isEmpty()) return ContextResult(emptyList(), 0, false)
        val limit = maxChars.coerceIn(8_000, 1_000_000)
        var used = 0
        val result = ArrayDeque<ChatMessage>()
        for (message in messages.asReversed()) {
            val cost = message.text.length + message.attachments.sumOf { it.name.length + 128 }
            if (result.isNotEmpty() && used + cost > limit) break
            result.addFirst(message)
            used += cost
        }
        val final = result.toList()
        return ContextResult(final, used, final.size != messages.size)
    }

    fun contextLabel(usedChars: Int, limit: Int): String =
        "Context " + ((usedChars.toDouble() / limit.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 999) +
            "% · " + (usedChars / 1000) + "k/" + (limit / 1000) + "k chars"
}
