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

data class ResponseMetadata(
    val model: String? = null,
    val provider: String? = null,
    val routedVia: String? = null,
    val fallbackAttempts: Int = 0,
    val requestId: String? = null,
    val latencyMs: Long? = null,
    val tokenUsage: String? = null
)

data class ChatMessage(
    val id: Long,
    val text: String,
    val role: Role,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    val parentMessageId: Long? = null,
    val metadata: ResponseMetadata? = null
) {
    enum class Role { USER, ASSISTANT, ERROR }

    fun branchKey(): Long? = parentMessageId

    fun toJsonObject(): JSONObject {
        val mo = JSONObject()
            .put("id", id)
            .put("text", text)
            .put("role", role.name)
            .put("timestamp", timestamp)
        parentMessageId?.let { mo.put("parentMessageId", it) }
        metadata?.let { m ->
            val meta = JSONObject()
                .put("model", m.model ?: "")
                .put("provider", m.provider ?: "")
                .put("routedVia", m.routedVia ?: "")
                .put("fallbackAttempts", m.fallbackAttempts)
                .put("requestId", m.requestId ?: "")
                .put("latencyMs", m.latencyMs ?: 0L)
                .put("tokenUsage", m.tokenUsage ?: "")
            mo.put("metadata", meta)
        }
        val aa = JSONArray()
        attachments.forEach { a ->
            aa.put(
                JSONObject()
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("mimeType", a.mimeType)
                    .put("localPath", a.localPath)
                    .put("sizeBytes", a.sizeBytes)
                    .put("kind", a.kind.name)
                    .put("createdAt", a.createdAt)
            )
        }
        mo.put("attachments", aa)
        return mo
    }

    companion object {
        fun fromJson(o: JSONObject): ChatMessage {
            val attachments = mutableListOf<Attachment>()
            val aa = o.optJSONArray("attachments") ?: JSONArray()
            for (j in 0 until aa.length()) {
                val ao = aa.optJSONObject(j) ?: continue
                attachments += Attachment(
                    id = ao.optString("id"),
                    name = ao.optString("name"),
                    mimeType = ao.optString("mimeType", "application/octet-stream"),
                    localPath = ao.optString("localPath"),
                    sizeBytes = ao.optLong("sizeBytes", 0L),
                    kind = runCatching { Attachment.Kind.valueOf(ao.optString("kind", Attachment.Kind.FILE.name)) }
                        .getOrDefault(Attachment.Kind.FILE),
                    createdAt = ao.optLong("createdAt", System.currentTimeMillis())
                )
            }
            val meta = o.optJSONObject("metadata")?.let { m ->
                ResponseMetadata(
                    model = m.optString("model").takeIf { it.isNotBlank() },
                    provider = m.optString("provider").takeIf { it.isNotBlank() },
                    routedVia = m.optString("routedVia").takeIf { it.isNotBlank() },
                    fallbackAttempts = m.optInt("fallbackAttempts", 0),
                    requestId = m.optString("requestId").takeIf { it.isNotBlank() },
                    latencyMs = m.optLong("latencyMs", 0L).takeIf { it > 0L },
                    tokenUsage = m.optString("tokenUsage").takeIf { it.isNotBlank() }
                )
            }
            return ChatMessage(
                id = o.optLong("id", System.currentTimeMillis()),
                text = o.optString("text"),
                role = runCatching { Role.valueOf(o.optString("role", Role.ASSISTANT.name)) }
                    .getOrDefault(Role.ASSISTANT),
                timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                attachments = attachments,
                parentMessageId = o.optLong("parentMessageId", 0L).takeIf { it != 0L },
                metadata = meta
            )
        }
    }
}

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage> = emptyList(),
    val starred: Boolean = false,
    val temporary: Boolean = false,
    val archived: Boolean = false,
    val systemPrompt: String? = null
) {
    fun preview(): String = messages.lastOrNull()?.text?.replace("\n", " ")?.trim().orEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
            .put("starred", starred)
            .put("temporary", temporary)
            .put("archived", archived)
        systemPrompt?.let { o.put("systemPrompt", it) }
        o.put("messages", JSONArray().apply { messages.forEach { put(it.toJsonObject()) } })
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): ChatSession {
            val arr = o.optJSONArray("messages") ?: JSONArray()
            val messages = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(ChatMessage.fromJson(item))
                }
            }
            return ChatSession(
                id = o.optString("id"),
                title = o.optString("title", "New chat"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                messages = messages,
                starred = o.optBoolean("starred", false),
                temporary = o.optBoolean("temporary", false),
                archived = o.optBoolean("archived", false),
                systemPrompt = o.optString("systemPrompt").takeIf { it.isNotBlank() }
            )
        }
    }
}
