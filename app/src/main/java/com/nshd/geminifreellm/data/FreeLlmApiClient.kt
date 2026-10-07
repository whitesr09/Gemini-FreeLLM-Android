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

class ApiException(message: String) : IOException(message)

/** All provider-specific wire formats live here; UI and history remain provider independent. */
class FreeLlmApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
) {
    suspend fun generate(profile: ProviderProfile, messages: List<ChatMessage>, onText: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            validate(profile)?.let { throw ApiException(it) }
            val request = buildRequest(profile, messages)
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
                                if (!it.isSuccessful) throw ApiException(httpError(it.code))
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

    suspend fun models(profile: ProviderProfile): List<String> = withContext(Dispatchers.IO) {
        validate(profile, requireModel = false)?.let { throw ApiException(it) }
        val request = authorized(profile, endpoint(profile, "models")).get().build()
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
                            if (!it.isSuccessful) throw ApiException(httpError(it.code))
                            val json = JSONObject(readBounded(it.body ?: throw ApiException("Empty model catalog.")))
                            val list = json.optJSONArray(if (profile.provider.protocol == ApiProtocol.GEMINI) "models" else "data")
                                ?: throw ApiException("Model discovery is unavailable. Enter the model ID manually.")
                            (0 until list.length()).mapNotNull { index ->
                                val item = list.optJSONObject(index) ?: return@mapNotNull null
                                if (profile.provider.protocol == ApiProtocol.GEMINI) {
                                    val methods = item.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                                    if (!methods.contains("generateContent")) return@mapNotNull null
                                    item.optString("name").removePrefix("models/")
                                } else item.optString("id")
                            }.filter { it.isNotBlank() }.distinct().sorted()
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is ApiException) e else ApiException("Could not read the model catalog. Enter the model ID manually.")
                        )
                    }
                }
            })
        }
    }

    internal fun buildRequest(profile: ProviderProfile, messages: List<ChatMessage>): Request {
        val protocol = profile.provider.protocol
        val history = messages.filter { it.role != ChatMessage.Role.ERROR && (it.text.isNotBlank() || it.attachments.isNotEmpty()) }
        val textSize = history.sumOf { message -> message.text.length.toLong() + message.attachments.sumOf { it.text.length.toLong() } }
        val imageSize = history.sumOf { message -> message.attachments.filter { it.isImage }.sumOf { File(it.localPath).length() } }
        if (textSize > 1_000_000 || imageSize > 12 * 1024 * 1024) throw ApiException("This conversation is too large to send. Start a new chat with a shorter excerpt.")
        if (!profile.vision && history.any { message -> message.attachments.any { it.isImage } }) {
            throw ApiException("This chat includes images. Enable image input for a vision-capable model in settings.")
        }
        val payload = JSONObject()
        val path = when (protocol) {
            ApiProtocol.OPENAI -> {
                payload.put("model", profile.model).put("stream", profile.stream)
                payload.put("messages", JSONArray(history.map { message ->
                    JSONObject().put("role", role(message)).put("content", openAiContent(message))
                }))
                "chat/completions"
            }
            ApiProtocol.ANTHROPIC -> {
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

    private fun authorized(profile: ProviderProfile, url: HttpUrl): Request.Builder = Request.Builder().url(url).apply {
        if (profile.apiKey.isNotBlank()) when (profile.provider.protocol) {
            ApiProtocol.GEMINI -> header("x-goog-api-key", profile.apiKey.trim())
            ApiProtocol.ANTHROPIC -> header("x-api-key", profile.apiKey.trim())
            ApiProtocol.OPENAI -> header("Authorization", "Bearer ${profile.apiKey.trim()}")
        }
        if (profile.provider.protocol == ApiProtocol.ANTHROPIC) header("anthropic-version", "2023-06-01")
    }

    private fun endpoint(profile: ProviderProfile, path: String): HttpUrl =
        (profile.baseUrl.trim().trimEnd('/') + "/" + path).toHttpUrlOrNull()
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
        if (parts != null) for (i in 0 until parts.length()) append(parts.optJSONObject(i)?.optString("text").orEmpty())
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
        while (!source.exhausted()) {
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

    private fun readBounded(body: ResponseBody): String {
        val source = body.source()
        source.request(MAX_RESPONSE.toLong() + 1)
        if (source.buffer.size > MAX_RESPONSE) throw ApiException("The response exceeded the safe size limit.")
        return source.readUtf8()
    }

    companion object {
        private const val MAX_RESPONSE = 1_000_000
        fun validate(profile: ProviderProfile, requireModel: Boolean = true): String? {
            val url = profile.baseUrl.trim().toHttpUrlOrNull() ?: return "Enter a valid HTTP or HTTPS API base URL."
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return "Use a base URL without credentials, query parameters, or a fragment."
            if (requireModel && (profile.model.isBlank() || !profile.model.matches(Regex("[A-Za-z0-9._:/@+\\-]+")) || profile.model.contains(".."))) return "Enter a valid model ID from your provider."
            if (profile.apiKey.any { it.code < 32 || it.code > 126 }) return "The API key contains invalid characters."
            if (profile.apiKey.isBlank() && profile.provider !in listOf(Provider.CUSTOM, Provider.FREELLM)) return "Enter the API key for ${profile.provider.label}."
            return null
        }
        fun httpError(code: Int): String = when (code) {
            400, 422 -> "The provider rejected this request. Check the model ID and image-input support."
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
