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
        val storageBlocked: Boolean = false
    ) {
        val active: Conversation? get() = chats.firstOrNull { it.id == activeId }
    }

    private val store = LocalStore(application)
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
            } catch (_: Exception) {
                // Never overwrite a history/credential file that could not be read.
                mutable.value = State(loading = false, storageBlocked = true,
                    notice = "Local data could not be opened. Restart the app to retry; existing files have been preserved.")
            }
        }
        viewModelScope.launch {
            for ((chats, active) in saves) {
                try { withContext(Dispatchers.IO) { store.saveHistory(chats, active) } }
                catch (_: Exception) { notice("Could not save chats. Free some device storage, then try again.") }
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

    fun draft(text: String) = changeChat { it.copy(draft = text.take(120_000)) }
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

    suspend fun saveSettings(settings: AppSettings): Boolean {
        if (mutable.value.storageBlocked) return false
        return try {
            withContext(Dispatchers.IO) { store.saveSettings(settings) }
            mutable.update { it.copy(settings = settings) }
            true
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { notice("Could not securely save provider settings. Your previous settings are unchanged."); false }
    }

    fun send() {
        val value = mutable.value
        val chat = value.active ?: return
        if (value.generatingId != null || value.preparing || value.storageBlocked || (chat.draft.isBlank() && chat.attachments.isEmpty())) return
        FreeLlmApiClient.validate(value.settings.active)?.let { notice(it); return }
        if (!value.settings.active.vision && chat.attachments.any { it.isImage }) {
            notice("Enable image input for a vision-capable model in settings before sending images."); return
        }
        val message = ChatMessage(text = chat.draft.trim(), role = ChatMessage.Role.USER, attachments = chat.attachments)
        val history = chat.messages + message
        changeChat { it.copy(messages = history, draft = "", attachments = emptyList(),
            title = if (it.messages.isEmpty()) message.text.ifBlank { message.attachments.first().name }.take(60) else it.title,
            updatedAt = System.currentTimeMillis()) }
        generate(chat.id, history, value.settings.active)
    }

    fun retry() {
        val value = mutable.value
        val chat = value.active ?: return
        if (value.generatingId != null) return
        val userIndex = chat.messages.indexOfLast { it.role == ChatMessage.Role.USER }
        if (userIndex < 0) return
        FreeLlmApiClient.validate(value.settings.active)?.let { notice(it); return }
        val history = chat.messages.take(userIndex + 1)
        changeChat { it.copy(messages = history) }
        generate(chat.id, history, value.settings.active)
    }

    /** The UI confirms replacement of the selected turn and its following replies. */
    fun edit(message: ChatMessage) {
        if (mutable.value.generatingId != null) return
        changeChat { chat ->
            val index = chat.messages.indexOfFirst { it.id == message.id }
            if (index < 0) chat else chat.copy(messages = chat.messages.take(index), draft = message.text, attachments = message.attachments)
        }
    }

    private fun generate(chatId: String, history: List<ChatMessage>, profile: ProviderProfile) {
        val assistant = ChatMessage(text = "", role = ChatMessage.Role.ASSISTANT, model = "${profile.provider.label} · ${profile.model}", interrupted = true)
        generationToken = assistant.id
        mutable.update { it.copy(generatingId = chatId) }
        changeChat(chatId) { it.copy(messages = history + assistant) }
        generation = viewModelScope.launch {
            try {
                var lastCheckpoint = 0L
                val text = client.generate(profile, history) { partial ->
                    // Callback is on an OkHttp worker. All writes are marshalled to the ViewModel's main dispatcher.
                    viewModelScope.launch {
                        if (generationToken == assistant.id) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            val checkpoint = now - lastCheckpoint >= 2000
                            if (checkpoint) lastCheckpoint = now
                            changeChat(chatId, save = checkpoint) { chat ->
                                chat.copy(messages = chat.messages.map { if (it.id == assistant.id) it.copy(text = partial) else it })
                            }
                        }
                    }
                }
                if (generationToken == assistant.id) changeChat(chatId) { chat ->
                    chat.copy(messages = chat.messages.map { if (it.id == assistant.id) it.copy(text = text, interrupted = false) else it })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
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
                notice(if (error is IllegalArgumentException || error is IllegalStateException) error.message ?: "Could not prepare this file." else "Could not prepare this file. Try another file.")
            } finally { mutable.update { it.copy(preparing = false) } }
        }
    }
    private fun cancelAttachment() { attachmentJob?.cancel(); attachmentJob = null; mutable.update { it.copy(preparing = false) } }
    fun removeAttachment(id: String) = changeChat { it.copy(attachments = it.attachments.filterNot { file -> file.id == id }) }
}
