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
import com.nshd.geminifreellm.MainActivity
import org.junit.*
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
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
        compose.onNodeWithText("FreeLLM AI · 2.1 (5)").assertExists()
        capture("drawer")
    }
    @Test fun modelPickerExpandsProviderAndAllowsManualId() {
        ready()
        compose.onNodeWithContentDescription("Choose AI model").performClick()
        compose.onNodeWithText("Choose your AI").assertIsDisplayed()
        compose.onNodeWithText("Enter model ID manually").assertExists()
        compose.onNodeWithContentDescription("Close model picker").performClick()
        compose.onNodeWithContentDescription("Message").assertExists()
    }
    @Test fun canvasCanEditAndReturnToConversation() {
        ready()
        compose.onNodeWithContentDescription("Chat options").performClick()
        compose.onNodeWithText("Coding canvas").performClick()
        compose.onNodeWithText("Write code here, or open a code block from a reply.").performTextInput("fun main() = println(42)")
        compose.onNodeWithText("Review", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Message").assertTextContains("Review this text code.", substring = true)
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
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
