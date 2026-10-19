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

data class ConnectionTestResult(val models: List<String>?, val message: String, val error: Boolean)

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
    private val historyMutex = Mutex()
    private val catalogJobs = mutableMapOf<Provider, Job>()
    private val catalogTokens = mutableMapOf<Provider, String>()
    /** Catalog cache validity depends on the concrete endpoint, not just its provider kind. */
    private val catalogProfileIds = mutableMapOf<Provider, String>()
    private var draftSave: Job? = null
    private var autoCheckJob: Job? = null
    val client = FreeLlmApiClient()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var generation: Job? = null
    private var attachmentJob: Job? = null
    private var generationToken: String? = null
    private val saves = Channel<Triple<Int, List<Conversation>, String>>(Channel.CONFLATED)
    private var persistEpoch = 0
    private var restoringBackup = false

    init {
        reload()
        viewModelScope.launch {
            for ((epoch, chats, active) in saves) {
                // A restore bumps the epoch; stale queued saves must not overwrite it.
                if (epoch != persistEpoch) continue
                try {
                    historyMutex.withLock {
                        // A restore may have started while this save was waiting for the lock.
                        if (epoch == persistEpoch) withContext(Dispatchers.IO) { store.saveHistory(chats, active) }
                    }
                } catch (error: Exception) {
                    withContext(Dispatchers.IO) { diagnostics.record("Storage", error = error) }
                    notice("Could not save chats. Free some device storage, then try again.")
                }
            }
        }
    }

    /** (Re)loads settings and history from disk; used at startup and after a backup restore. */
    fun reload() {
        persistEpoch++
        generation?.cancel(); generation = null
        viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val (settings, history) = withContext(Dispatchers.IO) { store.loadSettings() to store.loadHistory() }
                val chats = history.first.ifEmpty { listOf(Conversation()) }
                mutable.update { it.copy(loading = false, settings = settings, chats = chats,
                    activeId = history.second?.takeIf { id -> chats.any { it.id == id } } ?: chats.first().id,
                    generatingId = null, storageBlocked = false) }
                if (settings.autoRouting) settings.profiles.filter { it.provider !in settings.autoExcluded }.forEach { loadCatalog(it.provider) }
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { diagnostics.record("Storage", error = error) }
                // Never overwrite a history/credential file that could not be read.
                mutable.update { it.copy(loading = false, storageBlocked = true,
                    notice = "Local data could not be opened. Restart the app to retry; existing files have been preserved.") }
            }
        }
    }

    private fun persist() {
        val value = mutable.value
        if (!restoringBackup && !value.loading && !value.storageBlocked) saves.trySend(Triple(persistEpoch, value.chats, value.activeId))
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
        val profile = current.settings.active.takeIf { current.settings.selected == provider }
            ?: current.settings.profiles.firstOrNull { it.provider == provider }
            ?: ProviderProfile(provider)
        val cached = current.catalogs[provider]
        val sameEndpoint = catalogProfileIds[provider] == profile.id
        if (catalogJobs[provider]?.isActive == true && sameEndpoint) return
        if (catalogJobs[provider]?.isActive == true && !sameEndpoint) catalogJobs[provider]?.cancel()
        if (!force && sameEndpoint && cached != null && cached.fetchedAt > System.currentTimeMillis() - 300_000) return
        catalogProfileIds[provider] = profile.id
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

    /** Activate a saved connection without replacing its URL, key, model, or capabilities. */
    suspend fun selectProfile(profileId: String): Boolean {
        if (mutable.value.settings.profiles.none { it.id == profileId }) {
            notice("That saved connection no longer exists. Refresh settings and try again.")
            return false
        }
        return updateSettings { settings -> settings.withActiveProfile(profileId) ?: settings }
    }

    suspend fun selectModel(provider: Provider, model: String): Boolean {
        val current = mutable.value.settings
        val profile = current.active.takeIf { current.selected == provider }
            ?: current.profiles.firstOrNull { it.provider == provider }
            ?: ProviderProfile(provider)
        val updated = profile.copy(model = model.trim())
        FreeLlmApiClient.validate(updated)?.let { notice(it); return false }
        return updateSettings { settings ->
            val exists = settings.profiles.any { it.id == updated.id }
            settings.copy(selected = provider, activeProfileId = updated.id, autoRouting = false,
                profiles = if (exists) settings.profiles.map { if (it.id == updated.id) updated else it }
                    else settings.profiles + updated)
        }
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
                val candidates = AutoRouter.candidates(snapshot.settings, snapshot.catalogs, snapshot.access, history).take(8)
                if (candidates.isEmpty()) {
                    mutable.update { it.copy(autoStatus = "Auto found no eligible model. Open Auto to check provider connections and cooldowns.") }
                } else {
                    // All candidates probe concurrently; a slow probe stays eligible (marked slow, not broken).
                    mutable.update { it.copy(checking = candidates.map { modelKey(it.provider, it.model) }.toSet(),
                        autoStatus = "Checking ${candidates.size} models in parallel…") }
                    val results = AutoRouter.probe(candidates, request = { selected, emit ->
                        client.generate(selected, listOf(ChatMessage(text = "Reply with OK only.", role = ChatMessage.Role.USER)), onText = emit)
                    })
                    var usable = 0
                    var best: AutoRouter.ProbeResult? = null
                    results.forEach { result ->
                        if (result.state == AccessState.USABLE) {
                            usable++
                            if (best == null) best = result
                            markAccess(result.profile, AccessState.USABLE, result.latencyMs)
                        } else {
                            markAccess(result.profile, result.state)
                            result.error?.let { error -> withContext(Dispatchers.IO) { diagnostics.record("Auto access check", result.profile.provider, error) } }
                        }
                    }
                    mutable.update { it.copy(autoChoice = best?.let { "${it.profile.provider.label} · ${it.profile.model}" },
                        autoStatus = if (usable > 0) "Ready · $usable/${results.size} models responding. Auto re-evaluates for each message and switches when a model fails."
                        else "No model responded. Check provider connections, or wait out rate-limit cooldowns.") }
                }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(autoStatus = "Check stopped. Completed results are kept.") }; throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(autoStatus = (error as? ApiException)?.message ?: "Could not check models. Review connections and retry.") }
            } finally { mutable.update { it.copy(autoChecking = false, checking = emptySet()) } }
        }
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

    suspend fun testConnection(profile: ProviderProfile): ConnectionTestResult {
        try {
            var keyNote: String? = null
            val probeProfile = profile.copy(model = profile.model.ifBlank { profile.provider.defaultModel })
            try {
                client.generate(probeProfile, listOf(ChatMessage(text = "Reply with OK only.", role = ChatMessage.Role.USER))) {}
            } catch (auth: ApiException) {
                when (auth.access) {
                    AccessState.AUTH -> throw ApiException("The API key was rejected. Check the key for ${profile.provider.label}.", auth.access, auth.statusCode)
                    AccessState.MODEL_REJECTED -> throw ApiException(
                        "The key may be valid, but this account or model is not permitted. Check model access and provider permissions.",
                        auth.access, auth.statusCode)
                    AccessState.QUOTA -> throw ApiException(
                        "The key works, but this provider's quota or balance is exhausted.", auth.access, auth.statusCode)
                    AccessState.UNAVAILABLE -> keyNote =
                        "Key looks OK, but the model \"${probeProfile.model}\" was not found — check the model ID and base URL."
                    AccessState.RATE_LIMITED -> keyNote = "Key accepted, but the provider is rate-limiting right now."
                    else -> throw auth
                }
            }
            val models = try {
                client.models(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val message = buildString {
                append(if (keyNote == null) "Connected · API key verified" else "Connected · $keyNote")
                if (models.isNullOrEmpty()) append(" · no model catalog here; enter a model ID manually.")
                else append(" · ${models.size} models returned. Select one below.")
            }
            return ConnectionTestResult(models, message, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            withContext(Dispatchers.IO) { diagnostics.record("Connection test", profile.provider, failure) }
            return ConnectionTestResult(null, (failure as? ApiException)?.message ?: "Connection test failed.", true)
        }
    }

    fun checkModel(provider: Provider, model: String) {
        val current = mutable.value.settings
        val profile = (current.active.takeIf { current.selected == provider }
            ?: current.profiles.firstOrNull { it.provider == provider } ?: ProviderProfile(provider)).copy(model = model)
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
        val configured = mutable.value.settings.profiles.firstOrNull { it.id == profile.id }
            ?: mutable.value.settings.profiles.firstOrNull { it.provider == profile.provider }
            ?: ProviderProfile(profile.provider)
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

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { BackupManager.export(getApplication(), uri) }
                notice("Backup saved.")
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { diagnostics.record("Backup", error = error) }
                notice(if (error is BackupException) error.message ?: "Export failed." else "Could not write the backup file.")
            }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            // Invalidate queued snapshots and stop producers before touching the files they
            // persist. The mutex also waits for an already-running synchronous disk write.
            persistEpoch++
            restoringBackup = true
            mutable.update { it.copy(loading = true, generatingId = null, preparing = false) }
            draftSave?.cancel()
            draftSave = null
            generation?.cancel()
            val pendingAttachment = attachmentJob
            pendingAttachment?.cancel()
            try {
                generation?.join()
                pendingAttachment?.join()
                val credentialsSkipped = settingsMutex.withLock {
                    historyMutex.withLock {
                        withContext(Dispatchers.IO) { BackupManager.import(getApplication(), uri) }
                    }
                }
                reload()
                notice(if (credentialsSkipped)
                    "Backup restored, but its API keys belong to another installation. Enter your API keys again."
                else "Backup restored.")
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(loading = false) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(loading = false) }
                withContext(Dispatchers.IO) { diagnostics.record("Backup", error = error) }
                notice(if (error is BackupException) error.message ?: "Could not read the backup file." else "Restore failed.")
            } finally {
                restoringBackup = false
            }
        }
    }

    private suspend fun updateSettings(transform: (AppSettings) -> AppSettings): Boolean = settingsMutex.withLock {
        if (mutable.value.storageBlocked) return@withLock false
        val old = mutable.value.settings
        val settings = transform(old).let { value -> value.copy(profiles = value.profiles.map { profile ->
            profile.copy(baseUrl = FreeLlmApiClient.normalizeBaseUrl(profile.baseUrl, profile.provider.protocol), apiKey = profile.apiKey.trim(), model = profile.model.trim())
        }) }
        try {
            withContext(Dispatchers.IO) { store.saveSettings(settings) }
            val changed = settings.profiles.filter { profile -> old.profiles.firstOrNull { it.id == profile.id }?.let {
                it.apiKey != profile.apiKey || it.baseUrl != profile.baseUrl
            } ?: true }.map { it.provider }.toSet()
            // Catalogs/access for CUSTOM are endpoint-dependent. Switching between two saved
            // compatible endpoints must never keep the previous endpoint's model list visible.
            val activeProfileChanged = old.selected != settings.selected || old.active.id != settings.active.id
            val invalidatedProviders = changed + if (activeProfileChanged) setOfNotNull(
                old.selected.takeIf { it == Provider.CUSTOM },
                settings.selected.takeIf { it == Provider.CUSTOM }
            ) else emptySet()
            if (invalidatedProviders.isNotEmpty() || old.autoExcluded != settings.autoExcluded || (old.autoRouting && !settings.autoRouting)) stopAutoCheck()
            invalidatedProviders.forEach {
                catalogTokens.remove(it)
                catalogProfileIds.remove(it)
                catalogJobs.remove(it)?.cancel()
            }
            mutable.update { value -> value.copy(settings = settings,
                autoChoice = if (invalidatedProviders.isNotEmpty() || old.autoExcluded != settings.autoExcluded) null else value.autoChoice,
                catalogs = value.catalogs.filterKeys { it !in invalidatedProviders },
                access = value.access.filterKeys { key -> invalidatedProviders.none { key.startsWith("${it.name}/") } }) }
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
        // "/SkillName …" invokes a saved skill for this message only. The bubble keeps
        // the token so the invocation stays visible; the API sees clean text plus
        // the skill in the system prompt.
        val (invokedSkill, cleanDraft) = AgentConfig.parseInvocation(chat.draft, value.settings.agent)
        val message = ChatMessage(text = chat.draft.trim(), role = ChatMessage.Role.USER, attachments = chat.attachments)
        val history = chat.messages + message
        val apiHistory = if (invokedSkill != null) chat.messages + message.copy(text = cleanDraft.trim()) else history

        changeChat { it.copy(messages = history, draft = "", attachments = emptyList(),
            title = if (it.messages.isEmpty()) message.text.ifBlank { message.attachments.first().name }.take(60) else it.title,
            updatedAt = System.currentTimeMillis()) }
        generate(chat.id, history, value, invokedSkill, apiHistory)
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
        val lastUser = history.last()
        val (invokedSkill, cleanText) = AgentConfig.parseInvocation(lastUser.text, value.settings.agent)
        val apiHistory = if (invokedSkill != null) {
            history.dropLast(1) + lastUser.copy(text = cleanText.trim())
        } else history

        changeChat { it.copy(messages = history) }
        generate(chat.id, history, value, invokedSkill, apiHistory)
    }

    /** The UI confirms replacement of the selected turn and its following replies. */
    fun edit(message: ChatMessage) {
        if (mutable.value.generatingId != null) return
        changeChat { chat ->
            val index = chat.messages.indexOfFirst { it.id == message.id }
            if (index < 0) chat else chat.copy(messages = chat.messages.take(index), draft = message.text, attachments = message.attachments)
        }
    }

    private fun generate(chatId: String, history: List<ChatMessage>, snapshot: State, invokedSkill: AgentSkill? = null,
                         apiHistory: List<ChatMessage>? = null) {
        stopAutoCheck()
        var profile = snapshot.settings.active
        val systemPrompt = snapshot.settings.agent.promptFor(invokedSkill)
        // apiHistory strips the "/SkillName" token so the model never sees it;
        // the chat bubble keeps it so the invocation stays visible.
        val request = apiHistory ?: history
        val assistant = ChatMessage(text = "", role = ChatMessage.Role.ASSISTANT,
            model = if (snapshot.settings.autoRouting) "Auto · discovering available models…" else "${profile.provider.label} · ${profile.model}", interrupted = true)
        val token = assistant.id
        generationToken = token
        mutable.update { it.copy(generatingId = chatId) }
        changeChat(chatId) { it.copy(messages = history + assistant) }
        generation = viewModelScope.launch {
            var replyId = assistant.id
            var requestHistory = request
            try {
                autoCheckJob?.join()
                if (snapshot.settings.autoRouting) awaitAutoCatalogs()
                val candidates = requestCandidates(snapshot.copy(catalogs = mutable.value.catalogs, access = mutable.value.access), request)
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
                    if (snapshot.settings.autoRouting) {
                        var continuationPrefix = ""
                        val (winner, reply) = AutoRouter.generate(candidates,
                            onAttempt = { selected, attempt ->
                                profile = selected
                                started = android.os.SystemClock.elapsedRealtime(); firstToken = 0L
                                changeChat(chatId, save = false) { chat -> chat.copy(messages = chat.messages.map {
                                    if (it.id == replyId) it.copy(model = "Auto · ${selected.provider.label} · ${selected.model}" +
                                        if (continuationPrefix.isNotEmpty()) " · continued" else if (attempt > 1) " · fallback $attempt" else "") else it
                                }) }
                            }, onFailure = { failed, error ->
                                markAccess(failed, (error as? ApiException)?.access ?: AccessState.ERROR)
                                withContext(Dispatchers.IO) { diagnostics.record("Auto fallback", failed.provider, error) }
                            }, onSlow = { slow, latencyMs ->
                                // Slow is not broken: stays eligible, just ranked lower next time.
                                markAccess(slow, AccessState.USABLE, latencyMs)
                            }, request = { selected, emit ->
                                val prefix = continuationPrefix
                                client.generate(selected, requestHistory, systemPrompt) { partial -> emit(prefix + partial) }
                            }, onText = { chunk -> updates.trySend(replyId to chunk) }, onPartialFailure = { partial ->
                                // Seamless: the same bubble keeps streaming; the next attempt's
                                // chunks are appended after the preserved prefix, with a
                                // continuation instruction in the request history.
                                // AutoRouter reports the full accumulated partial (including the
                                // existing prefix), not just the newly received suffix.
                                val added = partial.removePrefix(continuationPrefix)
                                continuationPrefix = partial
                                requestHistory = requestHistory + ChatMessage(text = added, role = ChatMessage.Role.ASSISTANT) +
                                    ChatMessage(text = "The previous reply was interrupted. Continue from its last unfinished point, preserve the user's requirements, and avoid repeating completed content.", role = ChatMessage.Role.USER)
                            })
                        profile = winner
                        continuationPrefix + reply
                    }
                    else client.generate(profile, requestHistory, systemPrompt) { updates.trySend(replyId to it) }
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
                // Re-resolve the active chat: the user may have switched chats
                // while the file was being prepared.
                val targetId = mutable.value.active?.id ?: return@launch
                changeChat(targetId) { it.copy(attachments = it.attachments + attachment) }
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
