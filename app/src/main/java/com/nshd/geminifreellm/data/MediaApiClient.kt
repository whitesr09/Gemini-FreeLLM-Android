package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

enum class MediaKind { IMAGE, VIDEO }
data class VideoStatus(val id: String, val status: String, val progress: Int)

/** Explicit media adapters; chat-model discovery does not imply support for these routes. */
class MediaApiClient(private val api: FreeLlmApiClient) {
    suspend fun image(profile: ProviderProfile, model: String, prompt: String, destination: File): String = withContext(Dispatchers.IO) {
        validate(profile, model, prompt)
        val gemini = profile.provider.protocol == ApiProtocol.GEMINI
        val body = if (gemini) JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray(listOf("TEXT", "IMAGE"))))
        else JSONObject().put("model", model).put("prompt", prompt).put("n", 1)
        val url = api.endpoint(profile, if (gemini) "models/${model.removePrefix("models/")}:generateContent" else "images/generations")
        val json = api.fetchJson(api.authorized(profile, url).post(body.toString().toRequestBody("application/json".toMediaType())).build(), 24 * 1024 * 1024)
        var mime = "image/png"
        val encoded = if (gemini) {
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            var data: String? = null
            if (parts != null) for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                mime = inline.optString("mimeType", inline.optString("mime_type", "image/png"))
                data = inline.optString("data").takeIf { it.isNotBlank() }
                if (data != null) break
            }
            data
        } else json.optJSONArray("data")?.optJSONObject(0)?.optString("b64_json")?.takeIf { it.isNotBlank() }
        if (encoded != null) {
            if (encoded.length > 22 * 1024 * 1024) throw ApiException("Generated image exceeds the 16 MB limit.")
            val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw ApiException("The provider returned invalid image data.") }
            if (bytes.isEmpty() || bytes.size > 16 * 1024 * 1024) throw ApiException("Generated image is empty or exceeds the 16 MB limit.")
            if (mime !in listOf("image/png", "image/jpeg", "image/webp")) throw ApiException("The provider returned an unsupported image format.")
            destination.writeBytes(bytes)
        } else {
            val value = json.optJSONArray("data")?.optJSONObject(0)?.optString("url").orEmpty()
            val remote = value.toHttpUrlOrNull() ?: throw ApiException("The provider returned no image. Select an image-generation model with account access.")
            if (remote.scheme != "https" || remote.username.isNotBlank() || remote.password.isNotBlank()) throw ApiException("Image download requires a secure HTTPS URL.")
            // Signed CDN URLs are fetched without the provider's Authorization header or redirects.
            api.download(Request.Builder().url(remote).get().build(), destination, 16 * 1024 * 1024L)
        }
        mime
    }

    suspend fun createVideo(profile: ProviderProfile, model: String, prompt: String): VideoStatus {
        validate(profile, model, prompt)
        if (profile.provider.protocol != ApiProtocol.OPENAI) throw ApiException("Video generation requires a provider implementing the OpenAI Videos API.")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model", model)
            .addFormDataPart("prompt", prompt).addFormDataPart("seconds", "4").addFormDataPart("size", "720x1280").build()
        return videoStatus(api.fetchJson(api.authorized(profile, api.endpoint(profile, "videos")).post(body).build()))
    }
    suspend fun video(profile: ProviderProfile, id: String): VideoStatus {
        requireVideoId(id)
        return videoStatus(api.fetchJson(api.authorized(profile, api.endpoint(profile, "videos/$id")).get().build()))
    }
    suspend fun downloadVideo(profile: ProviderProfile, id: String, destination: File) {
        requireVideoId(id)
        api.download(api.authorized(profile, api.endpoint(profile, "videos/$id/content")).get().build(), destination, 100 * 1024 * 1024L)
    }
    private fun validate(profile: ProviderProfile, model: String, prompt: String) {
        FreeLlmApiClient.validate(profile.copy(model = model))?.let { throw ApiException(it) }
        if (prompt.isBlank() || prompt.length > 8000) throw ApiException("Enter a media prompt of 1–8,000 characters.")
        if (profile.provider.protocol == ApiProtocol.ANTHROPIC) throw ApiException("This provider does not support these media APIs.")
    }
    private fun requireVideoId(id: String) {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) throw ApiException("The provider returned an invalid video job ID.")
    }
    private fun videoStatus(json: JSONObject): VideoStatus {
        val id = json.optString("id")
        requireVideoId(id)
        val status = json.optString("status")
        if (status !in listOf("queued", "in_progress", "completed", "failed")) throw ApiException("The provider returned an unknown video status.")
        return VideoStatus(id, status, json.optInt("progress").coerceIn(0, 100))
    }
}
