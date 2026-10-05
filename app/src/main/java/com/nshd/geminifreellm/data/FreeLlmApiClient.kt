package com.nshd.geminifreellm.data

import android.content.Context
import android.util.Base64
import com.nshd.geminifreellm.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ChatResult {
    data class Success(val text: String) : ChatResult
    data class Failure(val message: String) : ChatResult
}

data class ModelInfo(
    val id: String,
    val name: String,
    val available: Boolean
)

data class GeneratedMedia(
    val file: File,
    val mimeType: String,
    val displayName: String
)

sealed interface MediaResult {
    data class Success(val media: GeneratedMedia) : MediaResult
    data class Failure(val message: String) : MediaResult
}

class FreeLlmApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun send(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit
    ): ChatResult = withContext(Dispatchers.IO) {
        val cleanBase = baseUrl.trim().trimEnd('/')
        if (cleanBase.isBlank()) return@withContext ChatResult.Failure("Base URL is empty.")
        if (apiKey.isBlank()) return@withContext ChatResult.Failure("Unified API key is missing.")

        try {
            val jsonMessages = JSONArray()
            messages.filter { it.role != ChatMessage.Role.ERROR }.forEach { message ->
                val role = when (message.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.ASSISTANT -> "assistant"
                    ChatMessage.Role.ERROR -> "user"
                }

                val content = if (message.role == ChatMessage.Role.USER && message.attachments.isNotEmpty()) {
                    buildContentParts(context, message)
                } else {
                    message.text
                }

                jsonMessages.put(
                    JSONObject()
                        .put("role", role)
                        .put("content", content)
                )
            }

            val payload = JSONObject()
                .put("model", model.ifBlank { "auto" })
                .put("messages", jsonMessages)
                .put("stream", true)

            val request = Request.Builder()
                .url(cleanBase + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream, application/json")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    return@withContext ChatResult.Failure(
                        when (response.code) {
                            401, 403 -> "The API key was rejected. Check your Unified API key."
                            404 -> "API endpoint not found. Check the Base URL."
                            429 -> "The provider is rate-limited. Try again in a moment."
                            in 500..599 -> "Server error (" + response.code + "). Try again."
                            else -> parseServerError(errorBody) ?: "Request failed (" + response.code + ")."
                        }
                    )
                }

                val source = response.body?.source()
                    ?: return@withContext ChatResult.Failure("The server returned an empty response.")

                val contentType = response.header("Content-Type").orEmpty()
                val textBuilder = StringBuilder()

                if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                    val body = source.buffer().readUtf8()
                    val text = parseChatText(body)
                    if (text.isBlank()) return@withContext ChatResult.Failure("The server returned an unreadable response.")
                    onDeltaOnMain(onDelta, text)
                    return@withContext ChatResult.Success(text)
                }

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val raw = line.removePrefix("data:").trim()
                    if (raw.isBlank() || raw == "[DONE]") continue

                    val delta = runCatching {
                        val json = JSONObject(raw)
                        val choices = json.optJSONArray("choices") ?: return@runCatching ""
                        choices.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                            .orEmpty()
                    }.getOrDefault("")

                    if (delta.isNotEmpty()) {
                        textBuilder.append(delta)
                        onDeltaOnMain(onDelta, delta)
                    }
                }

                val finalText = textBuilder.toString().trim()
                if (finalText.isBlank()) ChatResult.Failure("The model returned an empty response.")
                else ChatResult.Success(finalText)
            }
        } catch (_: IOException) {
            ChatResult.Failure("Couldn't reach FreeLLMAPI. The server may be sleeping; try again in a moment.")
        } catch (e: Exception) {
            ChatResult.Failure(e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong.")
        }
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val request = Request.Builder()
                .url(cleanBase + "/models")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(parseServerError(body) ?: "Couldn't load models (" + response.code + ").")
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                buildList {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        if (id.isNotBlank()) {
                            add(
                                ModelInfo(
                                    id = id,
                                    name = item.optString("name", id),
                                    available = item.optBoolean("available", true)
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun generateImage(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String
    ): MediaResult = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val payload = JSONObject()
                .put("model", model.ifBlank { "auto" })
                .put("prompt", prompt)
                .put("n", 1)
                .put("response_format", "b64_json")

            val request = Request.Builder()
                .url(cleanBase + "/images/generations")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(parseServerError(body) ?: "Image generation failed (" + response.code + ").")
                val item = JSONObject(body).optJSONArray("data")?.optJSONObject(0)
                    ?: error("The image provider returned no image.")
                val bytes = if (item.has("b64_json")) {
                    Base64.decode(item.optString("b64_json"), Base64.DEFAULT)
                } else {
                    val url = item.optString("url")
                    if (url.isBlank()) error("The image provider returned no usable image.")
                    downloadBytes(url)
                }
                val dir = File(context.filesDir, "generated").apply { mkdirs() }
                val file = File(dir, "image_" + System.currentTimeMillis() + ".png")
                file.writeBytes(bytes)
                MediaResult.Success(GeneratedMedia(file, "image/png", file.name))
            }
        }.getOrElse { MediaResult.Failure(it.message ?: "Image generation failed.") }
    }

    suspend fun generateVideo(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        prompt: String
    ): MediaResult = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val payload = JSONObject()
                .put("model", model.ifBlank { "auto" })
                .put("prompt", prompt)
                .put("duration", 5)
                .put("aspect_ratio", "16:9")
                .put("audio", true)

            val request = Request.Builder()
                .url(cleanBase + "/videos/generations")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    error(parseServerError(body) ?: "Video generation failed (" + response.code + ").")
                }
                val bytes = response.body?.bytes() ?: error("The video provider returned no data.")
                val dir = File(context.filesDir, "generated").apply { mkdirs() }
                val file = File(dir, "video_" + System.currentTimeMillis() + ".mp4")
                file.writeBytes(bytes)
                MediaResult.Success(GeneratedMedia(file, "video/mp4", file.name))
            }
        }.getOrElse { MediaResult.Failure(it.message ?: "Video generation failed.") }
    }

    private fun buildContentParts(context: Context, message: ChatMessage): JSONArray {
        val parts = JSONArray()
        if (message.text.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", message.text))
        }

        message.attachments.forEach { attachment ->
            val file = File(attachment.localPath)
            if (!file.exists()) return@forEach

            when {
                attachment.mimeType.startsWith("image/") -> {
                    val bytes = DocumentProcessor.compressImage(file)
                    val data = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", data))
                    )
                }
                attachment.mimeType == "application/pdf" || attachment.name.endsWith(".pdf", true) -> {
                    DocumentProcessor.renderPdfPages(context, file).take(8).forEach { page ->
                        val bytes = DocumentProcessor.compressImage(page)
                        val data = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                        parts.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", data))
                        )
                    }
                }
                else -> {
                    val extracted = DocumentProcessor.extractText(context, file, attachment.mimeType, attachment.name)
                    val text = extracted?.take(120_000)
                    val payloadText = if (!text.isNullOrBlank()) {
                        "[Attached file: " + attachment.name + "]\n" + text
                    } else {
                        "[Attached file: " + attachment.name + ". The file is saved locally but its contents could not be extracted on-device.]"
                    }
                    parts.put(JSONObject().put("type", "text").put("text", payloadText))
                }
            }
        }
        return parts
    }

    private suspend fun onDeltaOnMain(onDelta: (String) -> Unit, delta: String) {
        withContext(Dispatchers.Main.immediate) { onDelta(delta) }
    }

    private fun parseChatText(body: String): String {
        return runCatching {
            val choices = JSONObject(body).optJSONArray("choices") ?: JSONArray()
            choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }.getOrDefault("")
    }

    private fun parseServerError(body: String): String? {
        return runCatching {
            JSONObject(body)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun downloadBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Couldn't download generated image (" + response.code + ").")
            return response.body?.bytes() ?: error("Generated image response was empty.")
        }
    }
}
