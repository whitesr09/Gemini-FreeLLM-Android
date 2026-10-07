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
        val json = JSONObject(String(history.readFully(), Charsets.UTF_8))
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
    .put("theme", settings.theme).put("profiles", JSONArray(settings.profiles.map {
        JSONObject().put("provider", it.provider.name).put("baseUrl", it.baseUrl).put("apiKey", it.apiKey)
            .put("model", it.model).put("stream", it.stream).put("vision", it.vision)
    }))

internal fun settingsFromJson(json: JSONObject): AppSettings = AppSettings(
    selected = Provider.valueOf(json.getString("selected")), theme = json.optString("theme", "SYSTEM"),
    profiles = json.getJSONArray("profiles").objects().map {
        ProviderProfile(Provider.valueOf(it.getString("provider")), it.getString("baseUrl"), it.optString("apiKey"),
            it.getString("model"), it.optBoolean("stream", true), it.optBoolean("vision", false))
    }
)

internal fun conversationsToJson(chats: List<Conversation>) = JSONObject().put("version", 1)
    .put("chats", JSONArray(chats.map { chat ->
        JSONObject().put("id", chat.id).put("title", chat.title).put("draft", chat.draft)
            .put("pinned", chat.pinned).put("archived", chat.archived).put("updatedAt", chat.updatedAt)
            .put("attachments", attachmentsJson(chat.attachments)).put("messages", JSONArray(chat.messages.map {
                JSONObject().put("id", it.id).put("text", it.text).put("role", it.role.name)
                    .put("timestamp", it.timestamp).put("model", it.model).put("interrupted", it.interrupted)
                    .put("attachments", attachmentsJson(it.attachments))
            }))
    }))

internal fun conversationsFromJson(json: JSONObject): List<Conversation> = json.getJSONArray("chats").objects().map { chat ->
    Conversation(chat.getString("id"), chat.getString("title"), chat.getJSONArray("messages").objects().map {
        ChatMessage(it.getString("id"), it.getString("text"), ChatMessage.Role.valueOf(it.getString("role")),
            it.getLong("timestamp"), attachmentsFromJson(it.optJSONArray("attachments")), it.optString("model"), it.optBoolean("interrupted"))
    }, chat.optString("draft"), attachmentsFromJson(chat.optJSONArray("attachments")), chat.optBoolean("pinned"),
        chat.optBoolean("archived"), chat.getLong("updatedAt"))
}

private fun attachmentsJson(attachments: List<Attachment>) = JSONArray(attachments.map {
    JSONObject().put("id", it.id).put("name", it.name).put("mimeType", it.mimeType).put("size", it.size)
        .put("localPath", it.localPath).put("text", it.text)
})
private fun attachmentsFromJson(array: JSONArray?): List<Attachment> = array?.objects()?.map {
    Attachment(it.getString("id"), it.getString("name"), it.getString("mimeType"), it.getLong("size"), it.optString("localPath"), it.optString("text"))
}.orEmpty()
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
