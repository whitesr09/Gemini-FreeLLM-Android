package com.nshd.geminifreellm.data

import android.content.Context
import org.json.JSONArray

data class DraftState(val text: String = "", val attachmentIds: List<String> = emptyList())

class DraftRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drafts_v1", Context.MODE_PRIVATE)

    fun load(sessionId: String): DraftState {
        val key = "draft_" + sessionId
        val text = prefs.getString(key + "_text", "") ?: ""
        val raw = prefs.getString(key + "_attachments", null)
        val ids = runCatching {
            val array = JSONArray(raw ?: "[]")
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        }.getOrDefault(emptyList())
        return DraftState(text, ids)
    }

    fun save(sessionId: String, draft: DraftState) {
        prefs.edit()
            .putString("draft_" + sessionId + "_text", draft.text.take(20_000))
            .putString("draft_" + sessionId + "_attachments", JSONArray(draft.attachmentIds.distinct().take(20)).toString())
            .apply()
    }

    fun clear(sessionId: String) {
        prefs.edit()
            .remove("draft_" + sessionId + "_text")
            .remove("draft_" + sessionId + "_attachments")
            .apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
