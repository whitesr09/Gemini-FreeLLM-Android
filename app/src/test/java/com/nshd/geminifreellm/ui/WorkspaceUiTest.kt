package com.nshd.geminifreellm.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import com.nshd.geminifreellm.ChatViewModel
import com.nshd.geminifreellm.model.*
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nshd.geminifreellm.BuildConfig
import com.nshd.geminifreellm.MainActivity
import org.junit.*
import com.nshd.geminifreellm.FreeLlmApplication
import com.nshd.geminifreellm.MediaViewModel
import java.util.Base64
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    companion object {
        @BeforeClass @JvmStatic fun crypto() { Security.addProvider(TestKeyStore()) }
    }
    private fun ready() {
        lateinit var vm: ChatViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java] }
        compose.waitUntil(10_000) { compose.waitForIdle(); !vm.state.value.loading }
        compose.runOnIdle {
            Assert.assertFalse("Synthetic Keystore could not reopen test data", vm.state.value.storageBlocked)
            runBlocking { Assert.assertTrue(vm.saveSettings(AppSettings())) }
            vm.newChat(); vm.draft("")
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("What can I help with?").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun homeDrawerAndVersionRender() {
        ready()
        compose.onNodeWithContentDescription("Message").assertExists()
        compose.onNodeWithContentDescription("Open chat history").performClick()
        compose.onNodeWithText("Search your chats").assertExists()
        compose.onNodeWithText("FreeLLM AI · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
        capture("drawer")
    }
    @Test fun settingsCategoriesAreCompactAndExpandOnDemand() {
        ready()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNode(hasText("Settings") and hasAnyAncestor(isPopup())).performClick()

        compose.onNodeWithText("Connection").assertIsDisplayed()
        compose.onNodeWithText("Personalisation").assertIsDisplayed()
        compose.onNodeWithText("Skills").assertIsDisplayed()
        compose.onNodeWithText("API base URL").assertDoesNotExist()
        compose.onNodeWithText("Enable personalization").assertDoesNotExist()

        compose.onNodeWithText("Connection").performClick()
        compose.onNodeWithText("API base URL").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Personalisation").performClick()
        compose.onNodeWithText("Persona").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skills").assertIsDisplayed()
    }

    @Test fun modelPickerExpandsProviderAndAllowsManualId() {
        ready()
        compose.onNodeWithContentDescription("Choose AI model").performClick()
        compose.onNodeWithText("Choose your AI").assertIsDisplayed()
        compose.onNodeWithText("Manual selection").performClick()
        compose.onNodeWithText("Enter model ID manually").assertExists()
        compose.onNodeWithContentDescription("Close model picker").performClick()
        compose.onNodeWithContentDescription("Message").assertExists()
    }
    @Test fun autoFallbackKeepsContextAndPaddedReplyRenders() {
        val first = MockWebServer(); val second = MockWebServer(); first.start(); second.start()
        try {
            ready()
            lateinit var vm: ChatViewModel
            compose.runOnIdle {
                vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
                runBlocking { vm.saveSettings(AppSettings(profiles = listOf(
                    ProviderProfile(Provider.OPENAI, first.url("/v1").toString(), "synthetic", "primary", stream = false),
                    ProviderProfile(Provider.CUSTOM, second.url("/v1").toString(), "synthetic", "fallback", stream = false)),
                    selected = Provider.OPENAI, autoRouting = true, reducedMotion = true,
                    agent = AgentConfig(persona = "Careful teacher", memory = "My project uses Kotlin"))) }
                vm.draft("Help with my project")
            }
            first.enqueue(MockResponse().setBody("""{"data":[{"id":"primary"}]}"""))
            second.enqueue(MockResponse().setBody("""{"data":[{"id":"fallback"}]}"""))
            first.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            second.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Hello! This reply has room around every edge.\n\nThe last line stays fully visible."}}]}"""))
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.waitUntil(10_000) { compose.waitForIdle(); vm.state.value.generatingId == null && vm.state.value.active!!.messages.size == 2 }
            compose.onAllNodesWithText("The last line stays fully visible.").onFirst().assertIsDisplayed()
            capture("padded-auto-reply")
            Assert.assertEquals("GET", second.takeRequest().method)
            val request = org.json.JSONObject(second.takeRequest().body.readUtf8())
            Assert.assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("My project uses Kotlin"))
            Assert.assertEquals("Help with my project", request.getJSONArray("messages").getJSONObject(1).getString("content"))
            compose.runOnIdle { Assert.assertTrue(vm.state.value.active!!.messages.last().model.contains("fallback")); vm.draft("Continue") }
            second.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Continuing with your previous context."}}]}"""))
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.waitUntil(10_000) { compose.waitForIdle(); vm.state.value.generatingId == null && vm.state.value.active!!.messages.size == 4 }
            val next = org.json.JSONObject(second.takeRequest().body.readUtf8()).getJSONArray("messages")
            Assert.assertEquals(4, next.length())
            Assert.assertEquals("assistant", next.getJSONObject(2).getString("role"))
            Assert.assertEquals(2, first.requestCount)
        } finally { first.shutdown(); second.shutdown() }
    }
    @Test fun autoDiscoversAlternativeInSameProviderBeforeFirstSend() {
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setBody("""{"data":[{"id":"limited"},{"id":"available"}]}""")
                return if (org.json.JSONObject(request.body.readUtf8()).getString("model") == "limited") MockResponse().setResponseCode(429).setBody("{}")
                else MockResponse().setBody("""{"choices":[{"message":{"content":"The alternative model replied."}}]}""")
            }
        }
        server.start()
        try {
            ready()
            lateinit var vm: ChatViewModel
            compose.runOnIdle {
                vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
                runBlocking { vm.saveSettings(AppSettings(profiles = listOf(ProviderProfile(Provider.CUSTOM, server.url("/v1").toString(), "synthetic", "limited", stream = false)), selected = Provider.CUSTOM, autoRouting = true)) }
                vm.draft("Hello"); vm.send()
            }
            compose.waitUntil(15_000) { compose.waitForIdle(); vm.state.value.generatingId == null && vm.state.value.active!!.messages.last().text == "The alternative model replied." }
            compose.runOnIdle { Assert.assertTrue(vm.state.value.active!!.messages.last().model.contains("available")); Assert.assertEquals(3, server.requestCount) }
        } finally { server.shutdown() }
    }

    @Test fun autoToggleOffCancelsAvailabilityProbe() {
        val server = MockWebServer(); server.start()
        try {
            ready()
            lateinit var vm: ChatViewModel
            compose.runOnIdle {
                vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
                runBlocking { vm.saveSettings(AppSettings(profiles = listOf(ProviderProfile(Provider.CUSTOM, server.url("/v1").toString(), "synthetic", "test")), selected = Provider.CUSTOM)) }
            }
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"test"}]}"""))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            compose.onNodeWithContentDescription("Choose AI model").performClick()
            compose.onNodeWithContentDescription("Global Auto mode").performClick()
            compose.waitUntil(10_000) { compose.waitForIdle(); vm.state.value.checking.isNotEmpty() }
            compose.onNodeWithContentDescription("Global Auto mode").performClick()
            compose.waitUntil(10_000) { compose.waitForIdle(); !vm.state.value.autoChecking && !vm.state.value.settings.autoRouting }
            compose.runOnIdle { Assert.assertTrue(vm.state.value.access.isEmpty()); Assert.assertTrue(vm.state.value.checking.isEmpty()) }
        } finally { server.shutdown() }
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("/tmp/freellm-ui-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun modelSwitchKeepsDraftAndUsesCatalogSelection() {
        val server = MockWebServer(); server.start()
        try {
            ready()
            lateinit var vm: ChatViewModel
            compose.runOnIdle {
                vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
                runBlocking { Assert.assertTrue(vm.saveSettings(AppSettings(profiles = listOf(ProviderProfile(Provider.CUSTOM,
                    server.url("/v1").toString(), "synthetic-test", "model-a")), selected = Provider.CUSTOM))) }
                vm.draft("Keep this draft")
            }
            server.enqueue(MockResponse().setBody("""{"data":[{"id":"model-a"},{"id":"model-b"}]}"""))
            compose.waitUntil(10_000) { compose.onAllNodesWithText("model-a").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Choose AI model").performClick()
            compose.onNodeWithText("Manual selection").performClick()
            compose.waitUntil(10_000) { compose.waitForIdle(); vm.state.value.catalogs[Provider.CUSTOM]?.models?.any { it.id == "model-b" } == true }
            compose.onNodeWithContentDescription("Provider model list").performScrollToNode(hasText("model-b"))
            compose.onNodeWithText("model-b").performClick()
            compose.waitUntil(10_000) { vm.state.value.settings.active.model == "model-b" }
            compose.onNodeWithContentDescription("Message").assertTextContains("Keep this draft")
            compose.runOnIdle { Assert.assertEquals("model-b", vm.state.value.settings.active.model) }
        } finally { server.shutdown() }
    }
    @Test fun thinkingHasOneStatusAndStopPreservesChat() {
        val server = MockWebServer(); server.start()
        try {
            ready()
            lateinit var vm: ChatViewModel
            compose.runOnIdle {
                vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
                runBlocking { vm.saveSettings(AppSettings(profiles = listOf(ProviderProfile(Provider.CUSTOM,
                    server.url("/v1").toString(), "synthetic-test", "model-a")), selected = Provider.CUSTOM, reducedMotion = true)) }
                vm.draft("Hello")
            }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Thinking").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Generating…").assertDoesNotExist()
            capture("thinking")
            compose.onNodeWithContentDescription("Stop generating").performClick()
            compose.waitUntil(5_000) { vm.state.value.generatingId == null }
            compose.onAllNodesWithText("Reply stopped.").onFirst().assertIsDisplayed()
        } finally { server.shutdown() }
    }

    @Test fun diagnosticsPreparesRedactedReportWithoutSending() {
        ready()
        compose.runOnIdle { (compose.activity.application as FreeLlmApplication).diagnostics.record("Storage", error = IllegalStateException("synthetic-private-message")) }
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNode(hasText("Settings") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Error log & AI troubleshooting").performScrollTo().performClick()
        compose.onNodeWithText("Ask AI to explain").performClick()
        compose.onNodeWithText("Review in chat").performClick()
        compose.onNodeWithContentDescription("Message").assertTextContains("Explain these app diagnostics", substring = true)
        compose.runOnIdle {
            val vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
            Assert.assertFalse(vm.state.value.active!!.draft.contains("synthetic-private-message"))
            Assert.assertNull(vm.state.value.generatingId)
        }
    }
    @Test fun imageCreationNeedsConfirmationAndPersistsResult() {
        val server = MockWebServer(); server.start()
        try {
            ready()
            compose.runOnIdle {
                val vm = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
                runBlocking { vm.saveSettings(AppSettings(profiles = listOf(ProviderProfile(Provider.CUSTOM,
                    server.url("/v1").toString(), "synthetic-test", "model-a")), selected = Provider.CUSTOM)) }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("model-a").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Chat options").performClick()
            compose.onNodeWithText("Create image or video").performClick()
            compose.onNodeWithText("Image model ID").performTextInput("image-test")
            compose.onNodeWithText("Describe your creation").performTextInput("A blue dot")
            compose.onNodeWithText("Create image").performScrollTo().performClick()
            Assert.assertEquals(0, server.requestCount)
            val bytes = java.io.ByteArrayOutputStream().use { stream ->
                val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.BLUE); bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream); bitmap.recycle(); stream.toByteArray()
            }
            server.enqueue(MockResponse().setBody("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(bytes)}"}]}"""))
            compose.onNode(hasText("Create") and hasClickAction()).performClick()
            val media = ViewModelProvider(compose.activity)[MediaViewModel::class.java]
            compose.waitUntil(10_000) { compose.waitForIdle(); media.state.value.jobs.firstOrNull()?.status == "Ready" }
            compose.runOnIdle {
                Assert.assertTrue(File(media.state.value.jobs.first().localPath).isFile)
                Assert.assertEquals("image/jpeg", media.state.value.jobs.first().mime)
                Assert.assertEquals(1, server.requestCount)
            }
        } finally { server.shutdown() }
    }
}
