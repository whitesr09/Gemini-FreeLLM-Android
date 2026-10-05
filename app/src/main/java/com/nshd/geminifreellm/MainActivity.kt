package com.nshd.geminifreellm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.nshd.geminifreellm.data.AppDiagnostics
import com.nshd.geminifreellm.data.DocumentExporter
import com.nshd.geminifreellm.data.DocumentProcessor
import com.nshd.geminifreellm.data.ExportFormat
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.data.MediaResult
import com.nshd.geminifreellm.data.ModelInfo
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
import com.nshd.geminifreellm.ui.SettingsDialog
import com.nshd.geminifreellm.ui.ThemeMode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDiagnostics.install(this)
        AppDiagnostics.recordEvent(this, "app_started")
        setContent { AiApp(this) }
    }
}

@Composable
private fun AiApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    val secureCredentials = remember { SecureCredentialStore(context).also { it.migrateLegacy(prefs) } }
    val chatVm: ChatViewModel = viewModel()
    val chatState by chatVm.uiState.collectAsState()
    val client = remember { FreeLlmApiClient() }
    val scope = rememberCoroutineScope()
    val ids = remember { AtomicLong(System.currentTimeMillis()) }

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
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
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
    var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
    var showOnboarding by remember { mutableStateOf(aiName.isBlank()) }
    var generationMode by remember { mutableStateOf<String?>(null) }
    var generationPrompt by remember { mutableStateOf("") }
    var clearDialog by remember { mutableStateOf(false) }

    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    var pendingSaveName by remember { mutableStateOf<String?>(null) }

    fun replaceSession(session: ChatSession, persistNow: Boolean = true) {
        chatVm.replaceSession(session, persistNow, selectedModel)
    }

    fun makeNewChat() = chatVm.newChat()

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
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            file.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("Couldn't open the destination.")
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
            val added = uris.mapNotNull { uri ->
                runCatching { DocumentProcessor.copyToAppStorage(context, uri)?.attachment }.getOrNull()
            }
            if (added.isNotEmpty()) {
                pendingAttachments = pendingAttachments + added
            }
        }
    }

    val importLauncher = rememberLauncherForOpenDocument { uri ->
        if (uri == null) return@rememberLauncherForOpenDocument
        scope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: error("Couldn't read the backup.")
                chatVm.importJson(raw, selectedModel)
            }.onSuccess {
                Toast.makeText(context, "Chats imported", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "Import failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(baseUrl, apiKey) {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            models = emptyList()
        } else {
            val result = client.fetchModels(baseUrl, apiKey)
            result.onSuccess { list ->
                models = list
                if (selectedModel != "auto" && list.none { it.id == selectedModel && it.available }) {
                    selectedModel = "auto"
                    prefs.edit().putString("model", "auto").apply()
                }
            }
        }
    }

    fun sendMessage() {
        if (busy || (input.isBlank() && pendingAttachments.isEmpty())) return
        chatVm.sendMessage(baseUrl, apiKey, selectedModel, input, pendingAttachments)
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
                DocumentExporter.renderText(message, format)
            }.onSuccess { file ->
                requestSaveFile(file)
            }.onFailure {
                Toast.makeText(context, it.message ?: "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val settingsContent: @Composable () -> Unit = {
        if (showSettings) {
            SettingsDialog(
                baseUrl = baseUrl,
                apiKey = apiKey,
                aiName = aiName,
                themeMode = themeMode,
                onSave = { url, key, name, theme ->
                    baseUrl = url
                    apiKey = key
                    aiName = name
                    themeMode = theme
                    secureCredentials.setApiKey(apiKey)
                    prefs.edit()
                        .putString("baseUrl", baseUrl)
                        .putString("aiName", aiName)
                        .putString("theme", theme.name)
                        .apply()
                    diagnostics = AppDiagnostics.selfCheck(context, baseUrl, apiKey.isNotBlank())
                    AppDiagnostics.recordEvent(context, "settings_saved")
                    showSettings = false
                    if (aiName.isNotBlank()) showOnboarding = false
                },
                onDismiss = {
                    if (apiKey.isNotBlank() && aiName.isNotBlank()) showSettings = false
                },
                onExportChats = {
                    val file = File(context.cacheDir, "chat_backup.json")
                    file.writeText(chatVm.exportJson())
                    requestSaveFile(file)
                },
                onImportChats = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                onClearChats = { clearDialog = true },
                diagnostics = diagnostics,
                onExportDiagnostics = {
                    runCatching {
                        AppDiagnostics.exportReport(context, baseUrl, apiKey.isNotBlank(), sessions.size)
                    }.onSuccess { file -> requestSaveFile(file) }
                        .onFailure { Toast.makeText(context, it.message ?: "Diagnostics export failed", Toast.LENGTH_LONG).show() }
                },
                onSafeRepair = {
                    AppDiagnostics.maintenance(context)
                    diagnostics = AppDiagnostics.selfCheck(context, baseUrl, apiKey.isNotBlank())
                    Toast.makeText(context, "Maintenance completed", Toast.LENGTH_SHORT).show()
                },
                onClearCrashReport = {
                    AppDiagnostics.clearPreviousCrash(context)
                    diagnostics = AppDiagnostics.selfCheck(context, baseUrl, apiKey.isNotBlank())
                    Toast.makeText(context, "Crash report cleared", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    AiTheme(themeMode) {
        ChatScreen(
            aiName = aiName.ifBlank { "Assistant" },
            session = activeSession,
            sessions = sessions,
            selectedModel = selectedModel,
            models = models,
            input = input,
            pendingAttachments = pendingAttachments,
            busy = busy,
            onInputChange = { input = it },
            onSend = ::sendMessage,
            onNewChat = ::makeNewChat,
            onSelectSession = { id ->
                if (!busy) chatVm.selectSession(id)
            },
            onSettings = { showSettings = true },
            onStop = { chatVm.stopGeneration() },
            onAttach = { attachmentLauncher.launch(arrayOf("*/*")) },
            onGenerateImage = { startGeneration("image") },
            onGenerateVideo = { startGeneration("video") },
            onSelectModel = {
                selectedModel = it
                prefs.edit().putString("model", it).apply()
            },
            onRemovePending = { id ->
                pendingAttachments = pendingAttachments.filterNot { it.id == id }
            },
            onCopy = ::copyText,
            onRegenerate = ::regenerate,
            onExport = ::requestExport,
            onExportAttachment = { attachment ->
                val file = File(attachment.localPath)
                if (file.exists()) requestSaveFile(file)
                else Toast.makeText(context, "Attachment is no longer available.", Toast.LENGTH_LONG).show()
            },
            onDeleteSessions = { idsToDelete -> chatVm.deleteSessions(idsToDelete) },
            onToggleStar = { id -> chatVm.toggleStar(id) }
        )

        settingsContent()

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

        if (generationMode != null) {
            GenerationDialog(
                type = generationMode!!,
                prompt = generationPrompt,
                onPromptChange = { generationPrompt = it },
                onDismiss = {
                    generationMode = null
                    generationPrompt = ""
                },
                onGenerate = { runGeneration(generationMode!!, generationPrompt) }
            )
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
