package com.nshd.geminifreellm.data

import org.json.JSONArray
import org.json.JSONObject

object SseParser {
    sealed interface Event {
        data class Delta(val text: String) : Event
        data object Done : Event
        data class ToolDelta(val index: Int, val id: String?, val name: String?, val arguments: String) : Event
        data class Malformed(val raw: String) : Event
    }

    fun parseDataLine(line: String): Event? {
        if (!line.startsWith("data:")) return null
        val raw = line.removePrefix("data:").trim()
        if (raw.isBlank()) return null
        if (raw == "[DONE]") return Event.Done

        return runCatching {
            val json = JSONObject(raw)
            val delta = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")

            val text = delta?.optString("content").orEmpty()
            if (text.isNotEmpty()) return@runCatching Event.Delta(text)

            val tool = delta?.optJSONArray("tool_calls")?.optJSONObject(0)
            if (tool != null) {
                val fn = tool.optJSONObject("function")
                return@runCatching Event.ToolDelta(
                    index = tool.optInt("index", 0),
                    id = tool.optString("id").takeIf { it.isNotBlank() },
                    name = fn?.optString("name")?.takeIf { it.isNotBlank() },
                    arguments = fn?.optString("arguments").orEmpty()
                )
            }
            null
        }.getOrElse {
            parseWithFallback(raw)
        }
    }

    private fun parseWithFallback(raw: String): Event? {
        val content = Regex("""\"content\"\s*:\s*\"((?:\\.|[^"])*)\"""")
            .find(raw)?.groupValues?.getOrNull(1)
        if (!content.isNullOrBlank()) {
            return Event.Delta(unescapeJsonString(content))
        }

        val toolId = Regex("""\"id\"\s*:\s*\"([^"]+)\"""")
            .find(raw)?.groupValues?.getOrNull(1)
        val toolName = Regex("""\"name\"\s*:\s*\"([^"]+)\"""")
            .find(raw)?.groupValues?.getOrNull(1)
        val arguments = Regex("""\"arguments\"\s*:\s*\"((?:\\.|[^"])*)\"""")
            .find(raw)?.groupValues?.getOrNull(1)
            ?: ""

        if (toolId != null || toolName != null || arguments.isNotBlank()) {
            val index = Regex("""\"index\"\s*:\s*(\d+)""")
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            return Event.ToolDelta(index, toolId, toolName, unescapeJsonString(arguments))
        }
        return Event.Malformed(raw.take(2000))
    }

    private fun unescapeJsonString(value: String): String = value
        .replace("\\\"", """)
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")

}
