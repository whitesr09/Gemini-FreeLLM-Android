package com.nshd.geminifreellm.model

import org.json.JSONArray
import org.json.JSONObject

data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long = 0L,
    val kind: Kind = Kind.FILE,
    val createdAt: Long = System.currentTimeMillis()
) {
    enum class Kind { IMAGE, FILE, GENERATED_IMAGE, GENERATED_VIDEO }
}

data class ChatMessage(
    val id: Long,
    val text: String,
    val role: Role,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList()
) {
    enum class Role { USER, ASSISTANT, ERROR }
}

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val starred: Boolean = false
) {
    fun preview(): String = messages.lastOrNull()?.text?.replace("\n", " ")?.trim().orEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("title", title)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        o.put("starred", starred)
        val arr = JSONArray()
        messages.forEach { m ->
            val mo = JSONObject()
            mo.put("id", m.id)
            mo.put("text", m.text)
            mo.put("role", m.role.name)
            mo.put("timestamp", m.timestamp)
            val aa = JSONArray()
            m.attachments.forEach { a ->
                val ao = JSONObject()
                ao.put("id", a.id)
                ao.put("name", a.name)
                ao.put("mimeType", a.mimeType)
                ao.put("localPath", a.localPath)
                ao.put("sizeBytes", a.sizeBytes)
                ao.put("kind", a.kind.name)
                ao.put("createdAt", a.createdAt)
                aa.put(ao)
            }
            mo.put("attachments", aa)
            arr.put(mo)
        }
        o.put("messages", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ChatSession {
            val messages = mutableListOf<ChatMessage>()
            val arr = o.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val mo = arr.optJSONObject(i) ?: continue
                val attachments = mutableListOf<Attachment>()
                val aa = mo.optJSONArray("attachments") ?: JSONArray()
                for (j in 0 until aa.length()) {
                    val ao = aa.optJSONObject(j) ?: continue
                    attachments += Attachment(
                        id = ao.optString("id"),
                        name = ao.optString("name"),
                        mimeType = ao.optString("mimeType", "application/octet-stream"),
                        localPath = ao.optString("localPath"),
                        sizeBytes = ao.optLong("sizeBytes", 0L),
                        kind = runCatching {
                            Attachment.Kind.valueOf(ao.optString("kind", Attachment.Kind.FILE.name))
                        }.getOrDefault(Attachment.Kind.FILE),
                        createdAt = ao.optLong("createdAt", System.currentTimeMillis())
                    )
                }
                messages += ChatMessage(
                    id = mo.optLong("id", System.currentTimeMillis()),
                    text = mo.optString("text"),
                    role = runCatching {
                        ChatMessage.Role.valueOf(mo.optString("role", ChatMessage.Role.ASSISTANT.name))
                    }.getOrDefault(ChatMessage.Role.ASSISTANT),
                    timestamp = mo.optLong("timestamp", System.currentTimeMillis()),
                    attachments = attachments
                )
            }
            return ChatSession(
                id = o.optString("id"),
                title = o.optString("title", "New chat"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                messages = messages,
                starred = o.optBoolean("starred", false)
            )
        }
    }
}
