package com.nshd.geminifreellm.data

import android.content.Context
import com.nshd.geminifreellm.model.ChatSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LocalChatStore(context: Context) {
    private val prefs = context.getSharedPreferences("chat_store_v2", Context.MODE_PRIVATE)

    fun loadSessions(): List<ChatSession> {
        val raw = prefs.getString("sessions", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val session = ChatSession.fromJson(item)
                    if (session.id.isNotBlank()) add(session)
                }
            }.sortedWith(compareByDescending<ChatSession> { it.starred }.thenByDescending { it.updatedAt })
        }.getOrDefault(emptyList())
    }

    fun currentId(): String? = prefs.getString("current_id", null)

    fun saveSessions(sessions: List<ChatSession>, currentId: String?) {
        val array = JSONArray()
        sessions.forEach { array.put(it.toJson()) }
        val editor = prefs.edit().putString("sessions", array.toString())
        if (currentId != null) editor.putString("current_id", currentId) else editor.remove("current_id")
        editor.apply()
    }

    fun saveCurrentId(id: String) = prefs.edit().putString("current_id", id).apply()

    fun newSession(): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = "New chat",
            createdAt = now,
            updatedAt = now
        )
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun exportJson(sessions: List<ChatSession>): String {
        val root = JSONObject()
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())
        val array = JSONArray()
        sessions.forEach { array.put(it.toJson()) }
        root.put("sessions", array)
        return root.toString(2)
    }

    fun importJson(raw: String): List<ChatSession> {
        val root = JSONObject(raw)
        val array = root.optJSONArray("sessions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val session = ChatSession.fromJson(array.optJSONObject(i) ?: JSONObject())
                if (session.id.isNotBlank()) add(session)
            }
        }
    }
}
