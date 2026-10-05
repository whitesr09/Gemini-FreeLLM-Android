package com.nshd.geminifreellm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nshd.geminifreellm.data.AppSettings
import com.nshd.geminifreellm.data.ChatResult
import com.nshd.geminifreellm.data.ConnectionCheck
import com.nshd.geminifreellm.data.ContextManager
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.data.ModelInfo
import com.nshd.geminifreellm.data.SettingsRepository
import com.nshd.geminifreellm.data.database.StorageSnapshot
import com.nshd.geminifreellm.data.database.RoomChatRepository
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ChatSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentId: String? = null,
    val busy: Boolean = false,
    val connectionTesting: Boolean = false,
    val connectionResult: ConnectionCheck? = null,
    val connectionError: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val modelLoading: Boolean = false,
    val modelError: String? = null,
    val contextUsedChars: Int = 0,
    val contextLimit: Int = ContextManager.DEFAULT_MAX_CHARS,
    val contextTruncated: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val localToolsEnabled: Boolean = true,
    val settings: AppSettings = AppSettings(),
    val storage: StorageSnapshot? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = RoomChatRepository(application.applicationContext)
    private val apiClient = FreeLlmApiClient()
    private val settingsRepository = SettingsRepository(application.applicationContext)
    private val ids = AtomicLong(System.currentTimeMillis())
    private val modelPrefs = application.getSharedPreferences("model_cache", Application.MODE_PRIVATE)
    private val generationIds = AtomicLong(0L)
    private var generationJob: Job? = null
    private var activeGenerationId: Long? = null
    private var activeGenerationModel: String = "auto"
    private var currentSettings = settingsRepository.load()

    private val _uiState = MutableStateFlow(
        ChatUiState(
            contextLimit = currentSettings.contextLimit,
            webSearchEnabled = currentSettings.webSearchDefault,
            localToolsEnabled = currentSettings.localToolsEnabled,
            settings = currentSettings
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun replaceSession(session: ChatSession, persist: Boolean = true, model: String = selectedModel()) {
        updateSession(session, persist, model)
    }

    fun applySettings(settings: AppSettings) {
        currentSettings = settings
        settingsRepository.save(settings)
        _uiState.update {
            it.copy(
                settings = settings,
                contextLimit = settings.contextLimit,
                webSearchEnabled = settings.webSearchDefault,
                localToolsEnabled = settings.localToolsEnabled
            )
        }
    }

    fun setWebSearch(enabled: Boolean) {
        _uiState.update { it.copy(webSearchEnabled = enabled) }
    }

    fun setLocalTools(enabled: Boolean) {
        _uiState.update { it.copy(localToolsEnabled = enabled) }
    }

    fun storageSnapshot() {
        viewModelScope.launch {
            _uiState.update { it.copy(storage = runCatching { repository.storageSnapshot() }.getOrNull()) }
        }
    }

    private fun selectedModel(): String = currentSettings.selectedModel.ifBlank { "auto" }

    private fun updateSession(session: ChatSession, persist: Boolean, model: String) {
        _uiState.update { state ->
            val exists = state.sessions.any { it.id == session.id }
            val sessions = if (exists) {
                state.sessions.map { if (it.id == session.id) session else it }
            } else {
                listOf(session) + state.sessions
            }
            state.copy(sessions = sessions, currentId = session.id)
        }
        if (persist && !session.temporary) {
            viewModelScope.launch { runCatching { repository.saveSession(session, model) } }
        }
    }

    private fun replaceSessionsInState(session: ChatSession) {
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions.map { if (it.id == session.id) session else it },
                currentId = session.id
            )
        }
    }

    fun load() {
        viewModelScope.launch {
            val loaded = runCatching { repository.loadSessions() }.getOrElse { emptyList() }
            val current = repository.currentId()?.takeIf { id -> loaded.any { it.id == id } }
                ?: loaded.firstOrNull()?.id
            if (loaded.isEmpty()) {
                val fresh = repository.newSession()
                _uiState.value = _uiState.value.copy(
                    sessions = listOf(fresh),
                    currentId = fresh.id,
                    busy = false
                )
                runCatching { repository.saveSession(fresh, selectedModel()) }
                repository.saveCurrentId(fresh.id)
            } else {
                _uiState.update { it.copy(sessions = loaded, currentId = current, busy = false) }
            }
            storageSnapshot()
        }
    }

    fun refreshModels(baseUrl: String, apiKey: String) {
        val cached = readCachedModels(baseUrl)
        if (_uiState.value.models.isEmpty() && cached.isNotEmpty()) {
            _uiState.update { it.copy(models = cached, modelError = null) }
        }
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            _uiState.update { it.copy(modelLoading = false, modelError = null) }
            return
        }
        _uiState.update { it.copy(modelLoading = true, modelError = null) }
        viewModelScope.launch {
            apiClient.fetchModels(baseUrl, apiKey)
                .onSuccess { models ->
                    writeCachedModels(baseUrl, models)
                    _uiState.update { it.copy(models = models, modelLoading = false, modelError = null) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            models = if (it.models.isNotEmpty()) it.models else cached,
                            modelLoading = false,
                            modelError = error.message ?: "Couldn't load models."
                        )
                    }
                }
        }
    }

    private fun cacheKey(baseUrl: String): String =
        "models_" + baseUrl.trim().trimEnd('/').hashCode()

    private fun readCachedModels(baseUrl: String): List<ModelInfo> = runCatching {
        val raw = modelPrefs.getString(cacheKey(baseUrl), null) ?: return@runCatching emptyList()
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotBlank()) {
                    add(
                        ModelInfo(
                            id = id,
                            name = o.optString("name", id),
                            available = o.optBoolean("available", true),
                            provider = o.optString("provider").takeIf { it.isNotBlank() },
                            supportsVision = o.optBoolean("vision"),
                            supportsImageGeneration = o.optBoolean("image"),
                            supportsVideoGeneration = o.optBoolean("video"),
                            contextSize = o.optLong("context", 0L).takeIf { it > 0L },
                            reasoning = o.optBoolean("reasoning"),
                            coding = o.optBoolean("coding"),
                            speed = o.optString("speed").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun writeCachedModels(baseUrl: String, models: List<ModelInfo>) {
        val array = JSONArray()
        models.forEach { model ->
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("name", model.name)
                    .put("available", model.available)
                    .put("provider", model.provider ?: "")
                    .put("vision", model.supportsVision)
                    .put("image", model.supportsImageGeneration)
                    .put("video", model.supportsVideoGeneration)
                    .put("context", model.contextSize ?: 0L)
                    .put("reasoning", model.reasoning)
                    .put("coding", model.coding)
                    .put("speed", model.speed ?: "")
            )
        }
        modelPrefs.edit().putString(cacheKey(baseUrl), array.toString()).apply()
    }

    fun cleanupOrphans() {
        viewModelScope.launch {
            runCatching { repository.cleanupOrphans() }
            storageSnapshot()
        }
    }

    fun testConnection(baseUrl: String, apiKey: String) {
        _uiState.update { it.copy(connectionTesting = true, connectionError = null) }
        viewModelScope.launch {
            apiClient.testConnection(baseUrl, apiKey)
                .onSuccess { check ->
                    _uiState.update { it.copy(connectionTesting = false, connectionResult = check, connectionError = null) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            connectionTesting = false,
                            connectionResult = null,
                            connectionError = error.message ?: "Connection test failed."
                        )
                    }
                }
        }
    }

    fun newChat(temporary: Boolean = currentSettings.temporaryDefault) {
        cancelGeneration()
        val session = repository.newSession(temporary)
        _uiState.update { it.copy(sessions = listOf(session) + it.sessions, currentId = session.id) }
        if (!temporary) {
            viewModelScope.launch {
                runCatching { repository.saveSession(session, selectedModel()) }
                repository.saveCurrentId(session.id)
            }
        }
    }

    fun selectSession(id: String) {
        if (uiState.value.sessions.any { it.id == id }) {
            if (activeGenerationId != null && uiState.value.currentId != id) cancelGeneration()
            _uiState.update { it.copy(currentId = id) }
            val selected = uiState.value.sessions.firstOrNull { it.id == id }
            if (selected?.temporary != true) viewModelScope.launch { repository.saveCurrentId(id) }
        }
    }

    fun deleteSessions(idsToDelete: Set<String>) {
        if (idsToDelete.isEmpty()) return
        if (activeGenerationId != null && uiState.value.currentId in idsToDelete) cancelGeneration()
        val remaining = uiState.value.sessions.filterNot { it.id in idsToDelete }
        val fresh = if (remaining.isEmpty()) repository.newSession() else null
        val finalSessions = fresh?.let { listOf(it) } ?: remaining
        val current = uiState.value.currentId
        val nextId = if (current !in idsToDelete && finalSessions.any { it.id == current }) current
        else finalSessions.firstOrNull()?.id
        _uiState.value = _uiState.value.copy(sessions = finalSessions, currentId = nextId)
        viewModelScope.launch {
            runCatching { repository.deleteSessions(idsToDelete) }
            fresh?.let { runCatching { repository.saveSession(it, selectedModel()) } }
            val selected = finalSessions.firstOrNull { it.id == nextId }
            if (selected?.temporary != true) nextId?.let(repository::saveCurrentId)
            storageSnapshot()
        }
    }

    fun toggleStar(id: String) {
        val session = uiState.value.sessions.firstOrNull { it.id == id } ?: return
        updateSession(session.copy(starred = !session.starred), true, selectedModel())
    }

    fun deleteAttachment(attachmentId: String) {
        if (attachmentId.isBlank()) return
        val session = uiState.value.sessions.firstOrNull { it.id == uiState.value.currentId } ?: return
        val target = session.messages.asSequence().flatMap { it.attachments.asSequence() }.firstOrNull { it.id == attachmentId }
            ?: return
        val root = getApplication<Application>().filesDir.canonicalFile
        val targetFile = runCatching { java.io.File(target.localPath).canonicalFile }.getOrNull()
        if (targetFile != null && (targetFile.path == root.path || targetFile.path.startsWith(root.path + java.io.File.separator))) {
            runCatching { targetFile.delete() }
        }
        val updated = session.copy(
            messages = session.messages.map { message ->
                message.copy(attachments = message.attachments.filterNot { it.id == attachmentId })
            },
            updatedAt = System.currentTimeMillis()
        )
        updateSession(updated, persist = !updated.temporary, model = selectedModel())
        storageSnapshot()
    }

    fun archiveSession(id: String) {
        val session = uiState.value.sessions.firstOrNull { it.id == id } ?: return
        updateSession(session.copy(archived = true), true, selectedModel())
    }

    fun unarchiveSession(id: String) {
        val session = uiState.value.sessions.firstOrNull { it.id == id } ?: return
        updateSession(session.copy(archived = false), true, selectedModel())
    }

    fun persistSession(session: ChatSession, model: String) {
        viewModelScope.launch { runCatching { repository.saveSession(session, model) } }
    }

    fun clearAll(model: String) {
        cancelGeneration()
        viewModelScope.launch {
            runCatching { repository.clearAll() }
            val fresh = repository.newSession()
            _uiState.value = _uiState.value.copy(
                sessions = listOf(fresh),
                currentId = fresh.id,
                busy = false
            )
            runCatching { repository.saveSession(fresh, model) }
            repository.saveCurrentId(fresh.id)
            storageSnapshot()
        }
    }

    fun exportJson(): String = repository.exportJson(uiState.value.sessions)

    fun importSessions(sessions: List<ChatSession>, model: String) {
        viewModelScope.launch {
            val remapped = remapImportedSessions(sessions)
            repository.importSessions(remapped, model)
            val merged = (uiState.value.sessions + remapped)
                .distinctBy { it.id }
                .sortedWith(compareByDescending<ChatSession> { it.starred }.thenByDescending { it.updatedAt })
            val current = remapped.firstOrNull()?.id ?: merged.firstOrNull()?.id
            _uiState.value = _uiState.value.copy(sessions = merged, currentId = current)
            if (current != null) repository.saveCurrentId(current)
        }
    }

    fun importJson(raw: String, model: String) {
        viewModelScope.launch {
            val imported = runCatching { repository.importJson(raw) }.getOrElse { emptyList() }
            if (imported.isEmpty()) return@launch
            importSessions(imported, model)
        }
    }

    fun editAndResend(
        messageId: Long,
        text: String,
        baseUrl: String,
        apiKey: String,
        model: String
    ) {
        val clean = text.trim()
        if (clean.isBlank() || uiState.value.busy) return
        val session = uiState.value.sessions.firstOrNull { it.id == uiState.value.currentId } ?: return
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0 || session.messages[index].role != ChatMessage.Role.USER) return
        val edited = session.messages[index].copy(text = clean, timestamp = System.currentTimeMillis())
        val before = session.messages.take(index)
        val requestMessages = branchForRequest(before + edited)
        val placeholder = ChatMessage(
            id = ids.incrementAndGet(),
            text = "",
            role = ChatMessage.Role.ASSISTANT,
            parentMessageId = edited.id
        )
        val prepared = session.copy(
            title = if (session.title == "New chat") clean.take(60) else session.title,
            updatedAt = System.currentTimeMillis(),
            messages = before + edited + placeholder
        )
        startGeneration(prepared, requestMessages, placeholder.id, baseUrl, apiKey, model)
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
        val userMessage = ChatMessage(
            id = ids.incrementAndGet(),
            text = userText,
            role = ChatMessage.Role.USER,
            attachments = attachments
        )
        val assistantId = ids.incrementAndGet()
        val title = if (session.title == "New chat" || session.title == "Temporary chat") {
            (userText.ifBlank { attachments.firstOrNull()?.name ?: "New chat" })
                .replace("\n", " ")
                .trim()
                .take(60)
                .ifBlank { session.title }
        } else session.title

        val requestMessages = branchForRequest(session.messages) + userMessage
        val placeholder = ChatMessage(
            assistantId,
            "",
            ChatMessage.Role.ASSISTANT,
            parentMessageId = userMessage.id
        )
        val prepared = session.copy(
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = session.messages + userMessage + placeholder
        )
        startGeneration(prepared, requestMessages, assistantId, baseUrl, apiKey, model)
    }

    fun regenerate(
        messageId: Long,
        baseUrl: String,
        apiKey: String,
        model: String
    ) {
        val state = uiState.value
        if (state.busy) return
        val session = state.sessions.firstOrNull { it.id == state.currentId } ?: return
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val selected = session.messages[index]
        if (selected.role != ChatMessage.Role.ASSISTANT) return
        val parentUserIndex = session.messages.take(index).indexOfLast { it.role == ChatMessage.Role.USER }
        if (parentUserIndex < 0) return
        val parentUser = session.messages[parentUserIndex]
        val contextBefore = branchForRequest(session.messages.take(parentUserIndex + 1))
        val assistantId = ids.incrementAndGet()
        val placeholder = ChatMessage(
            assistantId,
            "",
            ChatMessage.Role.ASSISTANT,
            parentMessageId = parentUser.id
        )
        val insertAt = (index + 1).coerceAtMost(session.messages.size)
        val messages = session.messages.toMutableList().apply { add(insertAt, placeholder) }
        val prepared = session.copy(messages = messages, updatedAt = System.currentTimeMillis())
        startGeneration(prepared, contextBefore, assistantId, baseUrl, apiKey, model)
    }

    private fun startGeneration(
        prepared: ChatSession,
        requestMessages: List<ChatMessage>,
        assistantId: Long,
        baseUrl: String,
        apiKey: String,
        model: String
    ) {
        cancelGeneration()
        val generationId = generationIds.incrementAndGet()
        activeGenerationId = generationId
        activeGenerationModel = model

        replaceSession(prepared, persist = !prepared.temporary, model = model)
        _uiState.update {
            it.copy(
                busy = true,
                contextUsedChars = ContextManager.trim(requestMessages, currentSettings.contextLimit).usedChars,
                contextLimit = currentSettings.contextLimit,
                contextTruncated = ContextManager.trim(requestMessages, currentSettings.contextLimit).truncated
            )
        }

        generationJob = viewModelScope.launch {
            val result = apiClient.send(
                context = getApplication(),
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                messages = requestMessages,
                onDelta = { delta ->
                    if (activeGenerationId != generationId) return@send
                    val current = uiState.value.sessions.firstOrNull { it.id == prepared.id } ?: return@send
                    val updated = current.messages.map { message ->
                        if (message.id == assistantId) message.copy(text = message.text + delta) else message
                    }
                    replaceSessionsInState(
                        current.copy(
                            messages = updated,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                },
                systemPrompt = prepared.systemPrompt ?: currentSettings.systemPrompt,
                contextLimit = currentSettings.contextLimit,
                webSearch = uiState.value.webSearchEnabled,
                localTools = uiState.value.localToolsEnabled
            )

            if (activeGenerationId != generationId) return@launch

            val current = uiState.value.sessions.firstOrNull { it.id == prepared.id } ?: return@launch
            val updated = current.messages.map { message ->
                if (message.id != assistantId) return@map message
                when (result) {
                    is ChatResult.Success -> message.copy(text = result.text, metadata = result.metadata)
                    is ChatResult.Failure -> message.copy(text = result.message, role = ChatMessage.Role.ERROR)
                    ChatResult.Cancelled -> message
                }
            }
            val finished = current.copy(
                messages = updated,
                updatedAt = System.currentTimeMillis()
            )
            replaceSessionsInState(finished)
            _uiState.update { it.copy(busy = false) }
            if (!finished.temporary) runCatching { repository.saveSession(finished, model) }
            activeGenerationId = null
            generationJob = null
        }
    }

    private fun branchForRequest(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return emptyList()
        val output = mutableListOf<ChatMessage>()
        val seenParent = mutableSetOf<Long>()
        messages.forEach { message ->
            if (message.role == ChatMessage.Role.ERROR) return@forEach
            if (message.role == ChatMessage.Role.ASSISTANT && message.parentMessageId != null) {
                if (message.parentMessageId in seenParent) return@forEach
                seenParent += message.parentMessageId
            }
            output += message
        }
        return output
    }

    private fun remapImportedSessions(sessions: List<ChatSession>): List<ChatSession> {
        val sessionIds = mutableMapOf<String, String>()
        val messageIds = mutableMapOf<Long, Long>()
        val attachmentIds = mutableMapOf<String, String>()
        sessions.forEach { sessionIds[it.id] = UUID.randomUUID().toString() }
        sessions.flatMap { it.messages }.forEach { messageIds[it.id] = ids.incrementAndGet() }
        sessions.flatMap { it.messages }.flatMap { it.attachments }.forEach { attachmentIds[it.id] = UUID.randomUUID().toString() }

        return sessions.map { session ->
            session.copy(
                id = sessionIds.getValue(session.id),
                messages = session.messages.map { message ->
                    message.copy(
                        id = messageIds.getValue(message.id),
                        parentMessageId = message.parentMessageId?.let { messageIds[it] },
                        attachments = message.attachments.map { attachment ->
                            attachment.copy(
                                id = attachmentIds.getValue(attachment.id)
                            )
                        }
                    )
                }
            )
        }
    }

    private fun cancelGeneration() {
        activeGenerationId = null
        apiClient.cancelActive()
        generationJob?.cancel()
        generationJob = null
        _uiState.update { it.copy(busy = false) }
    }

    fun stopGeneration() = cancelGeneration()

    override fun onCleared() {
        apiClient.cancelActive()
        super.onCleared()
    }
}
