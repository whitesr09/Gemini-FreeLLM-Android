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

/** Regression coverage for the DSHA local OpenAI-compatible forwarder. */
class DshaProviderTest {
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
        provider = Provider.DSHA,
        baseUrl = server.url("/v1").toString(),
        apiKey = "synthetic-dsha-key",
        model = "opencode/deepseek-v4.1-flash",
        stream = true
    )

    @Test fun dshaProviderUsesBearerKeyAndDiscoversLiveModelIds() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"opencode/deepseek-v4.1-flash","owned_by":"dsha"},{"id":"kilo/kimi-k3"}]}"""))

        val models = client.models(profile())

        assertEquals(listOf("kilo/kimi-k3", "opencode/deepseek-v4.1-flash"), models)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer synthetic-dsha-key", request.getHeader("Authorization"))
    }

    @Test fun dshaProviderStreamsOpenAiCompatibleServerSentEvents() = runBlocking {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"from DSHA\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
            ))

        val snapshots = mutableListOf<String>()
        val answer = client.generate(profile(), listOf(ChatMessage(text = "Hi", role = ChatMessage.Role.USER))) {
            snapshots += it
        }

        assertEquals("Hello from DSHA", answer)
        assertTrue(snapshots.any { it.contains("Hello") })
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer synthetic-dsha-key", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("opencode/deepseek-v4.1-flash", payload.getString("model"))
        assertTrue(payload.getBoolean("stream"))
    }

    @Test fun dshaAuthenticationErrorsAreClassifiedWithoutLeakingResponseBody() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"private-key-value"}}"""))

        val error = runCatching {
            client.generate(profile(), listOf(ChatMessage(text = "Hi", role = ChatMessage.Role.USER)) ) {}
        }.exceptionOrNull() as ApiException

        assertEquals(AccessState.AUTH, error.access)
        assertEquals(401, error.statusCode)
        assertFalse(error.message.orEmpty().contains("private-key-value"))
    }

    @Test fun dshaPresetDefaultsToLoopbackAndOpenAiProtocol() {
        val profile = ProviderProfile(Provider.DSHA).copy(apiKey = "synthetic-test-key")
        assertEquals(ApiProtocol.OPENAI, profile.provider.protocol)
        assertEquals("http://127.0.0.1:18900/v1", profile.baseUrl)
        assertEquals(null, FreeLlmApiClient.validate(profile, requireModel = false))
    }
}
