package com.nshd.geminifreellm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nshd.geminifreellm.data.ChatResult
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.data.database.RoomChatRepository
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ChatSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentId: String? = null,
    val busy: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RoomChatRepository(application.applicationContext)
    private val apiClient = FreeLlmApiClient()
    private val ids = AtomicLong(System.currentTimeMillis())

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun updateSession(session: ChatSession, persist: Boolean, model: String = "auto") {
        _uiState.update { state ->
            val exists = state.sessions.any { it.id == session.id }
            val sessions = if (exists) {
                state.sessions.map { if (it.id == session.id) session else it }
            } else {
                listOf(session) + state.sessions
            }
            state.copy(sessions = sessions, currentId = session.id)
        }
        if (persist) {
            viewModelScope.launch {
                runCatching { repository.saveSession(session, model) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val loaded = runCatching { repository.loadSessions() }.getOrElse { emptyList() }
            val current = repository.currentId()?.takeIf { id -> loaded.any { it.id == id } }
                ?: loaded.firstOrNull()?.id
            if (loaded.isEmpty()) {
                val fresh = repository.newSession()
                _uiState.value = ChatUiState(listOf(fresh), fresh.id, false)
                repository.saveSession(fresh)
                repository.saveCurrentId(fresh.id)
            } else {
                _uiState.value = ChatUiState(loaded, current, false)
            }
        }
    }

    fun newChat() {
        val session = repository.newSession()
        _uiState.update { it.copy(sessions = listOf(session) + it.sessions, currentId = session.id) }
        viewModelScope.launch {
            runCatching { repository.saveSession(session) }
            repository.saveCurrentId(session.id)
        }
    }

    fun selectSession(id: String) {
        if (uiState.value.sessions.any { it.id == id }) {
            _uiState.update { it.copy(currentId = id) }
            viewModelScope.launch { repository.saveCurrentId(id) }
        }
    }

    fun deleteSessions(ids: Set<String>) {
        if (ids.isEmpty()) return
        val remaining = uiState.value.sessions.filterNot { it.id in ids }
        val fresh = if (remaining.isEmpty()) repository.newSession() else null
        val finalSessions = fresh?.let { listOf(it) } ?: remaining
        val current = uiState.value.currentId
        val nextId = if (current !in ids && current in finalSessions.map { it.id }) current else finalSessions.firstOrNull()?.id
        _uiState.value = uiState.value.copy(sessions = finalSessions, currentId = nextId)
        viewModelScope.launch {
            runCatching { repository.deleteSessions(ids) }
            fresh?.let { runCatching { repository.saveSession(it) } }
            nextId?.let(repository::saveCurrentId)
        }
    }

    fun toggleStar(id: String) {
        val changed = uiState.value.sessions.firstOrNull { it.id == id }?.copy(
            starred = !(uiState.value.sessions.firstOrNull { it.id == id }?.starred ?: false)
        ) ?: return
        updateSession(changed, persist = true, model = changed.model)
    }

    fun persistAll(model: String) {
        val sessions = uiState.value.sessions
        viewModelScope.launch {
            sessions.forEach { session -> runCatching { repository.saveSession(session, model) } }
            uiState.value.currentId?.let(repository::saveCurrentId)
        }
    }

    fun exportJson(): String = repository.exportJson(uiState.value.sessions)

    fun importJson(raw: String, model: String) {
        viewModelScope.launch {
            val imported = runCatching { repository.importJson(raw) }.getOrElse { emptyList() }
            if (imported.isEmpty()) return@launch
            imported.forEach { session -> runCatching { repository.saveSession(session, model) } }
            val merged = (uiState.value.sessions + imported).distinctBy { it.id }
                .sortedByDescending { it.updatedAt }
            val current = merged.firstOrNull()?.id
            _uiState.value = uiState.value.copy(sessions = merged, currentId = current)
            current?.let(repository::saveCurrentId)
        }
    }

    fun sendMessage(
        baseUrl: String,
        apiKey: String,
        model: String,
        text: String,
        attachments: List<Attachment>
    ) {
        val state = uiState.value
        if (state.busy || (text.isBlank() && attachments.isEmpty())) return
        val session = state.sessions.firstOrNull { it.id == state.currentId } ?: repository.newSession()
        val userText = text.trim()
        val userMessage = ChatMessage(ids.incrementAndGet(), userText, ChatMessage.Role.USER, attachments = attachments)
        val assistantId = ids.incrementAndGet()
        val title = if (session.title == "New chat") {
            (userText.ifBlank { attachments.firstOrNull()?.name ?: "New chat" })
                .replace("\n", " ").trim().take(60).ifBlank { "New chat" }
        } else session.title
        val requestMessages = session.messages + userMessage
        val placeholder = ChatMessage(assistantId, "", ChatMessage.Role.ASSISTANT)
        val prepared = session.copy(
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = requestMessages + placeholder
        )
        updateSession(prepared, persist = true, model = model)
        _uiState.update { it.copy(busy = true) }

        viewModelScope.launch {
            val result = apiClient.send(
                getApplication(),
                baseUrl,
                apiKey,
                model,
                requestMessages
            ) { delta ->
                val current = uiState.value.sessions.firstOrNull { it.id == prepared.id } ?: return@send
                val updated = current.messages.map { message ->
                    if (message.id == assistantId) message.copy(text = message.text + delta) else message
                }
                _uiState.update { it.copy(sessions = it.sessions.map { s -> if (s.id == current.id) current.copy(messages = updated, updatedAt = System.currentTimeMillis()) else s }) }
            }

            val current = uiState.value.sessions.firstOrNull { it.id == prepared.id } ?: prepared
            val updated = current.messages.map { message ->
                if (message.id != assistantId) message
                else when (result) {
                    is ChatResult.Success -> message.copy(text = result.text)
                    is ChatResult.Failure -> message.copy(text = result.message, role = ChatMessage.Role.ERROR)
                    is ChatResult.Cancelled -> message
                }
            }
            val finished = current.copy(messages = updated, updatedAt = System.currentTimeMillis())
            _uiState.update { it.copy(sessions = it.sessions.map { s -> if (s.id == finished.id) finished else s }, busy = false) }
            runCatching { repository.saveSession(finished, model) }
        }
    }

    fun regenerate(messageId: Long, baseUrl: String, apiKey: String, model: String) {
        val state = uiState.value
        if (state.busy) return
        val session = state.sessions.firstOrNull { it.id == state.currentId } ?: return
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index <= 0) return
        val requestMessages = session.messages.take(index).filter { it.role != ChatMessage.Role.ERROR }
        if (requestMessages.lastOrNull()?.role != ChatMessage.Role.USER) return
        val assistantId = ids.incrementAndGet()
        val placeholder = ChatMessage(assistantId, "", ChatMessage.Role.ASSISTANT)
        // Preserve the old response: regeneration creates a new response instead of deleting history.
        val prepared = session.copy(
            messages = requestMessages + placeholder,
            updatedAt = System.currentTimeMillis()
        )
        updateSession(prepared, persist = true, model = model)
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = apiClient.send(getApplication(), baseUrl, apiKey, model, requestMessages) { delta ->
                val current = uiState.value.sessions.firstOrNull { it.id == prepared.id } ?: return@send
                val updated = current.messages.map { message ->
                    if (message.id == assistantId) message.copy(text = message.text + delta) else message
                }
                _uiState.update { it.copy(sessions = it.sessions.map { s -> if (s.id == current.id) current.copy(messages = updated) else s }) }
            }
            val current = uiState.value.sessions.firstOrNull { it.id == prepared.id } ?: prepared
            val updated = current.messages.map { message ->
                if (message.id == assistantId) {
                    when (result) {
                        is ChatResult.Success -> message.copy(text = result.text)
                        is ChatResult.Failure -> message.copy(text = result.message, role = ChatMessage.Role.ERROR)
                        is ChatResult.Cancelled -> message
                    }
                } else message
            }
            val finished = current.copy(messages = updated, updatedAt = System.currentTimeMillis())
            _uiState.update { it.copy(sessions = it.sessions.map { s -> if (s.id == finished.id) finished else s }, busy = false) }
            runCatching { repository.saveSession(finished, model) }
        }
    }

    fun stopGeneration() {
        apiClient.cancelActive()
    }

    override fun onCleared() {
        apiClient.cancelActive()
        super.onCleared()
    }
}
