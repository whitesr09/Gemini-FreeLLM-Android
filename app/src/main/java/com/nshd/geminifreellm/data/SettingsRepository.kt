package com.nshd.geminifreellm.data

import android.content.Context

data class AppSettings(
    val baseUrl: String = "https://nshd-freellm-api.onrender.com/v1",
    val aiName: String = "",
    val selectedModel: String = "auto",
    val theme: String = "SYSTEM",
    val contextLimit: Int = ContextManager.DEFAULT_MAX_CHARS,
    val temporaryDefault: Boolean = false,
    val webSearchDefault: Boolean = false,
    val localToolsEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val appLockEnabled: Boolean = false,
    val appLockTimeoutMinutes: Int = 5,
    val systemPrompt: String = ""
)

class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        baseUrl = prefs.getString("baseUrl", AppSettings().baseUrl) ?: AppSettings().baseUrl,
        aiName = prefs.getString("aiName", "") ?: "",
        selectedModel = prefs.getString("model", "auto") ?: "auto",
        theme = prefs.getString("theme", "SYSTEM") ?: "SYSTEM",
        contextLimit = prefs.getInt("contextLimit", ContextManager.DEFAULT_MAX_CHARS).coerceIn(8_000, 1_000_000),
        temporaryDefault = prefs.getBoolean("temporaryDefault", false),
        webSearchDefault = prefs.getBoolean("webSearchDefault", false),
        localToolsEnabled = prefs.getBoolean("localToolsEnabled", true),
        animationsEnabled = prefs.getBoolean("animationsEnabled", true),
        appLockEnabled = prefs.getBoolean("appLockEnabled", false),
        appLockTimeoutMinutes = prefs.getInt("appLockTimeoutMinutes", 5).coerceIn(1, 60),
        systemPrompt = prefs.getString("systemPrompt", "") ?: ""
    )

    fun save(value: AppSettings) {
        prefs.edit()
            .putString("baseUrl", value.baseUrl)
            .putString("aiName", value.aiName)
            .putString("model", value.selectedModel)
            .putString("theme", value.theme)
            .putInt("contextLimit", value.contextLimit.coerceIn(8_000, 1_000_000))
            .putBoolean("temporaryDefault", value.temporaryDefault)
            .putBoolean("webSearchDefault", value.webSearchDefault)
            .putBoolean("localToolsEnabled", value.localToolsEnabled)
            .putBoolean("animationsEnabled", value.animationsEnabled)
            .putBoolean("appLockEnabled", value.appLockEnabled)
            .putInt("appLockTimeoutMinutes", value.appLockTimeoutMinutes.coerceIn(1, 60))
            .putString("systemPrompt", value.systemPrompt.take(20_000))
            .apply()
    }
}
