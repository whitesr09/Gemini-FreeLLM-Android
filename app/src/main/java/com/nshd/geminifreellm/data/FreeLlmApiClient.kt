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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import android.util.Base64InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ChatResult {
    data class Success(val text: String) : ChatResult
    data class Failure(val message: String) : ChatResult
    data object Cancelled : ChatResult
}

data class ModelInfo(
    val id: String,
    val name: String,
    val available: Boolean,
    val provider: String? = null,
    val supportsVision: Boolean = false,
    val supportsImageGeneration: Boolean = false,
    val supportsVideoGeneration: Boolean = false,
    val contextSize: Long? = null,
    val reasoning: Boolean = false,
    val coding: Boolean = false
)

data class GeneratedMedia(
    val file: File,
    val mimeType: String,
    val displayName: String
)

data class ConnectionCheck(
    val serverReachable: Boolean,
    val authenticationAccepted: Boolean,
    val modelsAvailable: Int,
    val visionAvailable: Boolean,
    val imageGenerationAvailable: Boolean,
    val videoGenerationAvailable: Boolean,
    val latencyMs: Long
)

sealed interface MediaResult {
    data class Success(val media: GeneratedMedia) : MediaResult
    data class Failure(val message: String) : MediaResult
}

class FreeLlmApiClient {
    private companion object {
        const val MAX_MEDIA_BYTES = 25L * 1024L * 1024L
        const val MAX_BASE64_MEDIA_CHARS = 36 * 1024 * 1024
    }

    private val activeCall = java.util.concurrent.atomic.AtomicReference<okhttp3.Call?>(null)
    private val cancelRequested = AtomicBoolean(false)

    fun cancelActive() {
        cancelRequested.set(true)
        activeCall.getAndSet(null)?.cancel()
    }

    private fun beginCall(call: okhttp3.Call): okhttp3.Call {
        cancelRequested.set(false)
        activeCall.set(call)
        return call
    }

    private fun endCall(call: okhttp3.Call) {
        activeCall.compareAndSet(call, null)
    }

    private fun normalizeBaseUrl(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.startsWith("https://") || value.startsWith("http://")) { "Base URL must use http:// or https://." }
        require(!value.contains("\n") && !value.contains("\r") && !value.contains(" ")) { "Base URL contains invalid whitespace." }
        val normalized = value.removeSuffix("/chat/completions").removeSuffix("/models")
        return normalized.trimEnd('/')
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(330, TimeUnit.SECONDS)
        .callTimeout(360, TimeUnit.SECONDS)
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
        val cleanBase = runCatching { normalizeBaseUrl(baseUrl) }.getOrElse { return@withContext ChatResult.Failure(it.message ?: "Invalid Base URL.") }

        if (apiKey.isBlank()) return@withContext ChatResult.Failure("Unified API key is missing.")

        try {
            val jsonMessages = JSONArray()
            messages.filter { it.role != ChatMessage.Role.ERROR }.forEach { message ->
                val role = when (message.role) {
                    ChatMessage.Role.USER -> "user"
                    ChatMessage.Role.ASSISTANT -> "assistant"
                    ChatMessage.Role.ERROR -> "user"
                }
                val content: Any = if (message.role == ChatMessage.Role.USER && message.attachments.isNotEmpty()) {
                    buildContentParts(context, message)
                } else {
                    message.text
                }
                jsonMessages.put(JSONObject().put("role", role).put("content", content))
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

            val call = beginCall(client.newCall(request))
            try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val apiError = ApiErrorMapper.fromHttp(response.code, parseServerError(errorBody))
                    return@withContext ChatResult.Failure(apiError.message)
                }

                val source = response.body?.source()
                if (source == null) return@withContext ChatResult.Failure("The server returned an empty response.")

                val contentType = response.header("Content-Type").orEmpty()
                val textBuilder = StringBuilder()
                if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                    val body = source.buffer().readUtf8()
                    val text = parseChatText(body)
                    if (text.isBlank()) return@withContext ChatResult.Failure("The server returned an unreadable response.")
                    onDeltaOnMain(onDelta, text)
                    return@withContext ChatResult.Success(text)
                }

                val uiBuffer = StringBuilder()
                var lastUiEmitAt = 0L
                suspend fun flushUi(force: Boolean = false) {
                    if (uiBuffer.isEmpty()) return
                    val now = System.nanoTime() / 1_000_000L
                    if (!force && now - lastUiEmitAt < 55L) return
                    val chunk = uiBuffer.toString()
                    uiBuffer.setLength(0)
                    lastUiEmitAt = now
                    onDeltaOnMain(onDelta, chunk)
                }

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val raw = line.removePrefix("data:").trim()
                    if (raw.isBlank()) continue
                    if (raw == "[DONE]") break
                    val delta = runCatching {
                        val json = JSONObject(raw)
                        val choices = json.optJSONArray("choices") ?: JSONArray()
                        choices.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                    }.getOrDefault("")
                    if (delta.isNotEmpty()) {
                        textBuilder.append(delta)
                        uiBuffer.append(delta)
                        flushUi()
                    }
                }
                flushUi(force = true)

                if (cancelRequested.get()) return@withContext ChatResult.Cancelled
                val finalText = textBuilder.toString().trim()
                if (finalText.isBlank()) ChatResult.Failure("The model returned an empty response.")
                else ChatResult.Success(finalText)
            }
            } finally {
                endCall(call)
            }
        } catch (e: java.util.concurrent.CancellationException) {
            cancelRequested.set(true)
            activeCall.getAndSet(null)?.cancel()
            ChatResult.Cancelled
        } catch (_: IOException) {
            if (cancelRequested.get()) ChatResult.Cancelled
            else ChatResult.Failure("You're offline or the server could not be reached. Check your connection and retry.")
        } catch (e: Exception) {
            if (cancelRequested.get()) ChatResult.Cancelled
            else ChatResult.Failure(e.message?.takeIf { it.isNotBlank() } ?: "The server returned an unreadable response.")
        }
    }
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val request = Request.Builder()
                .url(cleanBase + "/models")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .get()
                .build()

            val started = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val latencyMs = (System.nanoTime() - started) / 1_000_000L
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
                                    available = item.optBoolean("available", true),
                                    provider = item.optString("provider").takeIf { it.isNotBlank() },
                                    supportsVision = item.optBoolean("vision", item.optBoolean("supports_vision", false)),
                                    supportsImageGeneration = item.optBoolean("image_generation", item.optBoolean("supports_image_generation", false)),
                                    supportsVideoGeneration = item.optBoolean("video_generation", item.optBoolean("supports_video_generation", false)),
                                    contextSize = item.optLong("context_length", 0L).takeIf { it > 0L },
                                    reasoning = item.optBoolean("reasoning", item.optBoolean("supports_reasoning", false)),
                                    coding = item.optBoolean("coding", item.optBoolean("supports_coding", false))
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun testConnection(baseUrl: String, apiKey: String): Result<ConnectionCheck> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = normalizeBaseUrl(baseUrl)
            require(apiKey.isNotBlank()) { "API key is missing. Add it in Settings." }
            val request = Request.Builder()
                .url(cleanBase + "/models")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .get()
                .build()
            val started = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val latencyMs = (System.nanoTime() - started) / 1_000_000L
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(ApiErrorMapper.fromHttp(response.code, parseServerError(body)).message)
                }
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                val models = buildList {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        if (item.optString("id").isNotBlank()) add(item)
                    }
                }
                ConnectionCheck(
                    serverReachable = true,
                    authenticationAccepted = true,
                    modelsAvailable = models.size,
                    visionAvailable = models.any { it.optBoolean("vision") || it.optBoolean("supports_vision") },
                    imageGenerationAvailable = models.any { it.optBoolean("image_generation") || it.optBoolean("supports_image_generation") },
                    videoGenerationAvailable = models.any { it.optBoolean("video_generation") || it.optBoolean("supports_video_generation") },
                    latencyMs = latencyMs
                )
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
            val cleanBase = normalizeBaseUrl(baseUrl)
            val payload = JSONObject()
                .put("model", model.ifBlank { "auto" })
                .put("prompt", prompt)
                .put("n", 1)
                .put("response_format", "url")

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
                val dir = File(context.filesDir, "generated").apply { mkdirs() }
                val mimeType = item.optString("mime_type").takeIf { it.startsWith("image/") } ?: "image/png"
                val extension = if (mimeType == "image/jpeg") "jpg" else if (mimeType == "image/webp") "webp" else "png"
                val file = File(dir, "image_" + System.currentTimeMillis() + ".$extension")
                if (item.has("b64_json")) {
                    val encoded = item.optString("b64_json")
                    require(encoded.length <= MAX_BASE64_MEDIA_CHARS) { "Generated image is too large to store safely." }
                    Base64InputStream(ByteArrayInputStream(encoded.toByteArray()), Base64.DEFAULT).use { input ->
                        FileOutputStream(file).use { output -> copyBounded(input, output, MAX_MEDIA_BYTES) }
                    }
                } else {
                    val url = item.optString("url")
                    if (url.isBlank()) error("The image provider returned no usable image.")
                    downloadToFile(url, file, MAX_MEDIA_BYTES)
                }
                MediaResult.Success(GeneratedMedia(file, mimeType, file.name))
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
            val cleanBase = normalizeBaseUrl(baseUrl)
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
                val body = response.body ?: error("The video provider returned no data.")
                val maxBytes = 120L * 1024L * 1024L
                val advertised = body.contentLength()
                if (advertised > maxBytes) error("Generated video is too large to store safely.")
                val dir = File(context.filesDir, "generated").apply { mkdirs() }
                val file = File(dir, "video_" + System.currentTimeMillis() + ".mp4")
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > maxBytes) error("Generated video is too large to store safely.")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (!file.exists() || file.length() == 0L) error("The video provider returned an empty file.")
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

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Generated media exceeds the safe size limit." }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun downloadToFile(url: String, target: File, maxBytes: Long) {
        val parsed = url.toHttpUrlOrNull() ?: error("Generated media URL is invalid.")
        require(parsed.isHttps) { "Generated media URL must use HTTPS." }
        val request = Request.Builder().url(parsed).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Generated media download failed (" + response.code + ").")
            val body = response.body ?: error("Generated media response is empty.")
            val length = body.contentLength()
            require(length < 0L || length <= maxBytes) { "Generated media exceeds the safe size limit." }
            body.byteStream().use { input -> FileOutputStream(target).use { output -> copyBounded(input, output, maxBytes) } }
        }
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
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Couldn't download generated image (" + response.code + ").")
            val body = response.body ?: error("Generated image response was empty.")
            val maxBytes = 20L * 1024L * 1024L
            if (body.contentLength() > maxBytes) error("Generated image is too large to store safely.")
            val output = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) error("Generated image is too large to store safely.")
                    output.write(buffer, 0, read)
                }
            }
            output.toByteArray()
        }
    }
}
