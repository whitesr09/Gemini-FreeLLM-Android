package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProviderClientTest {
    private lateinit var server: MockWebServer
    private val client = FreeLlmApiClient()
    private val prompt = listOf(ChatMessage(text = "Hello", role = ChatMessage.Role.USER))
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }
    private fun profile(provider: Provider = Provider.CUSTOM, stream: Boolean = true) = ProviderProfile(
        provider, server.url("/v1").toString(), "synthetic-test-key", "test-model", stream)
    private fun event(data: String) = "data: $data\n\n"

    @Test fun openAiPayloadIncludesSelectedModelAndFiltersErrors() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"choices":[{"message":{"content":"Hello back"}}]}"""))
        assertEquals("Hello back", client.generate(profile(stream = false), prompt + ChatMessage(text = "failure", role = ChatMessage.Role.ERROR)) {})
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer synthetic-test-key", request.getHeader("Authorization"))
        val json = JSONObject(request.body.readUtf8())
        assertEquals("test-model", json.getString("model"))
        assertFalse(json.getBoolean("stream"))
        assertEquals(1, json.getJSONArray("messages").length())
    }

    @Test fun geminiUsesNativePayloadAndHeader() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"candidates":[{"content":{"parts":[{"text":"A"},{"text":"B"}]}}]}"""))
        assertEquals("AB", client.generate(profile(Provider.GEMINI, false), prompt) {})
        val request = server.takeRequest()!!
        assertEquals("/v1/models/test-model:generateContent", request.path)
        assertEquals("synthetic-test-key", request.getHeader("x-goog-api-key"))
        assertNull(request.getHeader("Authorization"))
        assertEquals("user", JSONObject(request.body.readUtf8()).getJSONArray("contents").getJSONObject(0).getString("role"))
    }

    @Test fun anthropicUsesNativePayloadAndHeader() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"content":[{"type":"text","text":"Ready"}]}"""))
        assertEquals("Ready", client.generate(profile(Provider.ANTHROPIC, false), prompt) {})
        val request = server.takeRequest()!!
        assertEquals("/v1/messages", request.path)
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertEquals("synthetic-test-key", request.getHeader("x-api-key"))
        assertEquals(4096, JSONObject(request.body.readUtf8()).getInt("max_tokens"))
    }

    @Test fun openAiStreamsAccumulateAndFinish() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            ": heartbeat\n\n" + event("""{"choices":[{"delta":{"content":"Hello"}}]}""") +
                event("""{"choices":[{"delta":{"content":" world"},"finish_reason":"stop"}]}""") + event("[DONE]")))
        val updates = mutableListOf<String>()
        assertEquals("Hello world", client.generate(profile(), prompt) { updates += it })
        assertEquals("Hello world", updates.last())
    }

    @Test fun geminiStreamsAccumulateAndFinish() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            event("""{"candidates":[{"content":{"parts":[{"text":"Hi"}]}}]}""") +
                event("""{"candidates":[{"content":{"parts":[{"text":"!"}]},"finishReason":"STOP"}]}""")))
        assertEquals("Hi!", client.generate(profile(Provider.GEMINI), prompt) {})
        assertEquals("/v1/models/test-model:streamGenerateContent?alt=sse", server.takeRequest().path)
    }

    @Test fun anthropicStreamsAccumulateAndFinish() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "event: content_block_delta\n" + event("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}""") +
                event("""{"type":"message_stop"}""")))
        assertEquals("Hi", client.generate(profile(Provider.ANTHROPIC), prompt) {})
    }

    @Test fun truncatedStreamIsNotReportedAsSuccessful() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event("""{"choices":[{"delta":{"content":"Partial"}}]}""")))
        val error = runCatching { client.generate(profile(), prompt) {} }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertTrue(error!!.message!!.contains("before the reply finished"))
    }

    @Test fun providerErrorBodiesNeverExposeSecrets() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic-secret-response"))
        val error = runCatching { client.generate(profile(), prompt) {} }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("key was rejected"))
        assertFalse(error.message!!.contains("synthetic"))
    }

    @Test fun malformedResponseIsSanitized() = runBlocking {
        server.enqueue(MockResponse().setBody("unexpected synthetic-secret-response"))
        val error = runCatching { client.generate(profile(), prompt) {} }.exceptionOrNull()!!
        assertTrue(error is ApiException)
        assertFalse(error.message!!.contains("synthetic"))
    }

    @Test fun redirectsAreNotFollowedWithCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/steal"))
        val error = runCatching { client.generate(profile(), prompt) {} }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("redirected"))
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationDoesNotWaitForNetworkTimeout() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch(Dispatchers.Default) { client.generate(profile(), prompt) {} }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        withTimeout(1500) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test fun modelDiscoveryUsesCatalogAndDeduplicates() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"z"},{"id":"a"},{"id":"a"}]}"""))
        assertEquals(listOf("a", "z"), client.models(profile()))
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test fun geminiDiscoveryExcludesUnsupportedModels() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/chat","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}"""))
        assertEquals(listOf("chat"), client.models(profile(Provider.GEMINI)))
    }

    @Test fun keylessCustomEndpointsAreAllowedButProviderKeysAreRequired() {
        assertNull(FreeLlmApiClient.validate(profile().copy(apiKey = "")))
        assertNotNull(FreeLlmApiClient.validate(profile(Provider.GEMINI).copy(apiKey = "")))
        assertNotNull(FreeLlmApiClient.validate(profile().copy(baseUrl = "https://user:pass@host/v1")))
        assertNotNull(FreeLlmApiClient.validate(profile().copy(baseUrl = "https://host/v1?key=secret")))
        assertNotNull(FreeLlmApiClient.validate(profile().copy(model = "../escape")))
    }

    @Test fun documentsAreIncludedInConversationContext() {
        val attachment = Attachment(name = "notes.txt", mimeType = "text/plain", size = 5, text = "notes")
        val request = client.buildRequest(profile(), listOf(prompt.first().copy(attachments = listOf(attachment))))
        val buffer = okio.Buffer()
        request.body!!.writeTo(buffer)
        assertTrue(JSONObject(buffer.readUtf8()).getJSONArray("messages").getJSONObject(0).getString("content").contains("notes.txt ---\nnotes"))
    }

    @Test fun imageHistoryCannotSilentlyLoseVisionOnProviderSwitch() {
        val attachment = Attachment(name = "photo.jpg", mimeType = "image/jpeg", size = 5)
        assertThrows(ApiException::class.java) { client.buildRequest(profile(), listOf(prompt.first().copy(attachments = listOf(attachment)))) }
    }
}
