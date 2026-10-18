package com.nshd.geminifreellm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.os.PowerManager
import android.hardware.display.DisplayManager
import com.nshd.geminifreellm.ui.FreeLlmApp

class MainActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FreeLlmApp(chat) }
    }
    override fun onResume() {
        super.onResume()
        val display = getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) ?: return
        val current = display.mode
        val mode = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate <= 120.1f }
            .maxByOrNull { it.refreshRate }
        // A preference only: Android retains control for battery saver and device policy.
        window.attributes = window.attributes.apply {
            preferredRefreshRate = if (getSystemService(PowerManager::class.java).isPowerSaveMode) 0f else mode?.refreshRate ?: 0f
        }
    }
    override fun onStop() { chat.flushDraft(); super.onStop() }
}
