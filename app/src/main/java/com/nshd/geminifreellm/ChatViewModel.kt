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
        val checking: Set<String> = emptySet(),
        val autoChecking: Boolean = false,
        val autoStatus: String = "Discover models, then check which can respond.",
        val autoChoice: String? = null
    ) {
        val active: Conversation? get() = chats.firstOrNull { it.id == activeId }
    }

    val diagnostics = (application as FreeLlmApplication).diagnostics
    private val store = LocalStore(application)
    private val settingsMutex = Mutex()
    private val catalogJobs = mutableMapOf<Provider, Job>()
    private val catalogTokens = mutableMapOf<Provider, String>()
    private var draftSave: Job? = null
    private var autoCheckJob: Job? = null
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
        val token = newId()
        catalogTokens[provider] = token
        catalogJobs[provider] = viewModelScope.launch {
            try {
                val models = client.catalog(profile)
                if (catalogTokens[provider] == token) mutable.update { it.copy(catalogs = it.catalogs + (provider to ModelCatalog(models, fetchedAt = System.currentTimeMillis()))) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (catalogTokens[provider] == token) mutable.update { it.copy(catalogs = it.catalogs + (provider to (cached ?: ModelCatalog()).copy(loading = false,
                    error = (error as? ApiException)?.message ?: "Could not load models. Retry or enter an ID manually."))) }
                withContext(Dispatchers.IO) { diagnostics.record("Model catalog", provider, error) }
            } finally {
                if (catalogTokens[provider] == token) mutable.update { value -> value.copy(catalogs = value.catalogs[provider]?.let {
                    value.catalogs + (provider to it.copy(loading = false))
                } ?: value.catalogs) }
            }
        }
    }

    suspend fun selectModel(provider: Provider, model: String): Boolean {
        val profile = mutable.value.settings.profiles.firstOrNull { it.provider == provider } ?: ProviderProfile(provider)
        FreeLlmApiClient.validate(profile.copy(model = model.trim()))?.let { notice(it); return false }
        return updateSettings { current -> current.copy(selected = provider, autoRouting = false,
            profiles = current.profiles.filterNot { it.provider == provider } + profile.copy(model = model.trim())) }
    }

    suspend fun setAutoEnabled(enabled: Boolean): Boolean {
        if (mutable.value.generatingId != null) { notice("Stop the current reply before changing Auto mode."); return false }
        stopAutoCheck()
        autoCheckJob?.join()
        val saved = updateSettings { it.copy(autoRouting = enabled) }
        if (saved) {
            if (enabled) checkAutoModels()
            else {
                catalogJobs.values.forEach { it.cancel() }
                mutable.update { it.copy(autoChoice = null, autoStatus = "Auto is off. Your saved manual model is selected.",
                    catalogs = it.catalogs.mapValues { entry -> entry.value.copy(loading = false) }) }
            }
        }
        return saved
    }

    fun refreshAutoCatalogs(force: Boolean = false) {
        mutable.value.settings.profiles.filter { FreeLlmApiClient.validate(it, requireModel = false) == null }
            .forEach { loadCatalog(it.provider, force) }
    }

    private suspend fun awaitAutoCatalogs() {
        refreshAutoCatalogs()
        val jobs = mutable.value.settings.profiles.mapNotNull { catalogJobs[it.provider] }
        withTimeoutOrNull(8_000) { jobs.joinAll() }
    }

    fun stopAutoCheck() { autoCheckJob?.cancel() }

    fun checkAutoModels() {
        if (!mutable.value.settings.autoRouting || mutable.value.autoChecking || mutable.value.checking.isNotEmpty() || mutable.value.generatingId != null) return
        mutable.update { it.copy(autoChecking = true, autoChoice = null, autoStatus = "Discovering models from configured providers…") }
        autoCheckJob = viewModelScope.launch {
            try {
                awaitAutoCatalogs()
                val snapshot = mutable.value
                val history = snapshot.active?.messages.orEmpty() + ChatMessage(text = snapshot.active?.draft.orEmpty(), role = ChatMessage.Role.USER,
                    attachments = snapshot.active?.attachments.orEmpty())
                val candidates = AutoRouter.candidates(snapshot.settings, snapshot.catalogs, snapshot.access, history)
                var started = 0L
                val (profile, _) = AutoRouter.generate(candidates, firstTokenTimeoutMs = 15_000,
                    onAttempt = { selected, attempt ->
                        started = android.os.SystemClock.elapsedRealtime()
                        mutable.update { it.copy(checking = setOf(modelKey(selected.provider, selected.model)),
                            autoStatus = "Checking $attempt/${minOf(6, candidates.size)} · ${selected.provider.label} · ${selected.model}") }
                    }, onFailure = { failed, error ->
                        markAccess(failed, (error as? ApiException)?.access ?: AccessState.ERROR)
                        withContext(Dispatchers.IO) { diagnostics.record("Auto access check", failed.provider, error) }
                    }, request = { selected, emit ->
                        withTimeoutOrNull(15_000) {
                            client.generate(selected, listOf(ChatMessage(text = "Reply with OK only.", role = ChatMessage.Role.USER)), onText = emit)
                        } ?: throw ApiException("Access check timed out.", AccessState.UNAVAILABLE)
                    }, onText = {})
                markAccess(profile, AccessState.USABLE, android.os.SystemClock.elapsedRealtime() - started)
                mutable.update { it.copy(autoChoice = "${profile.provider.label} · ${profile.model}",
                    autoStatus = "Ready. Auto re-evaluates for each message and switches when a model fails.") }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(autoStatus = "Check stopped. Completed results are kept.") }; throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(autoStatus = (error as? ApiException)?.message ?: "Could not check models. Review connections and retry.") }
            } finally { mutable.update { it.copy(autoChecking = false, checking = emptySet()) } }
        }
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
        if (mutable.value.autoChecking || key in mutable.value.checking || mutable.value.checking.isNotEmpty()) return
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

    private fun markAccess(profile: ProviderProfile, access: AccessState, latencyMs: Long = 0) {
        val configured = mutable.value.settings.profiles.firstOrNull { it.provider == profile.provider } ?: ProviderProfile(profile.provider)
        if (configured.apiKey == profile.apiKey && configured.baseUrl == profile.baseUrl)
            mutable.update { it.copy(access = it.access + (modelKey(profile.provider, profile.model) to ModelAccess(access, latencyMs = latencyMs.coerceAtLeast(0))),
                autoChoice = if (access != AccessState.USABLE && it.autoChoice == "${profile.provider.label} · ${profile.model}") null else it.autoChoice) }
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
            if (changed.isNotEmpty() || old.autoExcluded != settings.autoExcluded || (old.autoRouting && !settings.autoRouting)) stopAutoCheck()
            changed.forEach { catalogTokens.remove(it); catalogJobs.remove(it)?.cancel() }
            mutable.update { value -> value.copy(settings = settings,
                autoChoice = if (changed.isNotEmpty() || old.autoExcluded != settings.autoExcluded) null else value.autoChoice,
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
        stopAutoCheck()
        var profile = snapshot.settings.active
        val systemPrompt = snapshot.settings.agent.prompt()
        val assistant = ChatMessage(text = "", role = ChatMessage.Role.ASSISTANT,
            model = if (snapshot.settings.autoRouting) "Auto · discovering available models…" else "${profile.provider.label} · ${profile.model}", interrupted = true)
        val token = assistant.id
        generationToken = token
        mutable.update { it.copy(generatingId = chatId) }
        changeChat(chatId) { it.copy(messages = history + assistant) }
        generation = viewModelScope.launch {
            var replyId = assistant.id
            var requestHistory = history
            try {
                autoCheckJob?.join()
                if (snapshot.settings.autoRouting) awaitAutoCatalogs()
                val candidates = requestCandidates(snapshot.copy(catalogs = mutable.value.catalogs, access = mutable.value.access), history)
                if (candidates.isEmpty()) throw ApiException("Auto found no eligible model. Open Auto to check provider connections and cooldowns.")
                var started = android.os.SystemClock.elapsedRealtime()
                var firstToken = 0L
                var lastCheckpoint = started
                val updates = Channel<Pair<String, String>>(Channel.CONFLATED)
                val collector = launch {
                    for ((id, partial) in updates) {
                        if (generationToken == token && id == replyId) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (firstToken == 0L && partial.isNotEmpty()) firstToken = (now - started).coerceAtLeast(1)
                            val checkpoint = now - lastCheckpoint >= 2000
                            if (checkpoint) lastCheckpoint = now
                            changeChat(chatId, save = checkpoint) { chat ->
                                chat.copy(messages = chat.messages.map { if (it.id == id) it.copy(text = partial) else it })
                            }
                        }
                    }
                }
                val text = try {
                    if (snapshot.settings.autoRouting) AutoRouter.generate(candidates,
                        onAttempt = { selected, attempt ->
                            profile = selected
                            started = android.os.SystemClock.elapsedRealtime(); firstToken = 0L
                            changeChat(chatId, save = false) { chat -> chat.copy(messages = chat.messages.map {
                                if (it.id == replyId) it.copy(model = "Auto · ${selected.provider.label} · ${selected.model}" + if (attempt > 1) " · fallback $attempt" else "") else it
                            }) }
                        }, onFailure = { failed, error ->
                            markAccess(failed, (error as? ApiException)?.access ?: AccessState.ERROR)
                            withContext(Dispatchers.IO) { diagnostics.record("Auto fallback", failed.provider, error) }
                        }, request = { selected, emit ->
                            val id = replyId
                            client.generate(selected, requestHistory, systemPrompt) { partial -> updates.trySend(id to partial); emit(partial) }
                        }, onText = {}, onPartialFailure = { partial ->
                            // Persist the completed portion under its original model before starting a new bubble.
                            val previousId = replyId
                            val next = ChatMessage(text = "", role = ChatMessage.Role.ASSISTANT, model = "Auto · finding a model to continue…", interrupted = true)
                            replyId = next.id
                            changeChat(chatId) { chat -> chat.copy(messages = chat.messages.map {
                                if (it.id == previousId) it.copy(text = partial, interrupted = true) else it
                            } + next) }
                            requestHistory = requestHistory + ChatMessage(text = partial, role = ChatMessage.Role.ASSISTANT) +
                                ChatMessage(text = "The previous reply was interrupted. Continue from its last unfinished point, preserve the user's requirements, and avoid repeating completed content.", role = ChatMessage.Role.USER)
                        }).second
                    else client.generate(profile, history, systemPrompt) { updates.trySend(replyId to it) }
                } finally { updates.close(); collector.join() }
                if (generationToken == token) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - started
                    markAccess(profile, AccessState.USABLE, firstToken.takeIf { it > 0 } ?: elapsed)
                    if (snapshot.settings.autoRouting) mutable.update { it.copy(autoChoice = "${profile.provider.label} · ${profile.model}") }
                    changeChat(chatId) { chat ->
                        chat.copy(messages = chat.messages.map { if (it.id == replyId) it.copy(text = text, interrupted = false,
                            elapsedMs = elapsed, firstTokenMs = firstToken) else it })
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!snapshot.settings.autoRouting) markAccess(profile, (error as? ApiException)?.access ?: AccessState.ERROR)
                withContext(Dispatchers.IO) { diagnostics.record("Chat request", profile.provider, error) }
                if (generationToken == token) changeChat(chatId) { chat ->
                    chat.copy(messages = chat.messages.filterNot { it.id == replyId && it.text.isBlank() } +
                        ChatMessage(text = (error as? ApiException)?.message ?: "Could not complete this reply. Try again.", role = ChatMessage.Role.ERROR))
                }
            } finally {
                if (generationToken == token) {
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
        if (mutable.value.generatingId != null) catalogJobs.values.forEach { it.cancel() }
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
