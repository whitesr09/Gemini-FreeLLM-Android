package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutoAgentTest {
    private val a = ProviderProfile(Provider.OPENAI, apiKey = "synthetic", model = "gpt-mini")
    private val b = ProviderProfile(Provider.GEMINI, apiKey = "synthetic", model = "gemini-flash")
    private val history = listOf(ChatMessage(text = "Help debug my code", role = ChatMessage.Role.USER))
    @Test fun oldSettingsDefaultToManualAndEmptyAgent() {
        val json = settingsToJson(AppSettings()).apply { remove("agent"); remove("autoRouting"); remove("autoExcluded") }
        val result = settingsFromJson(json)
        assertFalse(result.autoRouting); assertEquals("", result.agent.prompt())
    }
    @Test fun agentAndRoutingRoundTrip() {
        val settings = AppSettings(autoRouting = true, autoExcluded = listOf(Provider.OPENAI), agent = AgentConfig(persona = "Teacher", memory = "Kotlin", skills = listOf(AgentSkill(name = "Test", content = "Write tests"))))
        assertEquals(settings, settingsFromJson(settingsToJson(settings)))
        assertFalse(settings.agent.copy(enabled = false).prompt().contains("Teacher"))
        assertFalse(settings.agent.copy(skills = settings.agent.skills.map { it.copy(enabled = false) }).prompt().contains("Write tests"))
    }
    @Test fun sendsNativeSystemInstructionsForEveryProtocol() {
        val client = FreeLlmApiClient()
        listOf(Provider.OPENAI, Provider.GEMINI, Provider.ANTHROPIC).forEach { provider ->
            val buffer = Buffer()
            client.buildRequest(ProviderProfile(provider, apiKey = "synthetic"), history, "Remember my project").body!!.writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            val actual = when (provider.protocol) {
                ApiProtocol.OPENAI -> json.getJSONArray("messages").getJSONObject(0).also { assertEquals("system", it.getString("role")) }.getString("content")
                ApiProtocol.ANTHROPIC -> json.getString("system")
                ApiProtocol.GEMINI -> json.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
            }
            assertEquals("Remember my project", actual)
        }
    }
    @Test fun ranksOnlyEligibleModelsAndRespectsCooldown() {
        val settings = AppSettings(profiles = listOf(a, b, ProviderProfile(Provider.DEEPSEEK)), selected = Provider.OPENAI)
        val access = mapOf(modelKey(a.provider, a.model) to ModelAccess(AccessState.QUOTA, 100))
        assertEquals(listOf(b), AutoRouter.candidates(settings, emptyMap(), access, history, 101))
        assertEquals(2, AutoRouter.candidates(settings, emptyMap(), access, history, 1_000_000).size)
        assertEquals(listOf(a), AutoRouter.candidates(settings.copy(autoExcluded = listOf(Provider.GEMINI)), emptyMap(), emptyMap(), history))
    }
    @Test fun routingUsesCatalogAndFavoritesAndSkipsMedia() {
        val settings = AppSettings(profiles = listOf(a, b), favorites = listOf(modelKey(a.provider, "coder")))
        val catalog = mapOf(a.provider to ModelCatalog(listOf(ModelInfo("coder"), ModelInfo("tts-test"))))
        val result = AutoRouter.candidates(settings, catalog, emptyMap(), history)
        assertEquals("coder", result.first().model)
        assertEquals(b.provider, result[1].provider)
        assertFalse(result.any { it.model == "tts-test" })
    }
    @Test fun imagesRequireExplicitVisionConfiguration() {
        val imageHistory = listOf(history.first().copy(attachments = listOf(Attachment(name = "image", mimeType = "image/png", size = 2))))
        val result = AutoRouter.candidates(AppSettings(profiles = listOf(a, b.copy(vision = true))), emptyMap(), emptyMap(), imageHistory)
        assertEquals(listOf(b.copy(vision = true)), result)
    }
    @Test fun fallsBackAndPreservesRequestHistory() = runTest {
        val calls = mutableListOf<ProviderProfile>(); val errors = mutableListOf<ProviderProfile>()
        val result = AutoRouter.generate(listOf(a, b), onAttempt = { _, _ -> }, onFailure = { p, _ -> errors += p }, request = { p, emit ->
            calls += p
            if (p == a) throw ApiException("Unavailable", AccessState.UNAVAILABLE)
            emit("Hello"); "Hello"
        }, onText = {})
        assertEquals(listOf(a, b), calls); assertEquals(listOf(a), errors); assertEquals(b to "Hello", result)
    }
    @Test fun quotaSkipsOtherModelsOnSameProvider() = runTest {
        val calls = mutableListOf<ProviderProfile>()
        AutoRouter.generate(listOf(a, a.copy(model = "another"), b), onAttempt = { _, _ -> }, onFailure = { _, _ -> }, request = { p, _ ->
            calls += p; if (p.provider == a.provider) throw ApiException("Quota", AccessState.QUOTA); "OK"
        }, onText = {})
        assertEquals(listOf(a, b), calls)
    }
    @Test fun partialRepliesNeverSwitchProvider() = runTest {
        val calls = mutableListOf<ProviderProfile>(); val partials = mutableListOf<String>()
        try {
            AutoRouter.generate(listOf(a, b), onAttempt = { _, _ -> }, onFailure = { _, _ -> }, request = { p, emit ->
                calls += p; emit("Partial reply"); throw ApiException("Interrupted")
            }, onText = { partials += it })
            fail("Expected interrupted reply")
        } catch (_: ApiException) { }
        assertEquals(listOf(a), calls); assertEquals(listOf("Partial reply"), partials)
    }
    @Test fun firstTokenTimeoutFallsBackButLongStreamingReplyCanFinish() = runTest {
        val result = AutoRouter.generate(listOf(a, b), firstTokenTimeoutMs = 100, onAttempt = { _, _ -> }, onFailure = { _, _ -> }, request = { p, emit ->
            if (p == a) awaitCancellation()
            emit("Started"); delay(500); "Finished"
        }, onText = {})
        assertEquals(b to "Finished", result)
    }
    @Test fun cancellationDoesNotTriggerFallback() = runTest {
        var calls = 0
        val job = launch {
            AutoRouter.generate(listOf(a, b), onAttempt = { _, _ -> calls++ }, onFailure = { _, _ -> fail("Cancellation logged as error") }, request = { _, _ -> awaitCancellation() }, onText = {})
        }
        testScheduler.runCurrent(); job.cancelAndJoin(); assertEquals(1, calls)
    }
    @Test fun skillImportRejectsOversizedBinaryAndHtml() {
        assertEquals("# Skill\nReview code", readPortableText("# Skill\nReview code".byteInputStream()))
        listOf("a".repeat(16_001), "bad\u0000data", "<!DOCTYPE html><html>page</html>").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { readPortableText(value.byteInputStream()) }
        }
        assertNotNull(AgentConfig(skills = List(4) { AgentSkill(name = "Skill", content = "x".repeat(16_000)) }).validationError())
    }
}
