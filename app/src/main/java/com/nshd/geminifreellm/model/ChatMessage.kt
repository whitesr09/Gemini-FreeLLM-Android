package com.nshd.geminifreellm.model

import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

data class Attachment(
    val id: String = newId(),
    val name: String,
    val mimeType: String,
    val size: Long,
    val localPath: String = "",
    val text: String = ""
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

data class ChatMessage(
    val id: String = newId(),
    val text: String,
    val role: Role,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    val model: String = "",
    val interrupted: Boolean = false
) {
    enum class Role { USER, ASSISTANT, ERROR }
}

data class Conversation(
    val id: String = newId(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val attachments: List<Attachment> = emptyList(),
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ApiProtocol { OPENAI, GEMINI, ANTHROPIC }

enum class Provider(val label: String, val endpoint: String, val defaultModel: String, val protocol: ApiProtocol) {
    FREELLM("FreeLLMAPI", "http://127.0.0.1:3001/v1", "auto", ApiProtocol.OPENAI),
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", ApiProtocol.OPENAI),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", ApiProtocol.GEMINI),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "claude-sonnet-4-5", ApiProtocol.ANTHROPIC),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free", ApiProtocol.OPENAI),
    GROQ("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", ApiProtocol.OPENAI),
    CUSTOM("OpenAI-compatible / unified", "", "", ApiProtocol.OPENAI)
}

data class ProviderProfile(
    val provider: Provider = Provider.FREELLM,
    val baseUrl: String = provider.endpoint,
    val apiKey: String = "",
    val model: String = provider.defaultModel,
    val stream: Boolean = true,
    val vision: Boolean = false
)

data class AppSettings(
    val profiles: List<ProviderProfile> = listOf(ProviderProfile()),
    val selected: Provider = Provider.FREELLM,
    val theme: String = "SYSTEM"
) {
    val active: ProviderProfile get() = profiles.firstOrNull { it.provider == selected } ?: ProviderProfile(selected)
}
