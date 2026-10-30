package com.nshd.geminifreellm.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.nshd.geminifreellm.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credential ciphertext is device-bound; history and attachments stay in private app storage. */
private const val MAX_STORED_MESSAGES_PER_CHAT = 1000

class LocalStore(private val context: Context) {
    private val credentials = AtomicFile(File(context.filesDir, "providers.enc"))
    private val history = AtomicFile(File(context.filesDir, "conversations.json"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("freellm.providers", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).apply {
            init(KeyGenParameterSpec.Builder("freellm.providers", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    /** Returns false for credentials encrypted by another install/device or a corrupt envelope. */
    fun canDecrypt(envelopeBytes: ByteArray): Boolean = runCatching {
        val envelope = JSONObject(String(envelopeBytes, Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(),
            GCMParameterSpec(128, Base64.getDecoder().decode(envelope.getString("iv"))))
        settingsFromJson(JSONObject(String(
            cipher.doFinal(Base64.getDecoder().decode(envelope.getString("data"))), Charsets.UTF_8)))
    }.isSuccess

    fun loadSettings(): AppSettings {
        if (credentials.baseFile.exists()) {
            val envelope = JSONObject(String(credentials.readFully(), Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(envelope.getString("iv"))))
            val settings = settingsFromJson(JSONObject(String(cipher.doFinal(Base64.getDecoder().decode(envelope.getString("data"))), Charsets.UTF_8)))
            val old = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (old.contains("apiKey")) check(old.edit().remove("apiKey").remove("baseUrl").remove("theme").commit())
            return settings
        }
        val old = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val migrated = AppSettings(
            profiles = listOf(ProviderProfile(baseUrl = old.getString("baseUrl", Provider.FREELLM.endpoint) ?: Provider.FREELLM.endpoint,
                apiKey = old.getString("apiKey", "").orEmpty())),
            theme = old.getString("theme", "SYSTEM") ?: "SYSTEM"
        )
        saveSettings(migrated) // Remove plaintext only after encrypted storage succeeds.
        check(old.edit().remove("apiKey").remove("baseUrl").remove("theme").commit())
        return migrated
    }

    fun saveSettings(settings: AppSettings) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(settingsToJson(settings).toString().toByteArray(Charsets.UTF_8))
        write(credentials, JSONObject().put("iv", Base64.getEncoder().encodeToString(cipher.iv))
            .put("data", Base64.getEncoder().encodeToString(encrypted)).toString())
    }

    fun loadHistory(): Pair<List<Conversation>, String?> {
        if (!history.baseFile.exists()) return emptyList<Conversation>() to null
        // A malformed or incomplete history must not be treated as an empty history:
        // doing so would delete attachment files that may still be referenced by recoverable data.
        val json = runCatching { JSONObject(String(history.readFully(), Charsets.UTF_8)) }.getOrNull()
            ?: return emptyList<Conversation>() to null
        if (json.optJSONArray("chats") == null) return emptyList<Conversation>() to null
        val chats = conversationsFromJson(json)
        // Runs before new attachments can be prepared. Never collect files after a failed read.
        val referenced = chats.flatMap { it.attachments + it.messages.flatMap { message -> message.attachments } }
            .map { it.localPath }.toSet()
        File(context.filesDir, "attachments").listFiles()?.filter { it.path !in referenced }?.forEach { it.delete() }
        return chats to json.optString("active").takeIf { it.isNotBlank() }
    }

    fun saveHistory(chats: List<Conversation>, active: String) {
        write(history, conversationsToJson(chats).put("active", active).toString())
    }

    private fun write(file: AtomicFile, value: String) {
        val stream = file.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

internal fun settingsToJson(settings: AppSettings) = JSONObject().put("selected", settings.selected.name)
    .put("activeProfileId", settings.activeProfileId ?: JSONObject.NULL)
    .put("autoRouting", settings.autoRouting).put("autoExcluded", JSONArray(settings.autoExcluded.map { it.name }))
    .put("agent", agentToJson(settings.agent))
    .put("theme", settings.theme).put("reducedMotion", settings.reducedMotion)
    .put("showTimestamps", settings.showTimestamps).put("compactSpacing", settings.compactSpacing)
    .put("haptics", settings.haptics).put("chatStyle", settings.chatStyle).put("favorites", JSONArray(settings.favorites)).put("profiles", JSONArray(settings.profiles.map {
        JSONObject().put("id", it.id).put("name", it.name)
            .put("provider", it.provider.name).put("baseUrl", it.baseUrl).put("apiKey", it.apiKey)
            .put("model", it.model).put("stream", it.stream).put("vision", it.vision).put("fastReplies", it.fastReplies)
    }))

internal fun settingsFromJson(json: JSONObject): AppSettings = AppSettings(
    autoRouting = json.optBoolean("autoRouting"),
    autoExcluded = json.optJSONArray("autoExcluded")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { Provider.valueOf(a.getString(i)) }.getOrNull() } }.orEmpty(),
    agent = runCatching { agentFromJson(json.optJSONObject("agent") ?: JSONObject()) }.getOrNull() ?: AgentConfig(),
    // Unknown/missing enum names fall back to defaults instead of bricking the whole load.
    selected = runCatching { Provider.valueOf(json.getString("selected")) }.getOrNull() ?: Provider.FREELLM,
    activeProfileId = json.opt("activeProfileId")?.let { if (it == JSONObject.NULL) null else it.toString() },
    theme = json.optString("theme", "SYSTEM"),
    chatStyle = json.optString("chatStyle", ChatStyle.CLASSIC.name).takeIf { it == ChatStyle.AURORA.name } ?: ChatStyle.CLASSIC.name,
    reducedMotion = json.optBoolean("reducedMotion"), showTimestamps = json.optBoolean("showTimestamps"),
    compactSpacing = json.optBoolean("compactSpacing"), haptics = json.optBoolean("haptics", true),
    favorites = runCatching { json.optJSONArray("favorites")?.let { array -> (0 until array.length()).map { array.getString(it) } } }.getOrNull().orEmpty(),
    profiles = runCatching { json.getJSONArray("profiles") }.getOrNull()?.objects()?.mapNotNull { obj ->
        runCatching {
            val provider = Provider.valueOf(obj.getString("provider"))
            ProviderProfile(provider, obj.getString("baseUrl"), obj.optString("apiKey"),
                obj.getString("model"), obj.optBoolean("stream", true), obj.optBoolean("vision", false), obj.optBoolean("fastReplies", false),
                id = obj.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: newId(),
                name = obj.optString("name").takeIf { it.isNotBlank() && it != "null" } ?: provider.label)
        }.getOrNull()
    }.orEmpty().distinctBy { it.id }.ifEmpty { listOf(ProviderProfile()) }
)

internal fun conversationsToJson(chats: List<Conversation>) = JSONObject().put("version", 1)
    .put("chats", JSONArray(chats.map { chat ->
        JSONObject().put("id", chat.id).put("title", chat.title).put("draft", chat.draft)
            .put("pinned", chat.pinned).put("archived", chat.archived).put("updatedAt", chat.updatedAt)
            .put("canvas", JSONObject().put("title", chat.canvas.title).put("language", chat.canvas.language).put("code", chat.canvas.code))
            // Bound stored history: full serialization runs on every save, so cap
            // per-chat messages to keep writes small and bounded.
            .put("attachments", attachmentsJson(chat.attachments)).put("messages", JSONArray(chat.messages.takeLast(MAX_STORED_MESSAGES_PER_CHAT).map {
                JSONObject().put("id", it.id).put("text", it.text).put("role", it.role.name)
                    .put("timestamp", it.timestamp).put("model", it.model).put("interrupted", it.interrupted).put("elapsedMs", it.elapsedMs).put("firstTokenMs", it.firstTokenMs)
                    .put("attachments", attachmentsJson(it.attachments))
            }))
    }))

// Corrupt entries are skipped (not fatal): one bad chat/message must never
// brick the whole history load.
internal fun conversationsFromJson(json: JSONObject): List<Conversation> =
    runCatching { json.getJSONArray("chats") }.getOrNull()?.objects()?.mapNotNull { chat ->
        runCatching {
            val messages = runCatching { chat.getJSONArray("messages") }.getOrNull()
                ?.objects()?.mapNotNull { m ->
                    runCatching {
                        ChatMessage(
                            m.getString("id"), m.getString("text"),
                            runCatching { ChatMessage.Role.valueOf(m.getString("role")) }.getOrNull() ?: ChatMessage.Role.USER,
                            m.optLong("timestamp", System.currentTimeMillis()),
                            attachmentsFromJson(m.optJSONArray("attachments")),
                            m.optString("model"), m.optBoolean("interrupted"),
                            m.optLong("elapsedMs"), m.optLong("firstTokenMs")
                        )
                    }.getOrNull()
                }.orEmpty()
            Conversation(
                chat.getString("id"), chat.optString("title", "New chat"), messages,
                chat.optString("draft"), attachmentsFromJson(chat.optJSONArray("attachments")),
                chat.optBoolean("pinned"), chat.optBoolean("archived"),
                chat.optLong("updatedAt", System.currentTimeMillis()),
                chat.optJSONObject("canvas")?.let {
                    CanvasDocument(it.optString("title", "Untitled"), it.optString("language", "text"), it.optString("code").take(120_000))
                } ?: CanvasDocument()
            )
        }.getOrNull()
    }.orEmpty()

private fun attachmentsJson(attachments: List<Attachment>) = JSONArray(attachments.map {
    JSONObject().put("id", it.id).put("name", it.name).put("mimeType", it.mimeType).put("size", it.size)
        .put("localPath", it.localPath).put("text", it.text)
})
private fun attachmentsFromJson(array: JSONArray?): List<Attachment> = array?.objects()?.mapNotNull {
    runCatching {
        Attachment(it.getString("id"), it.getString("name"), it.getString("mimeType"), it.getLong("size"), it.optString("localPath"), it.optString("text"))
    }.getOrNull()
}.orEmpty()
private fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { index -> runCatching { optJSONObject(index) }.getOrNull() }
