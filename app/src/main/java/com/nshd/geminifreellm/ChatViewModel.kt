package com.nshd.geminifreellm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nshd.geminifreellm.data.*
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns requests across rotation. Requests are bound to a conversation and a unique generation ID. */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val loading: Boolean = true,
        val settings: AppSettings = AppSettings(),
        val chats: List<Conversation> = emptyList(),
        val activeId: String = "",
        val generatingId: String? = null,
        val preparing: Boolean = false,
        val notice: String? = null,
        val storageBlocked: Boolean = false,
        val catalogs: Map<Provider, ModelCatalog> = emptyMap(),
        val access: Map<String, ModelAccess> = emptyMap(),
        val checking: Set<String> = emptySet()
    ) {
        val active: Conversation? get() = chats.firstOrNull { it.id == activeId }
    }

    val diagnostics = (application as FreeLlmApplication).diagnostics
    private val store = LocalStore(application)
    private val settingsMutex = Mutex()
    private val catalogJobs = mutableMapOf<Provider, Job>()
    private var draftSave: Job? = null
    val client = FreeLlmApiClient()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var generation: Job? = null
    private var attachmentJob: Job? = null
    private var generationToken: String? = null
    private val saves = Channel<Pair<List<Conversation>, String>>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            try {
                val (settings, history) = withContext(Dispatchers.IO) { store.loadSettings() to store.loadHistory() }
                val chats = history.first.ifEmpty { listOf(Conversation()) }
                mutable.value = State(loading = false, settings = settings, chats = chats,
                    activeId = history.second?.takeIf { id -> chats.any { it.id == id } } ?: chats.first().id)
                if (settings.autoRouting) settings.profiles.filter { it.provider !in settings.autoExcluded }.forEach { loadCatalog(it.provider) }
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { diagnostics.record("Storage", error = error) }
                // Never overwrite a history/credential file that could not be read.
                mutable.value = State(loading = false, storageBlocked = true,
                    notice = "Local data could not be opened. Restart the app to retry; existing files have been preserved.")
            }
        }
        viewModelScope.launch {
            for ((chats, active) in saves) {
                try { withContext(Dispatchers.IO) { store.saveHistory(chats, active) } }
                catch (error: Exception) {
                    withContext(Dispatchers.IO) { diagnostics.record("Storage", error = error) }
                    notice("Could not save chats. Free some device storage, then try again.")
                }
            }
        }
    }

    private fun persist() {
        val value = mutable.value
        if (!value.loading && !value.storageBlocked) saves.trySend(value.chats to value.activeId)
    }

    private fun changeChat(id: String = mutable.value.activeId, save: Boolean = true, update: (Conversation) -> Conversation) {
        mutable.update { value -> value.copy(chats = value.chats.map { if (it.id == id) update(it) else it }) }
        if (save) persist()
    }

    fun draft(text: String) {
        changeChat(save = false) { it.copy(draft = text.take(120_000)) }
        draftSave?.cancel()
        draftSave = viewModelScope.launch { delay(350); persist() }
    }
    fun saveCanvas(document: CanvasDocument) = changeChat { it.copy(canvas = document.copy(
        title = document.title.take(80), language = document.language.take(30), code = document.code.take(120_000))) }
    fun askAboutCanvas(document: CanvasDocument, action: String) {
        saveCanvas(document)
        draft("$action this ${document.language} code. Explain your changes and return complete code in a fenced block.\n\n```${document.language}\n${document.code}\n```")
    }
    fun diagnose(report: String) {
        val chat = Conversation(title = "App diagnostics", draft = "Explain these app diagnostics in simple terms. Identify likely causes, safe fixes I can try, and improvements for the developer. Do not claim to have patched the installed APK.\n\n$report")
        stop()
        mutable.update { it.copy(chats = listOf(chat) + it.chats, activeId = chat.id) }
        persist()
    }

    fun loadCatalog(provider: Provider, force: Boolean = false) {
        val current = mutable.value
        val profile = current.settings.profiles.firstOrNull { it.provider == provider } ?: ProviderProfile(provider)
        val cached = current.catalogs[provider]
        if (catalogJobs[provider]?.isActive == true || (!force && cached?.fetchedAt != null && cached.fetchedAt > System.currentTimeMillis() - 300_000)) return
        mutable.update { it.copy(catalogs = it.catalogs + (provider to (cached ?: ModelCatalog()).copy(loading = true, error = null))) }
        catalogJobs[provider] = viewModelScope.launch {
            try {
                val models = client.catalog(profile)
                mutable.update { it.copy(catalogs = it.catalogs + (provider to ModelCatalog(models, fetchedAt = System.currentTimeMillis()))) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.update { it.copy(catalogs = it.catalogs + (provider to (cached ?: ModelCatalog()).copy(loading = false,
                    error = (error as? ApiException)?.message ?: "Could not load models. Retry or enter an ID manually."))) }
                withContext(Dispatchers.IO) { diagnostics.record("Model catalog", provider, error) }
            }
        }
    }

    suspend fun selectModel(provider: Provider, model: String): Boolean {
        val profile = mutable.value.settings.profiles.firstOrNull { it.provider == provider } ?: ProviderProfile(provider)
        FreeLlmApiClient.validate(profile.copy(model = model.trim()))?.let { notice(it); return false }
        return updateSettings { current -> current.copy(selected = provider, autoRouting = false,
            profiles = current.profiles.filterNot { it.provider == provider } + profile.copy(model = model.trim())) }
    }

    suspend fun selectAuto(): Boolean {
        val saved = updateSettings { it.copy(autoRouting = true) }
        if (saved) mutable.value.settings.profiles.filter { it.provider !in mutable.value.settings.autoExcluded }.forEach { loadCatalog(it.provider) }
        return saved
    }

    suspend fun saveAgent(agent: AgentConfig): Boolean {
        agent.validationError()?.let { notice(it); return false }
        return updateSettings { it.copy(agent = agent) }
    }

    suspend fun setAutoProvider(provider: Provider, enabled: Boolean): Boolean = updateSettings {
        it.copy(autoExcluded = if (enabled) it.autoExcluded - provider else (it.autoExcluded + provider).distinct())
    }

    private fun requestCandidates(value: State, history: List<ChatMessage>): List<ProviderProfile> =
        if (value.settings.autoRouting) AutoRouter.candidates(value.settings, value.catalogs, value.access, history)
        else listOf(value.settings.active)

    fun favorite(provider: Provider, model: String) {
        viewModelScope.launch {
            val key = modelKey(provider, model)
            updateSettings { settings -> settings.copy(favorites = if (key in settings.favorites) settings.favorites - key else (settings.favorites + key).takeLast(100)) }
        }
    }

    fun checkModel(provider: Provider, model: String) {
        val profile = (mutable.value.settings.profiles.firstOrNull { it.provider == provider } ?: ProviderProfile(provider)).copy(model = model)
        val key = modelKey(provider, model)
        if (key in mutable.value.checking || mutable.value.checking.isNotEmpty()) return
        mutable.update { it.copy(checking = it.checking + key) }
        viewModelScope.launch {
            try {
                client.generate(profile, listOf(ChatMessage(text = "Reply with OK only.", role = ChatMessage.Role.USER))) {}
                markAccess(profile, AccessState.USABLE)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                markAccess(profile, (error as? ApiException)?.access ?: AccessState.ERROR)
                notice((error as? ApiException)?.message ?: "Model check failed.")
                withContext(Dispatchers.IO) { diagnostics.record("Model access check", provider, error) }
            } finally { mutable.update { it.copy(checking = it.checking - key) } }
        }
    }

    private fun markAccess(profile: ProviderProfile, access: AccessState) {
        val configured = mutable.value.settings.profiles.firstOrNull { it.provider == profile.provider } ?: ProviderProfile(profile.provider)
        if (configured.apiKey == profile.apiKey && configured.baseUrl == profile.baseUrl)
            mutable.update { it.copy(access = it.access + (modelKey(profile.provider, profile.model) to ModelAccess(access))) }
    }
    fun flushDraft() { draftSave?.cancel(); persist() }
    fun notice(text: String?) { mutable.update { it.copy(notice = text) } }

    fun newChat() {
        if (mutable.value.loading || mutable.value.storageBlocked) return
        stop()
        cancelAttachment()
        val existing = mutable.value.chats.firstOrNull { it.messages.isEmpty() && it.draft.isEmpty() && it.attachments.isEmpty() && !it.archived }
        val chat = existing ?: Conversation()
        mutable.update { it.copy(chats = if (existing == null) listOf(chat) + it.chats else it.chats, activeId = chat.id) }
        persist()
    }

    fun select(id: String) {
        if (id == mutable.value.activeId) return
        stop()
        cancelAttachment()
        mutable.update { it.copy(activeId = id) }
        persist()
    }

    fun rename(id: String, title: String) = changeChat(id) { it.copy(title = title.trim().take(100).ifBlank { "New chat" }) }
    fun pin(id: String) = changeChat(id) { it.copy(pinned = !it.pinned) }
    fun archive(id: String) = changeChat(id) { it.copy(archived = !it.archived) }
    fun delete(id: String) {
        if (mutable.value.activeId == id) { stop(); cancelAttachment() }
        mutable.update { value ->
            val remaining = value.chats.filterNot { it.id == id }.ifEmpty { listOf(Conversation()) }
            value.copy(chats = remaining, activeId = if (value.activeId == id) remaining.first().id else value.activeId)
        }
        persist()
        // Attachment files are collected only at a later startup to avoid racing queued writes.
    }

    suspend fun saveSettings(settings: AppSettings): Boolean = updateSettings { settings }

    private suspend fun updateSettings(transform: (AppSettings) -> AppSettings): Boolean = settingsMutex.withLock {
        if (mutable.value.storageBlocked) return@withLock false
        val old = mutable.value.settings
        val settings = transform(old).let { value -> value.copy(profiles = value.profiles.map { profile ->
            profile.copy(baseUrl = FreeLlmApiClient.normalizeBaseUrl(profile.baseUrl, profile.provider.protocol), apiKey = profile.apiKey.trim(), model = profile.model.trim())
        }) }
        try {
            withContext(Dispatchers.IO) { store.saveSettings(settings) }
            val changed = settings.profiles.filter { profile -> old.profiles.firstOrNull { it.provider == profile.provider }?.let {
                it.apiKey != profile.apiKey || it.baseUrl != profile.baseUrl
            } ?: true }.map { it.provider }.toSet()
            changed.forEach { catalogJobs.remove(it)?.cancel() }
            mutable.update { value -> value.copy(settings = settings,
                catalogs = value.catalogs.filterKeys { it !in changed },
                access = value.access.filterKeys { key -> changed.none { key.startsWith("${it.name}/") } }) }
            true
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            withContext(Dispatchers.IO) { diagnostics.record("Storage", error = error) }
            notice("Could not securely save provider settings. Your previous settings are unchanged."); false
        }
    }

    fun send() {
        val value = mutable.value
        val chat = value.active ?: return
        if (value.generatingId != null || value.preparing || value.storageBlocked || (chat.draft.isBlank() && chat.attachments.isEmpty())) return
        if (!value.settings.autoRouting) FreeLlmApiClient.validate(value.settings.active)?.let { notice(it); return }
        if (!value.settings.autoRouting && !value.settings.active.vision && chat.attachments.any { it.isImage }) {
            notice("Enable image input for a vision-capable model in settings before sending images."); return
        }
        val message = ChatMessage(text = chat.draft.trim(), role = ChatMessage.Role.USER, attachments = chat.attachments)
        val history = chat.messages + message
        if (requestCandidates(value, history).isEmpty()) { notice("No eligible Auto models. Configure a provider, enable image input if needed, or wait for cooldown."); return }
        changeChat { it.copy(messages = history, draft = "", attachments = emptyList(),
            title = if (it.messages.isEmpty()) message.text.ifBlank { message.attachments.first().name }.take(60) else it.title,
            updatedAt = System.currentTimeMillis()) }
        generate(chat.id, history, value)
    }

    fun continueReply() {
        if (mutable.value.active?.draft?.isNotBlank() == true) { notice("Send or clear your current draft before continuing this reply."); return }
        draft("Continue your previous response from where it stopped. Avoid repeating completed paragraphs.")
        send()
    }

    fun retry() {
        val value = mutable.value
        val chat = value.active ?: return
        if (value.generatingId != null) return
        val userIndex = chat.messages.indexOfLast { it.role == ChatMessage.Role.USER }
        if (userIndex < 0) return
        if (!value.settings.autoRouting) FreeLlmApiClient.validate(value.settings.active)?.let { notice(it); return }
        val history = chat.messages.take(userIndex + 1)
        if (requestCandidates(value, history).isEmpty()) { notice("No eligible Auto models. Check provider settings or wait for cooldown."); return }
        changeChat { it.copy(messages = history) }
        generate(chat.id, history, value)
    }

    /** The UI confirms replacement of the selected turn and its following replies. */
    fun edit(message: ChatMessage) {
        if (mutable.value.generatingId != null) return
        changeChat { chat ->
            val index = chat.messages.indexOfFirst { it.id == message.id }
            if (index < 0) chat else chat.copy(messages = chat.messages.take(index), draft = message.text, attachments = message.attachments)
        }
    }

    private fun generate(chatId: String, history: List<ChatMessage>, snapshot: State) {
        val candidates = requestCandidates(snapshot, history)
        var profile = candidates.first()
        val systemPrompt = snapshot.settings.agent.prompt()
        val assistant = ChatMessage(text = "", role = ChatMessage.Role.ASSISTANT, model = "${profile.provider.label} · ${profile.model}", interrupted = true)
        generationToken = assistant.id
        mutable.update { it.copy(generatingId = chatId) }
        changeChat(chatId) { it.copy(messages = history + assistant) }
        generation = viewModelScope.launch {
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                var firstToken = 0L
                var lastCheckpoint = started
                val updates = Channel<String>(Channel.CONFLATED)
                val collector = launch {
                    for (partial in updates) {
                        if (generationToken == assistant.id) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (firstToken == 0L && partial.isNotEmpty()) firstToken = now - started
                            val checkpoint = now - lastCheckpoint >= 2000
                            if (checkpoint) lastCheckpoint = now
                            changeChat(chatId, save = checkpoint) { chat ->
                                chat.copy(messages = chat.messages.map { if (it.id == assistant.id) it.copy(text = partial) else it })
                            }
                        }
                    }
                }
                val text = try {
                    if (snapshot.settings.autoRouting) AutoRouter.generate(candidates,
                        onAttempt = { selected, attempt ->
                            profile = selected
                            changeChat(chatId, save = false) { chat -> chat.copy(messages = chat.messages.map {
                                if (it.id == assistant.id) it.copy(model = "Auto · ${selected.provider.label} · ${selected.model}" + if (attempt > 1) " · fallback $attempt" else "") else it
                            }) }
                        }, onFailure = { failed, error ->
                            markAccess(failed, (error as? ApiException)?.access ?: AccessState.ERROR)
                            withContext(Dispatchers.IO) { diagnostics.record("Auto fallback", failed.provider, error) }
                        }, request = { selected, emit -> client.generate(selected, history, systemPrompt, emit) },
                        onText = { updates.trySend(it) }).second
                    else client.generate(profile, history, systemPrompt) { updates.trySend(it) }
                }
                    finally { updates.close(); collector.join() }
                if (generationToken == assistant.id) {
                    markAccess(profile, AccessState.USABLE)
                    changeChat(chatId) { chat ->
                        chat.copy(messages = chat.messages.map { if (it.id == assistant.id) it.copy(text = text, interrupted = false,
                            elapsedMs = android.os.SystemClock.elapsedRealtime() - started, firstTokenMs = firstToken) else it })
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                markAccess(profile, (error as? ApiException)?.access ?: AccessState.ERROR)
                withContext(Dispatchers.IO) { diagnostics.record("Chat request", profile.provider, error) }
                if (generationToken == assistant.id) changeChat(chatId) { chat ->
                    chat.copy(messages = chat.messages.filterNot { it.id == assistant.id && it.text.isBlank() } +
                        ChatMessage(text = (error as? ApiException)?.message ?: "Could not complete this reply. Try again.", role = ChatMessage.Role.ERROR))
                }
            } finally {
                if (generationToken == assistant.id) {
                    generationToken = null
                    mutable.update { it.copy(generatingId = null) }
                    persist()
                }
            }
        }
    }

    fun stop() {
        generationToken = null
        generation?.cancel()
        generation = null
        val id = mutable.value.generatingId
        mutable.update { it.copy(generatingId = null) }
        if (id != null) changeChat(id) { chat ->
            chat.copy(messages = chat.messages.map {
                if (it.role == ChatMessage.Role.ASSISTANT && it.text.isEmpty()) it.copy(text = "Reply stopped.", interrupted = true) else it
            })
        }
    }

    fun attach(uri: Uri) {
        val value = mutable.value
        val chat = value.active ?: return
        if (value.preparing || value.generatingId != null) return
        if (chat.attachments.size >= 4) { notice("You can attach up to four files per message."); return }
        mutable.update { it.copy(preparing = true) }
        attachmentJob = viewModelScope.launch {
            try {
                val attachment = AttachmentReader.read(getApplication(), uri)
                changeChat(chat.id) { it.copy(attachments = it.attachments + attachment) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                withContext(Dispatchers.IO) { diagnostics.record("Attachment", error = error) }
                notice(if (error is IllegalArgumentException || error is IllegalStateException) error.message ?: "Could not prepare this file." else "Could not prepare this file. Try another file.")
            } finally { mutable.update { it.copy(preparing = false) } }
        }
    }
    private fun cancelAttachment() { attachmentJob?.cancel(); attachmentJob = null; mutable.update { it.copy(preparing = false) } }
    fun removeAttachment(id: String) = changeChat { it.copy(attachments = it.attachments.filterNot { file -> file.id == id }) }
}
