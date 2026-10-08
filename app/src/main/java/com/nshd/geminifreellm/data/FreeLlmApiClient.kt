package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String, val access: AccessState = AccessState.ERROR, val statusCode: Int? = null) : IOException(message)

/** All provider-specific wire formats live here; UI and history remain provider independent. */
class FreeLlmApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
) {
    suspend fun generate(profile: ProviderProfile, messages: List<ChatMessage>, systemPrompt: String = "", onText: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            validate(profile)?.let { throw ApiException(it) }
            val request = buildRequest(profile, messages, systemPrompt)
            // The continuation stays active for the entire response body, including an SSE stream.
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(ApiException(networkError(e)))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val result = response.use {
                                if (!it.isSuccessful) throw responseError(it)
                                val body = it.body ?: throw ApiException("The provider returned an empty response.")
                                if (it.header("Content-Type").orEmpty().contains("text/event-stream")) {
                                    readEvents(body, profile.provider.protocol) { text ->
                                        if (continuation.isActive) onText(text)
                                    }
                                } else {
                                    parseResponse(profile.provider.protocol, JSONObject(readBounded(body)))
                                        .also { text -> if (continuation.isActive) onText(text) }
                                }
                            }
                            if (result.isBlank()) throw ApiException("The model returned no text. Choose a text model or try another prompt.")
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                if (e is ApiException) e else ApiException("The provider returned an unreadable or interrupted response. Try again.")
                            )
                        }
                    }
                })
            }
        }

    suspend fun models(profile: ProviderProfile): List<String> = catalog(profile).map { it.id }

    suspend fun catalog(profile: ProviderProfile): List<ModelInfo> = withContext(Dispatchers.IO) {
        validate(profile, requireModel = false)?.let { throw ApiException(it) }
        val result = linkedMapOf<String, ModelInfo>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        repeat(20) {
            var url = endpoint(profile, "models")
            cursor?.let { token -> url = url.newBuilder().addQueryParameter(
                if (profile.provider.protocol == ApiProtocol.GEMINI) "pageToken" else "after_id", token).build() }
            val json = fetchJson(authorized(profile, url).get().build())
            val list = json.optJSONArray(if (profile.provider.protocol == ApiProtocol.GEMINI) "models" else "data")
                ?: throw ApiException("Model discovery is unavailable. Enter the model ID manually.")
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                if (profile.provider.protocol == ApiProtocol.GEMINI) {
                    val methods = item.optJSONArray("supportedGenerationMethods") ?: continue
                    if ((0 until methods.length()).none { methods.optString(it) == "generateContent" }) continue
                }
                val id = (if (profile.provider.protocol == ApiProtocol.GEMINI) item.optString("name").removePrefix("models/") else item.optString("id"))
                if (id.isBlank() || id.length > 200) continue
                val pricing = item.optJSONObject("pricing")
                val free = pricing?.let { prices ->
                    val prompt = prices.optString("prompt").toDoubleOrNull()
                    val completion = prices.optString("completion").toDoubleOrNull()
                    if (prompt != null && completion != null) prompt == 0.0 && completion == 0.0 else null
                }
                result[id] = ModelInfo(id, item.optString("displayName", item.optString("name", id)).take(200),
                    item.optLong("context_length", item.optLong("inputTokenLimit")).takeIf { it > 0 }, free)
                if (result.size > 10_000) throw ApiException("This catalog is too large. Enter the model ID manually.")
            }
            cursor = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                ?: if (json.optBoolean("has_more")) json.optString("last_id").takeIf { it.isNotBlank() } else null
            if (cursor == null) return@withContext result.values.sortedBy { it.id }
            if (!seen.add(cursor!!)) throw ApiException("The provider repeated a catalog page. Enter the model ID manually.")
        }
        throw ApiException("The catalog exceeds 20 pages. Enter the model ID manually.")
    }

    internal suspend fun fetchJson(request: Request, maxBytes: Int = MAX_RESPONSE): JSONObject =
        execute(request) { JSONObject(readBounded(it.body ?: throw ApiException("Empty response."), maxBytes)) }

    internal suspend fun download(request: Request, destination: File, maxBytes: Long) {
        try {
            execute(request) { response ->
                val body = response.body ?: throw ApiException("The download was empty.")
                if (body.contentLength() > maxBytes) throw ApiException("The download exceeds the size limit.")
                body.byteStream().use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw ApiException("The download exceeds the size limit.")
                        output.write(buffer, 0, count)
                    }
                    if (total == 0L) throw ApiException("The download was empty.")
                } }
            }
        } catch (error: Exception) { destination.delete(); throw error }
    }

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(ApiException(networkError(e)))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (!it.isSuccessful) throw responseError(it)
                            read(it)
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is ApiException) error else ApiException("The provider returned an unreadable response. Check the API endpoint."))
                    }
                }
            })
        }
    }

    internal fun buildRequest(profile: ProviderProfile, messages: List<ChatMessage>, systemPrompt: String = ""): Request {
        val protocol = profile.provider.protocol
        val history = continuationHistory(messages)
        val textSize = history.sumOf { message -> message.text.length.toLong() + message.attachments.sumOf { it.text.length.toLong() } }
        val imageSize = history.sumOf { message -> message.attachments.filter { it.isImage }.sumOf { File(it.localPath).length() } }
        if (textSize > 1_000_000 || imageSize > 12 * 1024 * 1024) throw ApiException("This conversation is too large to send. Start a new chat with a shorter excerpt.")
        if (!profile.vision && history.any { message -> message.attachments.any { it.isImage } }) {
            throw ApiException("This chat includes images. Enable image input for a vision-capable model in settings.")
        }
        require(systemPrompt.length <= 48_000) { "Agent instructions exceed 48,000 characters." }
        val payload = JSONObject()
        val path = when (protocol) {
            ApiProtocol.OPENAI -> {
                payload.put("model", profile.model).put("stream", profile.stream)
                payload.put("messages", JSONArray().apply {
                    if (systemPrompt.isNotBlank()) put(JSONObject().put("role", "system").put("content", systemPrompt))
                    history.forEach { message -> put(JSONObject().put("role", role(message)).put("content", openAiContent(message))) }
                })
                "chat/completions"
            }
            ApiProtocol.ANTHROPIC -> {
                if (systemPrompt.isNotBlank()) payload.put("system", systemPrompt)
                payload.put("model", profile.model).put("max_tokens", 4096).put("stream", profile.stream)
                payload.put("messages", JSONArray(history.map { message ->
                    JSONObject().put("role", role(message)).put("content", JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", messageText(message)))
                        message.attachments.filter { it.isImage }.forEach { image ->
                            put(JSONObject().put("type", "image").put("source", JSONObject()
                                .put("type", "base64").put("media_type", image.mimeType).put("data", encoded(image))))
                        }
                    })
                }))
                "messages"
            }
            ApiProtocol.GEMINI -> {
                if (systemPrompt.isNotBlank()) payload.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                if (profile.fastReplies && supportsFastReplies(profile.model)) {
                    payload.put("generationConfig", JSONObject().put("thinkingConfig", fastThinkingConfig(profile.model)))
                }
                payload.put("contents", JSONArray(history.map { message ->
                    JSONObject().put("role", if (message.role == ChatMessage.Role.USER) "user" else "model")
                        .put("parts", JSONArray().apply {
                            put(JSONObject().put("text", messageText(message)))
                            message.attachments.filter { it.isImage }.forEach { image ->
                                put(JSONObject().put("inlineData", JSONObject().put("mimeType", image.mimeType).put("data", encoded(image))))
                            }
                        })
                }))
                "models/${profile.model.removePrefix("models/")}:${if (profile.stream) "streamGenerateContent" else "generateContent"}"
            }
        }
        var url = endpoint(profile, path)
        if (protocol == ApiProtocol.GEMINI && profile.stream) url = url.newBuilder().addQueryParameter("alt", "sse").build()
        return authorized(profile, url).header("Accept", if (profile.stream) "text/event-stream, application/json" else "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
    }

    internal fun authorized(profile: ProviderProfile, url: HttpUrl): Request.Builder = Request.Builder().url(url).apply {
        if (profile.apiKey.isNotBlank()) when (profile.provider.protocol) {
            ApiProtocol.GEMINI -> header("x-goog-api-key", profile.apiKey.trim())
            ApiProtocol.ANTHROPIC -> header("x-api-key", profile.apiKey.trim())
            ApiProtocol.OPENAI -> header("Authorization", "Bearer ${profile.apiKey.trim()}")
        }
        if (profile.provider.protocol == ApiProtocol.ANTHROPIC) header("anthropic-version", "2023-06-01")
    }

    internal fun endpoint(profile: ProviderProfile, path: String): HttpUrl =
        (normalizeBaseUrl(profile.baseUrl, profile.provider.protocol) + "/" + path).toHttpUrlOrNull()
            ?: throw ApiException("Enter a valid API base URL in settings.")

    private fun openAiContent(message: ChatMessage): Any {
        val images = message.attachments.filter { it.isImage }
        if (images.isEmpty()) return messageText(message)
        return JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", messageText(message)))
            images.forEach { image -> put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${encoded(image)}"))) }
        }
    }

    private fun encoded(image: Attachment): String {
        val file = File(image.localPath)
        if (!file.isFile || file.length() > 4 * 1024 * 1024) throw ApiException("An image attachment is unavailable. Remove it and attach it again.")
        return Base64.getEncoder().encodeToString(file.readBytes())
    }

    private fun role(message: ChatMessage) = if (message.role == ChatMessage.Role.USER) "user" else "assistant"
    private fun messageText(message: ChatMessage) = buildString {
        append(message.text)
        message.attachments.filterNot { it.isImage }.forEach { append("\n\n--- ${it.name} ---\n${it.text}") }
    }.ifBlank { "Describe the attached image." }

    internal fun parseResponse(protocol: ApiProtocol, json: JSONObject): String = when (protocol) {
        ApiProtocol.OPENAI -> json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        ApiProtocol.ANTHROPIC -> textParts(json.optJSONArray("content"))
        ApiProtocol.GEMINI -> textParts(json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts"))
    }

    private fun textParts(parts: JSONArray?): String = buildString {
        if (parts != null) for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (!part.optBoolean("thought")) append(part.optString("text"))
        }
    }

    private fun readEvents(body: ResponseBody, protocol: ApiProtocol, onText: (String) -> Unit): String {
        val output = StringBuilder()
        val event = StringBuilder()
        var lastUpdate = 0L
        var wireBytes = 0L
        var complete = false
        fun dispatch() {
            if (event.isEmpty()) return
            val data = event.toString().trim()
            event.setLength(0)
            if (data == "[DONE]") { complete = true; return }
            val json = JSONObject(data)
            if (json.has("error") || json.optString("type") == "error") throw ApiException("The provider interrupted this reply. Try again.")
            val delta = when (protocol) {
                ApiProtocol.OPENAI -> json.optJSONArray("choices")?.optJSONObject(0)?.let {
                    if (!it.isNull("finish_reason") && it.optString("finish_reason").isNotBlank()) complete = true
                    it.optJSONObject("delta")?.optString("content").orEmpty()
                }.orEmpty()
                ApiProtocol.ANTHROPIC -> {
                    if (json.optString("type") == "message_stop") complete = true
                    json.optJSONObject("delta")?.optString("text").orEmpty()
                }
                ApiProtocol.GEMINI -> {
                    val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                    if (candidate?.has("finishReason") == true) complete = true
                    textParts(candidate?.optJSONObject("content")?.optJSONArray("parts"))
                }
            }
            output.append(delta)
            if (output.length > MAX_RESPONSE) throw ApiException("The reply exceeded the safe size limit. Ask for a shorter answer.")
            val now = System.nanoTime()
            if (delta.isNotEmpty() && now - lastUpdate >= 50_000_000) {
                onText(output.toString())
                lastUpdate = now
            }
        }
        val source = body.source()
        while (!complete && !source.exhausted()) {
            val line = source.readUtf8LineStrict(256 * 1024L)
            wireBytes += line.length
            if (wireBytes > 8 * MAX_RESPONSE) throw ApiException("The reply exceeded the safe size limit.")
            if (line.isEmpty()) dispatch()
            else if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.removePrefix("data:").trimStart())
            }
        }
        dispatch()
        if (!complete) throw ApiException("The connection ended before the reply finished. You can retry.")
        onText(output.toString())
        return output.toString()
    }

    private fun readBounded(body: ResponseBody, limit: Int = MAX_RESPONSE): String {
        val source = body.source()
        source.request(limit.toLong() + 1)
        if (source.buffer.size > limit) throw ApiException("The response exceeded the safe size limit.")
        return source.readUtf8()
    }

    companion object {
        private const val MAX_RESPONSE = 1_000_000
        fun supportsFastReplies(model: String) = fastThinkingConfig(model) != null
        internal fun fastThinkingConfig(model: String): JSONObject? {
            val id = model.removePrefix("models/")
            return when {
                id in setOf("gemini-2.5-flash", "gemini-2.5-flash-lite") -> JSONObject().put("thinkingBudget", 0)
                id.matches(Regex("gemini-3(?:\\.\\d+)?-(?:flash|pro)(?:-preview)?")) -> JSONObject().put("thinkingLevel", "LOW")
                else -> null
            }
        }

        fun normalizeBaseUrl(value: String, protocol: ApiProtocol): String {
            val base = value.trim().trimEnd('/')
            val suffixes = when (protocol) {
                ApiProtocol.OPENAI -> listOf("/chat/completions", "/models", "/images/generations", "/videos")
                ApiProtocol.ANTHROPIC -> listOf("/messages", "/models")
                ApiProtocol.GEMINI -> listOf("/models")
            }
            return suffixes.firstOrNull { base.endsWith(it) }?.let { base.removeSuffix(it) } ?: base
        }

        internal fun continuationHistory(messages: List<ChatMessage>): List<ChatMessage> {
            val result = mutableListOf<ChatMessage>()
            messages.filter { it.role != ChatMessage.Role.ERROR && (it.text.isNotBlank() || it.attachments.isNotEmpty()) &&
                !(it.interrupted && it.text == "Reply stopped.") }.forEach { message ->
                val last = result.lastOrNull()
                if (last?.role == message.role) result[result.lastIndex] = last.copy(
                    text = last.text + "\n\n" + message.text, attachments = last.attachments + message.attachments)
                else result += message
            }
            return result
        }

        internal fun responseError(response: Response): ApiException {
            // Inspect only allowlisted error codes. Never expose provider text, URLs, prompts or keys.
            val code = runCatching {
                val source = response.body?.source()
                source?.request(16_385)
                val body = source?.buffer?.clone()?.readUtf8(minOf(source.buffer.size, 16_384L)).orEmpty()
                val error = JSONObject(body).optJSONObject("error")
                error?.optString("code", error.optString("type")).orEmpty()
            }.getOrDefault("")
            val quota = response.code == 402 || code in setOf("insufficient_quota", "insufficient_balance", "billing_hard_limit_reached", "credit_balance_too_low")
            val state = when {
                quota -> AccessState.QUOTA
                response.code == 401 || response.code == 403 -> AccessState.AUTH
                response.code == 429 -> AccessState.RATE_LIMITED
                response.code == 404 -> AccessState.UNAVAILABLE
                else -> AccessState.ERROR
            }
            return ApiException(if (quota) "Provider quota or balance exhausted. Check billing or free-tier quota, or switch providers. Free-tier expiry is not reported by this API."
                else httpError(response.code), state, response.code)
        }
        fun validate(profile: ProviderProfile, requireModel: Boolean = true): String? {
            val url = profile.baseUrl.trim().toHttpUrlOrNull() ?: return "Enter a valid HTTP or HTTPS API base URL."
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return "Use a base URL without credentials, query parameters, or a fragment."
            if (requireModel && (profile.model.isBlank() || !profile.model.matches(Regex("[A-Za-z0-9._:/@+\\-]+")) || profile.model.contains(".."))) return "Enter a valid model ID from your provider."
            if (profile.apiKey.any { it.code < 32 || it.code > 126 }) return "The API key contains invalid characters."
            if (profile.apiKey.isBlank() && profile.provider !in listOf(Provider.CUSTOM, Provider.FREELLM, Provider.OLLAMA)) return "Enter the API key for ${profile.provider.label}."
            return null
        }
        fun httpError(code: Int): String = when (code) {
            400, 422 -> "The provider rejected this request. Check the model ID and image-input support."
            402 -> "Provider balance is insufficient. Check billing or use another provider."
            401, 403 -> "The API key was rejected or lacks access. Check this provider's key and permissions."
            404 -> "Model or endpoint not found. Check the API base URL and model ID."
            413 -> "The request is too large. Remove attachments or start a shorter chat."
            429 -> "Rate or quota limit reached. Wait a moment or check your provider's plan."
            in 500..599 -> "The provider is temporarily unavailable ($code). Try again."
            in 300..399 -> "The endpoint redirected this request. Enter the final API URL in settings."
            else -> "Request failed ($code). Check your provider settings."
        }
        private fun networkError(error: IOException): String = if (error is java.net.SocketTimeoutException)
            "The provider took too long to respond. Try again."
        else "Could not reach the provider. Check your connection and API base URL."
    }
}
