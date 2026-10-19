package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Regression tests for OmniRoute's OpenAI-compatible gateway contract. */
class OmniRouteProviderTest {
    private lateinit var server: MockWebServer
    private val client = FreeLlmApiClient()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun profile() = ProviderProfile(
        provider = Provider.OMNIROUTE,
        baseUrl = server.url("/v1").toString(),
        apiKey = "synthetic-omniroute-endpoint-key",
        model = "if/kimi-k2-thinking",
        stream = true
    )

    @Test fun omniRouteDiscoversNamespacedModelIdsWithEndpointBearerKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"if/kimi-k2-thinking"},{"id":"gg/gemini-2.5-pro"}]}"""))

        assertEquals(listOf("gg/gemini-2.5-pro", "if/kimi-k2-thinking"), client.models(profile()))

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer synthetic-omniroute-endpoint-key", request.getHeader("Authorization"))
    }

    @Test fun omniRouteSupportsStreamingChatCompletionWithNamespacedModel() = runBlocking {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Omni\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"Route\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
            ))

        val answer = client.generate(profile(), listOf(ChatMessage(text = "Hello", role = ChatMessage.Role.USER))) {}
        assertEquals("OmniRoute", answer)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer synthetic-omniroute-endpoint-key", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("if/kimi-k2-thinking", payload.getString("model"))
        assertTrue(payload.getBoolean("stream"))
    }

    @Test fun omniRoutePresetHasLocalEndpointAndOpenAiProtocol() {
        val preset = ProviderProfile(Provider.OMNIROUTE).copy(apiKey = "synthetic-test-key")
        assertEquals(ApiProtocol.OPENAI, preset.provider.protocol)
        assertEquals("http://127.0.0.1:20128/v1", preset.baseUrl)
        assertEquals(null, FreeLlmApiClient.validate(preset, requireModel = false))
    }

    @Test fun omniRouteAuthFailureIsClassifiedAndRedacted() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"private-endpoint-secret"}}"""))
        val error = runCatching {
            client.generate(profile(), listOf(ChatMessage(text = "Hello", role = ChatMessage.Role.USER)) ) {}
        }.exceptionOrNull() as ApiException
        assertEquals(AccessState.AUTH, error.access)
        assertFalse(error.message.orEmpty().contains("private-endpoint-secret"))
    }
}
