package com.nshd.geminifreellm.data

import org.json.JSONArray
import org.json.JSONObject

object SseParser {
    sealed interface Event {
        data class Delta(val text: String) : Event
        data object Done : Event
        data class ToolDelta(val index: Int, val name: String?, val arguments: String) : Event
        data class Malformed(val raw: String) : Event
    }

    fun parseDataLine(line: String): Event? {
        if (!line.startsWith("data:")) return null
        val raw = line.removePrefix("data:").trim()
        if (raw.isBlank()) return null
        if (raw == "[DONE]") return Event.Done
        return runCatching {
            val json = JSONObject(raw)
            val choices = json.optJSONArray("choices") ?: JSONArray()
            val delta = choices.optJSONObject(0)?.optJSONObject("delta")
            val text = delta?.optString("content").orEmpty()
            if (text.isNotEmpty()) return@runCatching Event.Delta(text)
            val toolCalls = delta?.optJSONArray("tool_calls")
            val tool = toolCalls?.optJSONObject(0)
            if (tool != null) {
                val fn = tool.optJSONObject("function")
                Event.ToolDelta(
                    index = tool.optInt("index", 0),
                    name = fn?.optString("name").takeIf { !it.isNullOrBlank() },
                    arguments = fn?.optString("arguments").orEmpty()
                )
            } else null
        }.getOrElse { Event.Malformed(raw.take(2000)) }
    }
}
