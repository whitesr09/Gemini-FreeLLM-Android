package com.nshd.geminifreellm.data

import android.content.Context
import android.util.Base64
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ResponseMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

sealed interface ChatResult {
    data class Success(val text: String, val metadata: ResponseMetadata? = null) : ChatResult
    data class Failure(val message: String, val error: ApiError? = null) : ChatResult
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
    val coding: Boolean = false,
    val speed: String? = null
)

data class GeneratedMedia(val file: File, val mimeType: String, val displayName: String)

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

data class SpeechResult(val file: File, val mimeType: String = "audio/mpeg")

class FreeLlmApiClient {
    private companion object {
        const val MAX_ATTACHMENT_IMAGE_BYTES = 25L * 1024L * 1024L
        const val MAX_MEDIA_BYTES = 25L * 1024L * 1024L
        const val MAX_VIDEO_BYTES = 120L * 1024L * 1024L
        const val MAX_BASE64_MEDIA_CHARS = 36 * 1024 * 1024
        const val MAX_TOOL_CALLS = 4
        const val MAX_TOOL_ARGUMENT_CHARS = 16_384
    }

    private data class ToolCall(val id: String, val name: String, val arguments: String)
    private data class StreamOutcome(
        val text: String,
        val toolCalls: List<ToolCall>,
        val metadata: ResponseMetadata?
    )

    private val activeCall = AtomicReference<okhttp3.Call?>(null)
    private val cancelRequested = AtomicBoolean(false)

    private val chatClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(330, TimeUnit.SECONDS)
        .callTimeout(360, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val shortClient = chatClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaClient = chatClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(330, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    fun cancelActive() {
        cancelRequested.set(true)
        activeCall.getAndSet(null)?.cancel()
    }

    suspend fun send(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        onDelta: (String) -> Unit,
        systemPrompt: String = "",
        contextLimit: Int = ContextManager.DEFAULT_MAX_CHARS,
        webSearch: Boolean = false,
        localTools: Boolean = true
    ): ChatResult = withContext(Dispatchers.IO) {
        val cleanBase = runCatching { normalizeBaseUrl(baseUrl) }
            .getOrElse { return@withContext ChatResult.Failure(it.message ?: "Invalid Base URL.") }
        if (apiKey.isBlank()) return@withContext ChatResult.Failure("Unified API key is missing.")

        try {
            val trimmed = ContextManager.trim(
                messages.filter { it.role != ChatMessage.Role.ERROR },
                contextLimit
            )
            val requestMessages = mutableListOf<JSONObject>()
            if (systemPrompt.isNotBlank()) {
                requestMessages += JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt.take(20_000))
            }
            trimmed.messages.forEach { requestMessages += messageToJson(context, it) }

            val tools = buildTools(webSearch, localTools)
            var latest = streamRequest(
                cleanBase, apiKey, model.ifBlank { "auto" }, requestMessages, tools, onDelta
            )

            if (latest.toolCalls.any { !it.name.equals("google_search",true) }) {
                val followUp = requestMessages.toMutableList()
                val assistant = JSONObject()
                    .put("role", "assistant")
                    .put("content", if (latest.text.isBlank()) JSONObject.NULL else latest.text)
                val calls = JSONArray()
                latest.toolCalls.take(MAX_TOOL_CALLS).forEach { call ->
                    calls.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", call.name).put("arguments", call.arguments)
                            )
                    )
                }
                assistant.put("tool_calls", calls)
                followUp += assistant

                latest.toolCalls.take(MAX_TOOL_CALLS).forEach { call ->
                    if (call.name.equals("google_search", true)) return@forEach
                    val execution = LocalToolRegistry.execute(
                        call.name,
                        call.arguments.take(MAX_TOOL_ARGUMENT_CHARS)
                    )
                    followUp += JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.id)
                        .put("content", execution.result.take(16_000))
                }

                latest = streamRequest(
                    cleanBase, apiKey, model.ifBlank { "auto" }, followUp, tools, onDelta
                )
            }

            if (cancelRequested.get()) return@withContext ChatResult.Cancelled
            if (latest.text.isBlank()) return@withContext ChatResult.Failure("The model returned an empty response.")
            ChatResult.Success(latest.text.trim(), latest.metadata)
        } catch (_: CancellationException) {
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

    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanBase = normalizeBaseUrl(baseUrl)
                val request = Request.Builder()
                    .url(cleanBase + "/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                executeGetWithRetry(request).use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val err = ApiErrorMapper.fromHttp(response.code, parseServerError(body))
                        throw IOException(err.message)
                    }
                    parseModels(body)
                }
            }
        }

    suspend fun testConnection(baseUrl: String, apiKey: String): Result<ConnectionCheck> =
        withContext(Dispatchers.IO) {
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
                shortClient.newCall(request).execute().use { response ->
                    val latencyMs = (System.nanoTime() - started) / 1_000_000L
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val err = ApiErrorMapper.fromHttp(response.code, parseServerError(body))
                        throw IOException(err.message)
                    }
                    val models = parseModels(body)
                    ConnectionCheck(
                        serverReachable = true,
                        authenticationAccepted = true,
                        modelsAvailable = models.size,
                        visionAvailable = models.any { it.supportsVision },
                        imageGenerationAvailable = models.any { it.supportsImageGeneration },
                        videoGenerationAvailable = models.any { it.supportsVideoGeneration },
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
                .put("prompt", prompt.take(20_000))
                .put("n", 1)
                .put("response_format", "url")
            val request = Request.Builder()
                .url(cleanBase + "/images/generations")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = beginCall(mediaClient.newCall(request))
            try {
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val err = ApiErrorMapper.fromHttp(response.code, parseServerError(body))
                        throw IOException(err.message)
                    }
                    val item = JSONObject(body).optJSONArray("data")?.optJSONObject(0)
                        ?: error("The image provider returned no image.")
                    val dir = File(context.filesDir, "generated").apply { mkdirs() }
                    val mime = item.optString("mime_type").takeIf { it.startsWith("image/") } ?: "image/png"
                    val ext = when (mime) {
                        "image/jpeg" -> "jpg"
                        "image/webp" -> "webp"
                        else -> "png"
                    }
                    val file = File(dir, "image_" + System.currentTimeMillis() + "." + ext)
                    if (item.has("b64_json")) {
                        val encoded = item.optString("b64_json")
                        require(encoded.length <= MAX_BASE64_MEDIA_CHARS) { "Generated image is too large to store safely." }
                        ByteArrayInputStream(encoded.toByteArray(StandardCharsets.US_ASCII)).use { raw ->
                            android.util.Base64InputStream(raw, Base64.DEFAULT).use { input ->
                                FileOutputStream(file).use { output -> copyBounded(input, output, MAX_MEDIA_BYTES) }
                            }
                        }
                    } else {
                        val url = item.optString("url")
                        require(url.isNotBlank()) { "The image provider returned no usable image." }
                        downloadToFile(url, file, MAX_MEDIA_BYTES)
                    }
                    validateImageFile(file)
                    MediaResult.Success(GeneratedMedia(file, mime, file.name))
                }
            } finally {
                endCall(call)
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
                .put("prompt", prompt.take(20_000))
                .put("duration", 5)
                .put("aspect_ratio", "16:9")
                .put("audio", true)
            val request = Request.Builder()
                .url(cleanBase + "/videos/generations")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = beginCall(mediaClient.newCall(request))
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val err = ApiErrorMapper.fromHttp(response.code, parseServerError(body))
                        throw IOException(err.message)
                    }
                    val body = response.body ?: error("The video provider returned no data.")
                    val dir = File(context.filesDir, "generated").apply { mkdirs() }
                    val file = File(dir, "video_" + System.currentTimeMillis() + ".mp4")
                    val maxBytes = MAX_VIDEO_BYTES
                    val advertised = body.contentLength()
                    require(advertised < 0L || advertised <= maxBytes) { "Generated video is too large to store safely." }
                    body.byteStream().use { input ->
                        file.outputStream().use { output -> copyBounded(input, output, maxBytes) }
                    }
                    require(file.exists() && file.length() > 0L) { "The video provider returned an empty file." }
                    MediaResult.Success(GeneratedMedia(file, "video/mp4", file.name))
                }
            } finally {
                endCall(call)
            }
        }.getOrElse { MediaResult.Failure(it.message ?: "Video generation failed.") }
    }

    suspend fun generateSpeech(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        text: String,
        voice: String = "alloy"
    ): Result<SpeechResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val payload = JSONObject()
                .put("model", model.ifBlank { "auto" })
                .put("input", text.take(20_000))
                .put("voice", voice)
                .put("response_format", "mp3")
            val request = Request.Builder()
                .url(cleanBase + "/audio/speech")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = beginCall(mediaClient.newCall(request))
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val err = ApiErrorMapper.fromHttp(
                            response.code,
                            parseServerError(response.body?.string().orEmpty())
                        )
                        throw IOException(err.message)
                    }
                    val body = response.body ?: error("Speech provider returned no audio.")
                    val target = File(context.cacheDir, "speech_" + System.currentTimeMillis() + ".mp3")
                    FileOutputStream(target).use { output -> copyBounded(body.byteStream(), output, MAX_MEDIA_BYTES) }
                    require(target.isFile && target.length() > 0L) { "Speech provider returned empty audio." }
                    SpeechResult(target)
                }
            } finally {
                endCall(call)
            }
        }
    }

    suspend fun createEmbedding(
        baseUrl: String,
        apiKey: String,
        model: String,
        text: String
    ): Result<List<Float>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = normalizeBaseUrl(baseUrl)
            val payload = JSONObject()
                .put("model", model.take(200))
                .put("input", text.take(100_000))
            val request = Request.Builder()
                .url(cleanBase + "/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = beginCall(shortClient.newCall(request))
            try {
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val err = ApiErrorMapper.fromHttp(response.code, parseServerError(body))
                        throw IOException(err.message)
                    }
                    val embedding = JSONObject(body)
                        .optJSONArray("data")
                        ?.optJSONObject(0)
                        ?.optJSONArray("embedding")
                        ?: error("Embedding response is missing vector data.")
                    buildList {
                        for (i in 0 until embedding.length()) add(embedding.optDouble(i).toFloat())
                    }
                }
            } finally {
                endCall(call)
            }
        }
    }

    private fun buildTools(webSearch: Boolean, localTools: Boolean): JSONArray? {
        val tools = JSONArray()
        if (webSearch) {
            tools.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", "google_search")
                            .put("description", "Use Google Search grounding for current web information.")
                            .put("parameters", JSONObject())
                    )
            )
        }
        if (localTools) {
            LocalToolRegistry.definitionsJson().forEach { map ->
                val type = map["type"] as? String ?: return@forEach
                val fn = map["function"] as? Map<*, *> ?: return@forEach
                val params = fn["parameters"] as? Map<*, *> ?: emptyMap<Any, Any>()
                tools.put(
                    JSONObject()
                        .put("type", type)
                        .put(
                            "function",
                            JSONObject()
                                .put("name", fn["name"])
                                .put("description", fn["description"])
                                .put("parameters", JSONObject(params))
                        )
                )
            }
        }
        return tools.takeIf { it.length() > 0 }
    }

    private fun messageToJson(context: Context, message: ChatMessage): JSONObject {
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
        return JSONObject().put("role", role).put("content", content)
    }

    private suspend fun streamRequest(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<JSONObject>,
        tools: JSONArray?,
        onDelta: (String) -> Unit
    ): StreamOutcome {
        val payload = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().apply { messages.forEach { put(it) } })
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
        if(structuredOutput){val schema=JSONObject().put("type","object").put("properties",JSONObject().put("answer",JSONObject().put("type","string"))).put("required",JSONArray().put("answer")).put("additionalProperties",false);payload.put("response_format",StructuredOutput.responseFormat(StructuredOutputRequest(schema)))}\n        tools?.let{payload.put("tools",it).put("tool_choice","auto")}

        val request = Request.Builder()
            .url(baseUrl + "/chat/completions")
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "text/event-stream, application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = beginCall(chatClient.newCall(request))
        return try {
            call.execute().use { response ->
                val requestId = response.header("X-Request-ID") ?: response.header("x-request-id")
                val routedVia = response.header("X-Routed-Via")
                val fallback = response.header("X-Fallback-Attempts")?.toIntOrNull() ?: 0

                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val err = ApiErrorMapper.fromHttp(response.code, parseServerError(body))
                    throw IOException(err.message)
                }

                val source = response.body?.source() ?: error("The server returned an empty response.")
                val contentType = response.header("Content-Type").orEmpty()
                val started = System.nanoTime()

                if (!contentType.contains("text/event-stream", true)) {
                    val body = source.readUtf8()
                    val text = parseChatText(body)
                    onDeltaOnMain(onDelta, text)
                    return StreamOutcome(
                        text,
                        parseToolCallsFromJson(body),
                        ResponseMetadata(
                            model = model,
                            routedVia = routedVia,
                            fallbackAttempts = fallback,
                            requestId = requestId,
                            latencyMs = (System.nanoTime() - started) / 1_000_000L,
                            tokenUsage = parseUsage(body)
                        )
                    )
                }

                val textBuilder = StringBuilder()
                val uiBuffer = StringBuilder()
                val toolBuilders = linkedMapOf<Int, MutableTool>()
                var tokenUsage: String? = null
                var lastEmit = 0L
                suspend fun flush(force: Boolean = false) {
                    if (uiBuffer.isEmpty()) return
                    val now = System.nanoTime() / 1_000_000L
                    if (!force && now - lastEmit < 55L) return
                    val delta = uiBuffer.toString()
                    uiBuffer.setLength(0)
                    lastEmit = now
                    onDeltaOnMain(onDelta, delta)
                }

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    when (val event = SseParser.parseDataLine(line)) {
                        is SseParser.Event.Delta -> {
                            textBuilder.append(event.text)
                            uiBuffer.append(event.text)
                            flush()
                        }
                        is SseParser.Event.ToolDelta -> {
                            val item = toolBuilders.getOrPut(event.index) { MutableTool() }
                            if (!event.id.isNullOrBlank()) item.id = event.id
                            if (!event.name.isNullOrBlank()) item.name = event.name
                            item.arguments.append(event.arguments)
                        }
                        SseParser.Event.Done -> break
                        is SseParser.Event.Malformed, null -> Unit
                    }
                    rawUsage(line)?.let { tokenUsage = it }
                }
                flush(force = true)

                val calls = toolBuilders.entries.mapNotNull { entry ->
                    val item = entry.value
                    val name = item.name ?: return@mapNotNull null
                    ToolCall(item.id ?: "call_" + entry.key, name, item.arguments.toString())
                }.take(MAX_TOOL_CALLS)

                StreamOutcome(
                    textBuilder.toString(),
                    calls,
                    ResponseMetadata(
                        model = model,
                        routedVia = routedVia,
                        fallbackAttempts = fallback,
                        requestId = requestId,
                        latencyMs = (System.nanoTime() - started) / 1_000_000L,
                        tokenUsage = tokenUsage
                    )
                )
            }
        } finally {
            endCall(call)
        }
    }

    private class MutableTool {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun normalizeBaseUrl(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(!value.contains("\n") && !value.contains("\r") && !value.contains(" ")) {
            "Base URL contains invalid whitespace."
        }
        val parsed = value.toHttpUrlOrNull() ?: error("Base URL is not a valid HTTPS URL.")
        require(parsed.isHttps) { "Base URL must use HTTPS." }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Credentials in the Base URL are not allowed."
        }
        require(parsed.query == null && parsed.fragment == null) {
            "Base URL cannot contain a query or fragment."
        }
        return parsed.toString()
            .trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/models")
    }

    private suspend fun executeGetWithRetry(request: Request): okhttp3.Response {
        var attempt = 0
        while (true) {
            try {
                val response = shortClient.newCall(request).execute()
                val retryable = response.code == 429 || response.code in 502..504
                if (!retryable || attempt >= 2) return response
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0L, 5L)
                response.close()
                delay((retryAfter?.times(1000L)) ?: (250L shl attempt))
                attempt++
            } catch (e: IOException) {
                if (attempt >= 2) throw e
                delay(250L shl attempt)
                attempt++
            }
        }
    }

    private fun beginCall(call: okhttp3.Call): okhttp3.Call {
        cancelRequested.set(false)
        activeCall.set(call)
        return call
    }

    private fun endCall(call: okhttp3.Call) {
        activeCall.compareAndSet(call, null)
    }

    private fun parseModels(body: String): List<ModelInfo> {
        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
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
                        coding = item.optBoolean("coding", item.optBoolean("supports_coding", false)),
                        speed = item.optString("speed").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun parseChatText(body: String): String =
        runCatching {
            JSONObject(body).optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        }.getOrDefault("")

    private fun parseToolCallsFromJson(body: String): List<ToolCall> =
        runCatching {
            val message = JSONObject(body).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message") ?: return@runCatching emptyList()
            val calls = message.optJSONArray("tool_calls") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until calls.length()) {
                    val item = calls.optJSONObject(i) ?: continue
                    val fn = item.optJSONObject("function") ?: continue
                    val name = fn.optString("name").takeIf { it.isNotBlank() } ?: continue
                    add(
                        ToolCall(
                            item.optString("id").takeIf { it.isNotBlank() } ?: "call_" + i,
                            name,
                            fn.optString("arguments").take(MAX_TOOL_ARGUMENT_CHARS)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

    private fun parseServerError(body: String): String? =
        runCatching {
            JSONObject(body).optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

    private fun parseUsage(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("usage")?.toString() }.getOrNull()

    private fun rawUsage(line: String): String? =
        runCatching {
            if (!line.startsWith("data:")) return@runCatching null
            JSONObject(line.removePrefix("data:").trim())
                .optJSONObject("usage")?.toString()
        }.getOrNull()

    private fun buildContentParts(context: Context, message: ChatMessage): JSONArray {
        val parts = JSONArray()
        if (message.text.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", message.text.take(120_000)))
        }

        message.attachments.forEach { attachment ->
            val file = File(attachment.localPath)
            if (!file.exists()) return@forEach
            when {
                attachment.mimeType.startsWith("image/") -> {
                    require(file.length() <= MAX_ATTACHMENT_IMAGE_BYTES) { "Image attachment is too large." }
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
                        page.delete()
                    }
                }
                else -> {
                    val extracted = DocumentProcessor.extractText(context, file, attachment.mimeType, attachment.name)
                    val payloadText = if (!extracted.isNullOrBlank()) {
                        "[Attached file: " + attachment.name + "]\n" + extracted.take(120_000)
                    } else {
                        "[Attached file: " + attachment.name + ". Content could not be extracted on-device.]"
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
        val call = beginCall(mediaClient.newCall(request))
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) error("Generated media download failed (" + response.code + ").")
                require(response.request.url.isHttps) { "Generated media redirect was not HTTPS." }
                val body = response.body ?: error("Generated media response is empty.")
                val length = body.contentLength()
                require(length < 0L || length <= maxBytes) { "Generated media exceeds the safe size limit." }
                body.byteStream().use { input ->
                    FileOutputStream(target).use { output -> copyBounded(input, output, maxBytes) }
                }
            }
        } finally {
            endCall(call)
        }
    }

    private fun validateImageFile(file: File) {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Generated image is not a valid decodable image." }
        require(options.outWidth <= 16_384 && options.outHeight <= 16_384) { "Generated image dimensions are unsafe." }
    }
}
