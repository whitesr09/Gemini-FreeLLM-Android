package com.nshd.geminifreellm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nshd.geminifreellm.data.ChatResult
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns all chat state so it survives configuration changes (rotation, theme
 * switch, multi-window). The Activity only owns connection settings.
 */
class ChatViewModel : ViewModel() {
    private val client = FreeLlmApiClient()
    private val ids = AtomicLong(System.currentTimeMillis())

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    fun setInput(value: String) {
        _input.value = value
    }

    fun clear() {
        _messages.value = emptyList()
    }

    fun send(baseUrl: String, apiKey: String, prompt: String) {
        val text = prompt.trim()
        if (text.isBlank() || _busy.value) return
        _input.value = ""
        _messages.value = _messages.value + ChatMessage(ids.incrementAndGet(), text, ChatMessage.Role.USER)
        _busy.value = true
        viewModelScope.launch {
            // Snapshot history now; the list won't change while the request is in flight.
            val history = _messages.value
            val reply = when (val result = client.send(baseUrl, apiKey, history)) {
                is ChatResult.Success ->
                    ChatMessage(ids.incrementAndGet(), result.text, ChatMessage.Role.ASSISTANT)
                is ChatResult.Failure ->
                    ChatMessage(ids.incrementAndGet(), result.message, ChatMessage.Role.ERROR)
            }
            _messages.value = _messages.value + reply
            _busy.value = false
        }
    }
}
