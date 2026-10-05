package com.nshd.geminifreellm.data

import android.content.Context
import com.nshd.geminifreellm.model.Attachment
import org.json.JSONArray
import org.json.JSONObject

data class DraftState(val text: String = "", val attachments: List<Attachment> = emptyList())

class DraftRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drafts_v2", Context.MODE_PRIVATE)

    fun load(sessionId: String): DraftState {
        val prefix = "draft_" + sessionId
        val text = prefs.getString(prefix + "_text", "") ?: ""
        val raw = prefs.getString(prefix + "_attachments", "[]") ?: "[]"
        val attachments = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(Attachment(
                        id = o.optString("id"),
                        name = o.optString("name", "attachment"),
                        mimeType = o.optString("mimeType", "application/octet-stream"),
                        localPath = o.optString("localPath"),
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        kind = runCatching { Attachment.Kind.valueOf(o.optString("kind", Attachment.Kind.FILE.name)) }.getOrDefault(Attachment.Kind.FILE),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    ))
                }
            }
        }.getOrDefault(emptyList())
        return DraftState(text, attachments)
    }

    fun save(sessionId: String, draft: DraftState) {
        val array = JSONArray().apply {
            draft.attachments.distinctBy { it.id }.take(20).forEach { attachment ->
                put(JSONObject()
                    .put("id", attachment.id)
                    .put("name", attachment.name)
                    .put("mimeType", attachment.mimeType)
                    .put("localPath", attachment.localPath)
                    .put("sizeBytes", attachment.sizeBytes)
                    .put("kind", attachment.kind.name)
                    .put("createdAt", attachment.createdAt))
            }
        }
        prefs.edit()
            .putString("draft_" + sessionId + "_text", draft.text.take(20_000))
            .putString("draft_" + sessionId + "_attachments", array.toString())
            .apply()
    }

    fun clear(sessionId: String) {
        prefs.edit().remove("draft_" + sessionId + "_text").remove("draft_" + sessionId + "_attachments").apply()
    }

    fun clearAll() { prefs.edit().clear().apply() }
}
