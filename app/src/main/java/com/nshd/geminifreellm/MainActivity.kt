package com.nshd.geminifreellm

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nshd.geminifreellm.data.DEFAULT_BASE_URL
import com.nshd.geminifreellm.ui.ChatScreen
import com.nshd.geminifreellm.ui.ChatViewModel
import com.nshd.geminifreellm.ui.GeminiTheme
import com.nshd.geminifreellm.ui.SettingsDialog
import com.nshd.geminifreellm.ui.ThemeMode

class MainActivity : ComponentActivity() {
    private val chatViewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }

            var baseUrl by remember {
                mutableStateOf(prefs.getString("baseUrl", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
            }
            var apiKey by remember {
                mutableStateOf(prefs.getString("apiKey", "") ?: "")
            }
            var themeMode by remember {
                mutableStateOf(
                    runCatching {
                        ThemeMode.valueOf(prefs.getString("theme", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
                    }.getOrDefault(ThemeMode.SYSTEM)
                )
            }
            var showSettings by remember { mutableStateOf(apiKey.isBlank()) }

            val messages by chatViewModel.messages.collectAsState()
            val busy by chatViewModel.busy.collectAsState()
            val input by chatViewModel.input.collectAsState()

            GeminiTheme(themeMode) {
                ChatScreen(
                    messages = messages,
                    busy = busy,
                    input = input,
                    onInputChange = chatViewModel::setInput,
                    onSend = { prompt -> chatViewModel.send(baseUrl, apiKey, prompt) },
                    onSuggestion = { prompt -> chatViewModel.send(baseUrl, apiKey, prompt) },
                    onSettings = { showSettings = true },
                    onClear = chatViewModel::clear
                )
                if (showSettings) {
                    SettingsDialog(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        themeMode = themeMode,
                        onSave = { url, key, theme ->
                            baseUrl = url
                            apiKey = key
                            themeMode = theme
                            prefs.edit()
                                .putString("baseUrl", baseUrl)
                                .putString("apiKey", apiKey)
                                .putString("theme", theme.name)
                                .apply()
                            showSettings = false
                        },
                        onDismiss = { if (apiKey.isNotBlank()) showSettings = false }
                    )
                }
            }
        }
    }
}
