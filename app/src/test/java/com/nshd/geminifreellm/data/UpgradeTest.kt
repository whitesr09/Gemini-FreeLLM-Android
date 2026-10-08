package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.io.File

class UpgradeTest {
    private lateinit var server: MockWebServer
    private val client = FreeLlmApiClient()
    @Before fun before() { server = MockWebServer(); server.start() }
    @After fun after() { server.shutdown() }
    private fun profile(provider: Provider = Provider.CUSTOM) = ProviderProfile(provider, server.url("/v1").toString(), "synthetic-test-key", "test-model")
    private val prompt = listOf(ChatMessage(text = "Hello", role = ChatMessage.Role.USER))
    private fun payload(profile: ProviderProfile, history: List<ChatMessage> = prompt): JSONObject {
        val buffer = okio.Buffer(); client.buildRequest(profile, history).body!!.writeTo(buffer); return JSONObject(buffer.readUtf8())
    }
    @Test fun catalogPaginatesGeminiWithoutLeakingKeyIntoUrl() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/a","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"next page"}"""))
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"models/b","supportedGenerationMethods":["generateContent"]}]}"""))
        assertEquals(listOf("a", "b"), client.models(profile(Provider.GEMINI)))
        assertEquals("/v1/models", server.takeRequest().path)
        val second = server.takeRequest(); assertEquals("next page", second.requestUrl!!.queryParameter("pageToken")); assertFalse(second.path!!.contains("synthetic"))
    }
    @Test fun catalogRetainsPricingWithoutInventingAccountAccess() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"free","context_length":10000,"pricing":{"prompt":"0","completion":"0"}},{"id":"unknown"}]}"""))
        val models = client.catalog(profile())
        assertEquals(true, models[0].freePricing); assertEquals(10000L, models[0].contextTokens); assertNull(models[1].freePricing)
    }
    @Test fun repeatedCatalogPageFailsInsteadOfLooping() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"models":[],"nextPageToken":"same"}""")) }
        assertTrue(runCatching { client.models(profile(Provider.GEMINI)) }.exceptionOrNull() is ApiException)
        assertEquals(2, server.requestCount)
    }
    @Test fun deepSeekRequestUsesBearerAuthAndConfiguredModel() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        client.generate(profile(Provider.DEEPSEEK).copy(model = "deepseek-flash"), prompt) {}
        val request = server.takeRequest()
        assertEquals("Bearer synthetic-test-key", request.getHeader("Authorization"))
        assertEquals("deepseek-flash", JSONObject(request.body.readUtf8()).getString("model"))
    }
    @Test fun balanceAndRateLimitsAreDistinguishedAndBodiesRedacted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(402).setBody("private account data"))
        val quota = runCatching { client.generate(profile(), prompt) {} }.exceptionOrNull() as ApiException
        assertEquals(AccessState.QUOTA, quota.access); assertFalse(quota.message!!.contains("private"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"rate_limit_exceeded"}}"""))
        assertEquals(AccessState.RATE_LIMITED, (runCatching { client.generate(profile(), prompt) {} }.exceptionOrNull() as ApiException).access)
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"insufficient_quota","message":"secret"}}"""))
        assertEquals(AccessState.QUOTA, (runCatching { client.generate(profile(), prompt) {} }.exceptionOrNull() as ApiException).access)
    }
    @Test fun pastedCompletionUrlIsNormalizedWithoutBreakingGatewayPrefix() {
        assertEquals("https://example.test/gateway/v1", FreeLlmApiClient.normalizeBaseUrl(" https://example.test/gateway/v1/chat/completions/ ", ApiProtocol.OPENAI))
        assertEquals("/v1/chat/completions", client.buildRequest(profile().copy(baseUrl = server.url("/v1/chat/completions").toString()), prompt).url.encodedPath)
    }
    @Test fun switchingProtocolsRetainsHistoryAndMergesAdjacentRoles() {
        val history = prompt + ChatMessage(text = "old answer", role = ChatMessage.Role.ASSISTANT, model = "other") +
            ChatMessage(text = "old error", role = ChatMessage.Role.ERROR) + ChatMessage(text = "Follow up", role = ChatMessage.Role.USER) + ChatMessage(text = "More detail", role = ChatMessage.Role.USER)
        val request = payload(profile(Provider.GEMINI), history).getJSONArray("contents")
        assertEquals(3, request.length()); assertEquals("model", request.getJSONObject(1).getString("role"))
        assertEquals("Follow up\n\nMore detail", request.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"))
    }
    @Test fun fastModeOnlyChangesSupportedFlashModels() {
        assertEquals(0, payload(profile(Provider.GEMINI).copy(model = "gemini-2.5-flash", fastReplies = true)).getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        assertFalse(payload(profile(Provider.GEMINI).copy(model = "gemini-other", fastReplies = true)).has("generationConfig"))
        assertEquals("LOW", payload(profile(Provider.GEMINI).copy(model = "gemini-3.5-flash", fastReplies = true)).getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
        assertFalse(payload(profile(Provider.GEMINI).copy(model = "gemini-3-flash-image", fastReplies = true)).has("generationConfig"))
    }
    @Test fun geminiThoughtPartsAreNotRenderedAsAnswer() {
        val json = JSONObject("""{"candidates":[{"content":{"parts":[{"text":"private reasoning","thought":true},{"text":"answer"}]}}]}""")
        assertEquals("answer", client.parseResponse(ApiProtocol.GEMINI, json))
    }
    @Test fun newPreferencesAndCanvasRoundTripAndOldRecordsDefaultSafely() {
        val settings = AppSettings(profiles = listOf(profile().copy(fastReplies = true)), reducedMotion = true, compactSpacing = true, showTimestamps = true, haptics = false, favorites = listOf("CUSTOM/x"))
        assertEquals(settings, settingsFromJson(settingsToJson(settings)))
        val chat = Conversation(canvas = CanvasDocument("test.kt", "kotlin", "fun test() = 1"))
        assertEquals(listOf(chat), conversationsFromJson(conversationsToJson(listOf(chat))))
        val legacy = settingsToJson(AppSettings()).apply { remove("haptics"); remove("favorites"); remove("reducedMotion") }
        assertTrue(settingsFromJson(legacy).haptics); assertFalse(settingsFromJson(legacy).reducedMotion)
    }
    @Test fun diagnosticsNeverIncludeExceptionMessageOrCausePayload() {
        val error = IllegalArgumentException("synthetic-secret https://host?key=private", RuntimeException("user chat"))
        val trace = DiagnosticLog.safeTrace(error)
        assertFalse(trace.contains("synthetic")); assertFalse(trace.contains("private")); assertFalse(trace.contains("user chat")); assertTrue(trace.contains("IllegalArgumentException"))
    }
    @Test fun imageApiDecodesPayloadWithoutSendingConversation() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"aW1hZ2U="}]}"""))
        val file = File.createTempFile("media", ".png")
        try {
            MediaApiClient(client).image(profile(), "image-model", "A blue flower", file)
            assertEquals("image", file.readText())
            val req = server.takeRequest(); assertEquals("/v1/images/generations", req.path)
            val body = JSONObject(req.body.readUtf8()); assertEquals("A blue flower", body.getString("prompt")); assertFalse(body.has("messages"))
        } finally { file.delete() }
    }
    @Test fun videoCreationUsesMultipartAndStatusPollingIsSeparate() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"video_test","status":"queued","progress":0}"""))
        server.enqueue(MockResponse().setBody("""{"id":"video_test","status":"completed","progress":100}"""))
        val media = MediaApiClient(client)
        assertEquals("queued", media.createVideo(profile(), "video-model", "Ocean waves").status)
        val req = server.takeRequest(); assertEquals("/v1/videos", req.path); assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data")); assertTrue(req.body.readUtf8().contains("Ocean waves"))
        assertEquals(100, media.video(profile(), "video_test").progress); assertEquals("/v1/videos/video_test", server.takeRequest().path)
    }
    @Test fun videoIdsCannotEscapeEndpoint() = runBlocking {
        assertTrue(runCatching { MediaApiClient(client).video(profile(), "../other") }.exceptionOrNull() is ApiException)
        assertEquals(0, server.requestCount)
    }
    @Test fun insecureImageUrlsAreRejectedBeforeDownload() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"url":"http://example.test/image.png"}]}"""))
        val file = File.createTempFile("media", ".png")
        try { assertTrue(runCatching { MediaApiClient(client).image(profile(), "image-model", "test", file) }.exceptionOrNull() is ApiException); assertEquals(1, server.requestCount) }
        finally { file.delete() }
    }
}
