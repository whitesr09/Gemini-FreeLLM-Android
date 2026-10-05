package com.nshd.geminifreellm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageManager
import android.app.KeyguardManager
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowInsetsControllerCompat
import com.nshd.geminifreellm.data.AppDiagnostics
import com.nshd.geminifreellm.data.AppSettings
import com.nshd.geminifreellm.data.StorageManager
import com.nshd.geminifreellm.data.BackupManager
import com.nshd.geminifreellm.data.DocumentExporter
import com.nshd.geminifreellm.data.DraftRepository
import com.nshd.geminifreellm.data.DocumentProcessor
import com.nshd.geminifreellm.data.DraftState
import com.nshd.geminifreellm.data.ExportFormat
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.data.MediaResult
import com.nshd.geminifreellm.data.ModelCache
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ChatSession
import com.nshd.geminifreellm.security.SecureCredentialStore
import com.nshd.geminifreellm.ui.AiNameOnboarding
import com.nshd.geminifreellm.ui.AppButton
import com.nshd.geminifreellm.ui.AppDialog
import com.nshd.geminifreellm.ui.AppTextButton
import com.nshd.geminifreellm.ui.BasicText
import com.nshd.geminifreellm.ui.AiTheme
import com.nshd.geminifreellm.ui.ChatScreen
import com.nshd.geminifreellm.ui.MediaPreviewDialog
import com.nshd.geminifreellm.ui.SettingsDialog
import com.nshd.geminifreellm.ui.ThemeMode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column

class MainActivity : ComponentActivity() {
    private var pausedAt: Long = 0L
    private var unlockPromptShowing = false
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun requestUnlock() {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isKeyguardSecure) {
            _locked.value = false
            unlockPromptShowing = false
            return
        }
        if (unlockPromptShowing) return
        unlockPromptShowing = true
        runCatching {
            startActivityForResult(
                keyguard.createConfirmDeviceCredentialIntent(
                    "Unlock FreeLLM AI",
                    "Confirm your device credential to open the app."
                ),
                4101
            )
        }.onFailure {
            unlockPromptShowing = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDiagnostics.install(this)
        AppDiagnostics.recordEvent(this, "app_started")
        pausedAt = System.currentTimeMillis()
        setContent { AiApp(this) }
    }

    override fun onPause() {
        pausedAt = System.currentTimeMillis()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.getBoolean("appLockEnabled", false)) return
        val timeout = prefs.getInt("appLockTimeoutMinutes", 5).coerceIn(1, 60)
        val elapsed = if (pausedAt == 0L) Long.MAX_VALUE else System.currentTimeMillis() - pausedAt
        if (elapsed >= timeout * 60_000L && !isFinishing && !unlockPromptShowing) {
            _locked.value = true
            requestUnlock()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4101) {
            unlockPromptShowing = false
            if (resultCode == Activity.RESULT_OK) _locked.value = false
        }
    }
}

@Composable
private fun AiApp(activity: MainActivity) {
    val context: Context = activity
    val locked by activity.locked.collectAsState()
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val secureCredentials = remember { SecureCredentialStore(context).also { it.migrateLegacy(prefs) } }
    val chatVm: ChatViewModel = viewModel()
    val chatState by chatVm.uiState.collectAsState()
    val client = remember { FreeLlmApiClient() }
    val scope = rememberCoroutineScope()
    val ids = remember { AtomicLong(System.currentTimeMillis()) }
    val draftRepository = remember { DraftRepository(context) }

    DisposableEffect(Unit) {
        onDispose { client.cancelActive() }
    }

    var baseUrl by remember {
        mutableStateOf(
            prefs.getString("baseUrl", "https://nshd-freellm-api.onrender.com/v1")
                ?: "https://nshd-freellm-api.onrender.com/v1"
        )
    }
    var apiKey by remember { mutableStateOf(secureCredentials.getApiKey()) }
    var aiName by remember { mutableStateOf(prefs.getString("aiName", "") ?: "") }
    var themeMode by remember {
        mutableStateOf(
            runCatching {
                ThemeMode.valueOf(
                    prefs.getString("theme", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
                )
            }.getOrDefault(ThemeMode.SYSTEM)
        )
    }
    var selectedModel by remember { mutableStateOf(prefs.getString("model", "auto") ?: "auto") }
    var diagnostics by remember { mutableStateOf(AppDiagnostics.selfCheck(context, baseUrl, apiKey.isNotBlank())) }

    val sessions = chatState.sessions
    val currentId = chatState.currentId
    val activeSession = sessions.firstOrNull { it.id == currentId }
        ?: sessions.firstOrNull()
        ?: remember { ChatSession("draft", "New chat", System.currentTimeMillis(), System.currentTimeMillis()) }

    var input by rememberSaveable { mutableStateOf("") }
    var pendingAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var mediaBusy by remember { mutableStateOf(false) }
    val busy = chatState.busy || mediaBusy
    var showSettings by remember { mutableStateOf(apiKey.isBlank() && aiName.isNotBlank()) }
    var showOnboarding by remember { mutableStateOf(aiName.isBlank()) }
    var generationMode by remember { mutableStateOf<String?>(null) }
    var generationPrompt by remember { mutableStateOf("") }
    var clearDialog by remember { mutableStateOf(false) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var renamingSession by remember { mutableStateOf<ChatSession?>(null) }
    var draftReady by rememberSaveable { mutableStateOf(false) }

    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) input = if (input.isBlank()) text else input.trimEnd() + " " + text
        }
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to FreeLLM AI")
        })
    }
    val tts = remember { TextToSpeech(context) {} }
    DisposableEffect(tts) { onDispose { tts.stop(); tts.shutdown() } }

    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    var pendingSaveName by remember { mutableStateOf<String?>(null) }
    var previewAttachment by remember { mutableStateOf<Attachment?>(null) }

    fun replaceSession(session: ChatSession, persistNow: Boolean = true) {
        chatVm.replaceSession(session, persistNow, selectedModel)
    }

    fun makeNewChat() = chatVm.newChat()

    fun speakText(text: String) {
        if (text.isBlank()) return
        tts.setSpeechRate(1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "freellm-read-aloud")
    }

    fun shareText(text: String) {
        if (text.isBlank()) return
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "Share response"))
    }

    fun shareAttachment(attachment: Attachment) {
        val file = File(attachment.localPath)
        if (!file.isFile) {
            Toast.makeText(context, "Media file is no longer available.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = attachment.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share media"))
        }.onFailure {
            Toast.makeText(context, it.message ?: "Share failed", Toast.LENGTH_LONG).show()
        }
    }

    fun copyText(text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("AI response", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    val saveLauncher = rememberLauncherForCreate(
        mimeType = "*/*",
        onResult = { uri ->
            val file = pendingSaveFile
            pendingSaveFile = null
            pendingSaveName = null
            if (uri != null && file != null) {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                file.inputStream().use { input -> input.copyTo(output) }
                            } ?: error("Couldn't open the destination.")
                        }
                    }.onFailure {
                        Toast.makeText(context, it.message ?: "Save failed", Toast.LENGTH_LONG).show()
                    }.onSuccess {
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    )

    val attachmentLauncher = rememberLauncherForMultipleDocuments { uris ->
        if (uris.isEmpty()) return@rememberLauncherForMultipleDocuments
        scope.launch {
            val prepared = uris.mapNotNull { uri ->
                runCatching {
                    withContext(Dispatchers.IO) { DocumentProcessor.copyToAppStorage(context, uri) }
                }.getOrNull()
            }
            val added = prepared.map { it.attachment }
            if (added.isNotEmpty()) {
                pendingAttachments = pendingAttachments + added
                if (chatState.settings.documentIndexEnabled && apiKey.isNotBlank()) {
                    prepared.forEach { item ->
                        chatVm.indexDocument(File(item.attachment.localPath), baseUrl, apiKey)
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForOpenDocument { uri ->
        if (uri == null) return@rememberLauncherForOpenDocument
        scope.launch {
            runCatching {
                val imported = withContext(Dispatchers.IO) {
                    val temp = File(context.cacheDir, "selected-backup.zip")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Couldn't read the backup.")
                        BackupManager.importBackup(context, temp)
                    } finally {
                        temp.delete()
                    }
                }
                chatVm.importSessions(imported.sessions, selectedModel)
            }.onSuccess {
                Toast.makeText(context, "Chats imported", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(activeSession.id) {
        draftReady = false
        input = withContext(Dispatchers.IO) { draftRepository.load(activeSession.id).text }
        draftReady = true
    }
    LaunchedEffect(activeSession.id, input, draftReady) {
        if (draftReady) {
            delay(250)
            withContext(Dispatchers.IO) { draftRepository.save(activeSession.id, DraftState(input)) }
        }
    }
    LaunchedEffect(baseUrl, apiKey) {
        chatVm.refreshModels(baseUrl, apiKey)
    }
    fun sendMessage() {
        if (busy || (input.isBlank() && pendingAttachments.isEmpty())) return
        chatVm.sendMessage(baseUrl, apiKey, selectedModel, input, pendingAttachments)
        draftRepository.clear(activeSession.id)
        input = ""
        pendingAttachments = emptyList()
    }

    fun regenerate(messageId: Long) {
        chatVm.regenerate(messageId, baseUrl, apiKey, selectedModel)
    }

    fun startGeneration(type: String) {
        if (busy) return
        generationMode = type
        generationPrompt = input
    }

    fun runGeneration(type: String, prompt: String) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isBlank()) return
        generationMode = null
        generationPrompt = ""
        input = ""
        mediaBusy = true

        scope.launch {
            val result = if (type == "image") {
                client.generateImage(context, baseUrl, apiKey, selectedModel, cleanPrompt)
            } else {
                client.generateVideo(context, baseUrl, apiKey, selectedModel, cleanPrompt)
            }

            val current = sessions.firstOrNull { it.id == activeSession.id } ?: activeSession
            when (result) {
                is MediaResult.Success -> {
                    val media = result.media
                    val attachment = Attachment(
                        id = ids.incrementAndGet().toString(),
                        name = media.displayName,
                        mimeType = media.mimeType,
                        localPath = media.file.absolutePath,
                        sizeBytes = media.file.length(),
                        kind = if (type == "image") Attachment.Kind.GENERATED_IMAGE else Attachment.Kind.GENERATED_VIDEO
                    )
                    replaceSession(
                        current.copy(
                            messages = current.messages + ChatMessage(
                                id = ids.incrementAndGet(),
                                text = if (type == "image") "Generated image for: " + cleanPrompt else "Generated video for: " + cleanPrompt,
                                role = ChatMessage.Role.ASSISTANT,
                                attachments = listOf(attachment)
                            ),
                            updatedAt = System.currentTimeMillis()
                        ),
                        persistNow = true
                    )
                }
                is MediaResult.Failure -> {
                    AppDiagnostics.recordEvent(context, "media_failure[" + type + "]: " + result.message)
                    replaceSession(
                        current.copy(
                            messages = current.messages + ChatMessage(
                                id = ids.incrementAndGet(),
                                text = result.message,
                                role = ChatMessage.Role.ERROR
                            ),
                            updatedAt = System.currentTimeMillis()
                        ),
                        persistNow = true
                    )
                }
            }
            mediaBusy = false
        }
    }

    fun requestSaveFile(file: File) {
        pendingSaveFile = file
        pendingSaveName = file.name
        saveLauncher.launch(file.name)
    }

    fun requestExport(message: ChatMessage, format: ExportFormat) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { DocumentExporter.renderText(message, format) }
            }.onSuccess { file ->
                requestSaveFile(file)
            }.onFailure {
                Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val settingsContent: @Composable () -> Unit = {
        if (showSettings && !locked) {
            SettingsDialog(
                settings = chatState.settings,
                apiKey = apiKey,
                appVersion = "2.0",
                storageUsage = chatState.storage,
                diagnostics = diagnostics,
                connectionTesting = chatState.connectionTesting,
                connectionResult = chatState.connectionResult,
                connectionError = chatState.connectionError,
                onTestConnection = { url, key -> chatVm.testConnection(url, key) },
                onSave = { settings, key ->
                    baseUrl = settings.baseUrl
                    apiKey = key
                    aiName = settings.aiName
                    selectedModel = settings.selectedModel
                    themeMode = runCatching { ThemeMode.valueOf(settings.theme) }.getOrDefault(ThemeMode.SYSTEM)
                    secureCredentials.setApiKey(apiKey)
                    chatVm.applySettings(settings)
                    prefs.edit()
                        .putString("baseUrl", settings.baseUrl)
                        .putString("aiName", settings.aiName)
                        .putString("model", settings.selectedModel)
                        .putString("theme", settings.theme)
                        .apply()
                    diagnostics = AppDiagnostics.selfCheck(context, settings.baseUrl, apiKey.isNotBlank())
                    AppDiagnostics.recordEvent(context, "settings_saved")
                    showSettings = false
                    showOnboarding = settings.aiName.isBlank()
                    chatVm.refreshModels(settings.baseUrl, apiKey)
                },
                onDismiss = {
                    if (apiKey.isNotBlank() && aiName.isNotBlank()) showSettings = false
                },
                onExportChats = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { BackupManager.createBackup(context, sessions) }
                        }.onSuccess { file -> requestSaveFile(file) }
                            .onFailure { Toast.makeText(context, it.message ?: "Backup export failed", Toast.LENGTH_LONG).show() }
                    }
                },
                onImportChats = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onClearChats = { clearDialog = true },
                onExportDiagnostics = {
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                AppDiagnostics.exportReport(context, baseUrl, apiKey.isNotBlank(), sessions.size)
                            }
                        }.onSuccess { file -> requestSaveFile(file) }
                            .onFailure { Toast.makeText(context, it.message ?: "Diagnostics export failed", Toast.LENGTH_LONG).show() }
                    }
                },
                onMaintenance = {
                    scope.launch {
                        withContext(Dispatchers.IO) { AppDiagnostics.maintenance(context) }
                        chatVm.cleanupOrphans()
                        chatVm.storageSnapshot()
                        diagnostics = AppDiagnostics.selfCheck(context, baseUrl, apiKey.isNotBlank())
                        Toast.makeText(context, "Maintenance completed", Toast.LENGTH_SHORT).show()
                    }
                },
                onClearCrashReport = {
                    AppDiagnostics.clearPreviousCrash(context)
                    diagnostics = AppDiagnostics.selfCheck(context, baseUrl, apiKey.isNotBlank())
                },
                onClearCache = {
                    com.nshd.geminifreellm.data.StorageManager.clearCache(context)
                    chatVm.storageSnapshot()
                    Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                },
                onClearDocumentIndex = {
                    chatVm.clearDocumentIndex()
                    Toast.makeText(context, "Document index cleared", Toast.LENGTH_SHORT).show()
                },
                onAppLock = { enabled ->
                    prefs.edit().putBoolean("appLockEnabled", enabled).apply()
                }
            )
        }
    }
    AiTheme(themeMode, chatState.settings.animationsEnabled) {
        val activity = context as? Activity
        val systemDark = isSystemInDarkTheme()
        val colors = com.nshd.geminifreellm.ui.LocalAppColors.current
        androidx.compose.runtime.SideEffect {
            activity?.window?.statusBarColor = colors.background.toArgb()
            activity?.window?.navigationBarColor = colors.background.toArgb()
            activity?.window?.let { window ->
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                val lightBars = when (themeMode) {
                    ThemeMode.LIGHT -> true
                    ThemeMode.DARK, ThemeMode.AMOLED -> false
                    ThemeMode.SYSTEM -> !systemDark
                }
                controller.isAppearanceLightStatusBars = lightBars
                controller.isAppearanceLightNavigationBars = lightBars
            }
        }
        ChatScreen(
            aiName = aiName.ifBlank { "Assistant" },
            session = activeSession,
            sessions = sessions,
            selectedModel = selectedModel,
            models = chatState.models,
            input = input,
            pendingAttachments = pendingAttachments,
            busy = busy,
            webSearchEnabled = chatState.webSearchEnabled,
            localToolsEnabled = chatState.localToolsEnabled,
            contextLabel = if (chatState.contextUsedChars > 0) com.nshd.geminifreellm.data.ContextManager.contextLabel(chatState.contextUsedChars, chatState.contextLimit) else "",
            onInputChange = { input = it },
            onSuggestedPrompt = { input = it },
            onSend = ::sendMessage,
            onNewChat = ::makeNewChat,
            onNewTemporaryChat = { chatVm.newChat(true) },
            onSelectSession = { id ->
                if (!busy) chatVm.selectSession(id)
            },
            onSettings = { showSettings = true },
            onStop = { chatVm.stopGeneration() },
            onAttach = { attachmentLauncher.launch(arrayOf("*/*")) },
            onVoice = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to FreeLLM AI")
                    })
                } else permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            onGenerateImage = { startGeneration("image") },
            onGenerateVideo = { startGeneration("video") },
            imageSupported = if (selectedModel == "auto") chatState.models.any { it.available && it.supportsImageGeneration } else chatState.models.any { it.id == selectedModel && it.available && it.supportsImageGeneration },
            videoSupported = if (selectedModel == "auto") chatState.models.any { it.available && it.supportsVideoGeneration } else chatState.models.any { it.id == selectedModel && it.available && it.supportsVideoGeneration },
            onSelectModel = {
                selectedModel = it
                prefs.edit().putString("model", it).apply()
            },
            onRemovePending = { id ->
                pendingAttachments = pendingAttachments.filterNot { it.id == id }
            },
            onToggleWebSearch = { chatVm.setWebSearch(!chatState.webSearchEnabled) },
            onToggleLocalTools = { chatVm.setLocalTools(!chatState.localToolsEnabled) },
            onCopy = ::copyText,
            onShare = ::shareText,
            onSpeak = ::speakText,
            onEdit = { messageId, text ->
                editingMessage = ChatMessage(messageId, text, ChatMessage.Role.USER)
            },
            onRegenerate = ::regenerate,
            onExport = ::requestExport,
            onExportAttachment = { attachment ->
                val file = File(attachment.localPath)
                if (file.exists()) requestSaveFile(file)
                else Toast.makeText(context, "Attachment is no longer available.", Toast.LENGTH_LONG).show()
            },
            onDeleteSessions = { idsToDelete -> chatVm.deleteSessions(idsToDelete) },
            onToggleStar = { id -> chatVm.toggleStar(id) },
            onArchive = { id -> chatVm.archiveSession(id) },
            onUnarchive = { id -> chatVm.unarchiveSession(id) },
            onRename = { id -> renamingSession = sessions.firstOrNull { it.id == id } },
            onOpenAttachment = { attachment -> previewAttachment = attachment },
            onDeleteAttachment = { attachment -> chatVm.deleteAttachment(attachment.id) },
            onShareAttachment = ::shareAttachment,
            onRegenerateMedia = { message, attachment ->
                val type = if (attachment.kind == Attachment.Kind.GENERATED_VIDEO || attachment.mimeType.startsWith("video/")) "video" else "image"
                val prompt = message.text.substringAfter(":", message.text).trim()
                if (prompt.isNotBlank() && !mediaBusy) runGeneration(type, prompt)
            }
        )

        previewAttachment?.let { attachment ->
            MediaPreviewDialog(
                attachment = attachment,
                onDismiss = { previewAttachment = null },
                onSave = { requestSaveFile(File(attachment.localPath)) },
                onShare = { shareAttachment(attachment) },
                onDelete = {
                    previewAttachment = null
                    chatVm.deleteAttachment(attachment.id)
                },
                onRegenerate = activeSession.messages.firstOrNull { message ->
                    message.attachments.any { it.id == attachment.id }
                }?.let { message ->
                    { 
                        previewAttachment = null
                        val type = if (attachment.kind == Attachment.Kind.GENERATED_VIDEO || attachment.mimeType.startsWith("video/")) "video" else "image"
                        val prompt = message.text.substringAfter(":", message.text).trim()
                        if (prompt.isNotBlank()) runGeneration(type, prompt)
                    }
                }
            )
        }

        settingsContent()


        renamingSession?.let { session ->
            RenameChatDialog(
                initialTitle = session.title,
                onDismiss = { renamingSession = null },
                onSave = { title ->
                    chatVm.renameSession(session.id, title)
                    renamingSession = null
                }
            )
        }

        editingMessage?.let { message ->
            EditMessageDialog(
                initialText = message.text,
                onDismiss = { editingMessage = null },
                onResend = { editedText ->
                    chatVm.editAndResend(message.id, editedText, baseUrl, apiKey, selectedModel)
                    editingMessage = null
                }
            )
        }

        if (showOnboarding) {
            AiNameOnboarding(
                currentName = aiName,
                onContinue = {
                    aiName = it
                    prefs.edit().putString("aiName", aiName).apply()
                    showOnboarding = false
                    if (apiKey.isBlank()) showSettings = true
                }
            )
        }

        generationMode?.let { type ->
            GenerationDialog(
                type = type,
                prompt = generationPrompt,
                onPromptChange = { generationPrompt = it },
                onDismiss = {
                    generationMode = null
                    generationPrompt = ""
                },
                onGenerate = { runGeneration(type, generationPrompt) }
            )
        }

        if (locked) {
            Box(
                Modifier.fillMaxSize().background(com.nshd.geminifreellm.ui.LocalAppColors.current.background),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    com.nshd.geminifreellm.ui.AiOrb(Modifier.size(92.dp), false)
                    BasicText("App locked", color = com.nshd.geminifreellm.ui.LocalAppColors.current.text, fontSize = 22.sp)
                    Spacer(Modifier.width(1.dp))
                    AppButton("Unlock", onClick = activity::requestUnlock)
                }
            }
        }

        if (clearDialog) {
            AppDialog(
                title = "Clear chat history?",
                onDismiss = { clearDialog = false },
                content = {
                    BasicText(
                        "This removes local conversations from this device. Your server configuration stays saved.",
                        color = com.nshd.geminifreellm.ui.LocalAppColors.current.muted
                    )
                },
                actions = {
                    AppTextButton("Cancel", onClick = { clearDialog = false })
                    AppButton(
                        "Clear",
                        modifier = Modifier.width(110.dp),
                        onClick = {
                            chatVm.clearAll(selectedModel)
                            clearDialog = false
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun GenerationDialog(
    type: String,
    prompt: String,
    onPromptChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit
) {
    AppDialog(
        title = if (type == "image") "Generate image" else "Generate video",
        onDismiss = onDismiss,
        content = {
            com.nshd.geminifreellm.ui.AppField(
                value = prompt,
                onValueChange = onPromptChange,
                label = "Prompt",
                singleLine = false,
                minLines = 5
            )
        },
        actions = {
            AppTextButton("Cancel", onClick = onDismiss)
            AppButton("Generate", enabled = prompt.isNotBlank(), modifier = Modifier.width(110.dp), onClick = onGenerate)
        }
    )
}

@Composable
private fun rememberLauncherForCreate(
    mimeType: String,
    onResult: (Uri?) -> Unit
) = androidx.activity.compose.rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument(mimeType),
    onResult
)

@Composable
private fun rememberLauncherForMultipleDocuments(
    onResult: (List<Uri>) -> Unit
) = androidx.activity.compose.rememberLauncherForActivityResult(
    ActivityResultContracts.OpenMultipleDocuments(),
    onResult
)

@Composable
private fun rememberLauncherForOpenDocument(
    onResult: (Uri?) -> Unit
) = androidx.activity.compose.rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
    onResult
)


@Composable
private fun EditMessageDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onResend: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialText) }
    AppDialog(
        title = "Edit message",
        onDismiss = onDismiss,
        content = {
            com.nshd.geminifreellm.ui.AppField(
                value = value,
                onValueChange = { value = it.take(50_000) },
                label = "Message",
                singleLine = false,
                minLines = 6
            )
        },
        actions = {
            AppTextButton("Cancel", onClick = onDismiss)
            AppButton(
                "Resend",
                enabled = value.trim().isNotBlank(),
                modifier = Modifier.width(120.dp),
                onClick = { onResend(value.trim()) }
            )
        }
    )
}


@Composable
private fun RenameChatDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialTitle) }
    AppDialog(
        title = "Rename chat",
        onDismiss = onDismiss,
        content = {
            com.nshd.geminifreellm.ui.AppField(
                value = value,
                onValueChange = { value = it.take(80) },
                label = "Chat title",
                singleLine = true
            )
        },
        actions = {
            AppTextButton("Cancel", onClick = onDismiss)
            AppButton("Save", enabled = value.trim().isNotBlank(), modifier = Modifier.width(100.dp), onClick = { onSave(value.trim()) })
        }
    )
}
