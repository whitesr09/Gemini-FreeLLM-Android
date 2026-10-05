package com.nshd.geminifreellm.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ModelCache {
    private const val PREFS = "model_cache"
    private const val KEY_MODELS = "models_json"
    private const val KEY_SAVED_AT = "saved_at"

    fun save(context: Context, models: List<ModelInfo>) {
        val array = JSONArray().apply {
            models.forEach { model ->
                put(
                    JSONObject()
                        .put("id", model.id)
                        .put("name", model.name)
                        .put("available", model.available)
                        .put("provider", model.provider)
                        .put("vision", model.supportsVision)
                        .put("image_generation", model.supportsImageGeneration)
                        .put("video_generation", model.supportsVideoGeneration)
                        .put("context_length", model.contextSize ?: JSONObject.NULL)
                        .put("reasoning", model.reasoning)
                        .put("coding", model.coding)
                )
            }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODELS, array.toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): CachedModels? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MODELS, null) ?: return null
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        return runCatching {
            val array = JSONArray(raw)
            val models = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    add(
                        ModelInfo(
                            id = id,
                            name = item.optString("name", id),
                            available = item.optBoolean("available", true),
                            provider = item.optString("provider").takeIf { it.isNotBlank() },
                            supportsVision = item.optBoolean("vision"),
                            supportsImageGeneration = item.optBoolean("image_generation"),
                            supportsVideoGeneration = item.optBoolean("video_generation"),
                            contextSize = item.optLong("context_length", 0L).takeIf { it > 0L },
                            reasoning = item.optBoolean("reasoning"),
                            coding = item.optBoolean("coding")
                        )
                    )
                }
            }
            CachedModels(models, savedAt)
        }.getOrNull()
    }
}

data class CachedModels(val models: List<ModelInfo>, val savedAt: Long)
