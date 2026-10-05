package com.nshd.geminifreellm

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val text: String, val fromUser: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(context: Context) {
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "http://127.0.0.1:3001/v1") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("apiKey", "") ?: "") }
    var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Gemini FreeLLM") },
                    actions = { TextButton(onClick = { showSettings = true }) { Text("⚙") } }
                )
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask anything…") }, maxLines = 4
                    )
                    TextButton(
                        enabled = input.isNotBlank() && !busy,
                        onClick = {
                            val prompt = input.trim()
                            input = ""
                            messages += ChatMessage(prompt, true)
                            busy = true
                            scope.launch {
                                val result = sendMessage(baseUrl, apiKey, messages.toList())
                                messages += ChatMessage(result, false)
                                busy = false
                            }
                        }
                    ) { Text("➤") }
                }
            }
        ) { padding ->
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your private FreeLLM chat", style = MaterialTheme.typography.headlineSmall)
                        Text("FreeLLMAPI → Gemini", Modifier.padding(top = 8.dp))
                        Text("Tap ⚙ to configure the connection.", Modifier.padding(top = 4.dp))
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    items(messages) { msg ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                color = if (msg.fromUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth(.88f)
                            ) { Text(msg.text, Modifier.padding(14.dp)) }
                        }
                    }
                }
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
                }
            }
        }

        if (showSettings) {
            SettingsDialog(baseUrl, apiKey, { u, k ->
                baseUrl = u.trim().removeSuffix("/")
                apiKey = k.trim()
                prefs.edit().putString("baseUrl", baseUrl).putString("apiKey", apiKey).apply()
                showSettings = false
            }, { if (apiKey.isNotBlank()) showSettings = false })
        }
    }
}

@Composable
private fun SettingsDialog(
    initialUrl: String, initialKey: String,
    onSave: (String, String) -> Unit, onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var key by remember { mutableStateOf(initialKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FreeLLMAPI connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your key stays on this phone. Never put it in GitHub.")
                OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true)
                OutlinedTextField(
                    key, { key = it }, label = { Text("Unified API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = {
            Button(enabled = url.isNotBlank() && key.isNotBlank(), onClick = { onSave(url, key) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private suspend fun sendMessage(
    baseUrl: String, apiKey: String, messages: List<ChatMessage>
): String = try {
    val jsonMessages = JSONArray()
    messages.forEach {
        jsonMessages.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text))
    }
    val payload = JSONObject()
        .put("model", "auto")
        .put("messages", jsonMessages)
        .put("stream", false)

    val request = Request.Builder()
        .url(baseUrl.trimEnd('/') + "/chat/completions")
        .addHeader("Authorization", "Bearer " + apiKey)
        .addHeader("Content-Type", "application/json")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()

    OkHttpClient().newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) "API error " + response.code + ": " + body
        else JSONObject(body).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }
} catch (e: Exception) {
    "Connection error: " + (e.message ?: "unknown error")
}
