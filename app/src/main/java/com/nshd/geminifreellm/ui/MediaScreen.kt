package com.nshd.geminifreellm.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nshd.geminifreellm.MediaViewModel
import com.nshd.geminifreellm.data.*
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(settings: AppSettings, onBack: () -> Unit) {
    val vm: MediaViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var kind by rememberSaveable { mutableStateOf(MediaKind.IMAGE.name) }
    val choices = settings.profiles.filter { it.provider.protocol == ApiProtocol.GEMINI || it.provider in listOf(Provider.OPENAI, Provider.CUSTOM, Provider.FREELLM) }
    var provider by rememberSaveable { mutableStateOf(choices.firstOrNull { it.provider == settings.selected }?.provider?.name ?: choices.firstOrNull()?.provider?.name.orEmpty()) }
    var model by rememberSaveable(provider, kind) { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<MediaViewModel.JobItem?>(null) }
    var exportItem by remember { mutableStateOf<MediaViewModel.JobItem?>(null) }
    var preview by remember { mutableStateOf<Attachment?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val profile = choices.firstOrNull { it.provider.name == provider }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = exportItem
        if (uri != null && item != null) scope.launch {
            notice = withContext(Dispatchers.IO) { runCatching {
                File(item.localPath).inputStream().use { input -> checkNotNull(context.contentResolver.openOutputStream(uri)).use { input.copyTo(it) } }
                "Creation exported"
            }.getOrDefault("Could not export this creation") }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Create") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to chat") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(Design.page), verticalArrangement = Arrangement.spacedBy(Design.medium)) {
            item {
                Text("Bring an idea to life", style = MaterialTheme.typography.headlineMedium)
                Text("Images: Gemini image models or an Images API endpoint. Videos: an OpenAI-compatible Videos API endpoint. Your provider must support the route and model; chat access alone is insufficient.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                    MediaKind.entries.forEach { type -> FilterChip(kind == type.name, { kind = type.name }, label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
            }
            item {
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(profile?.provider?.label ?: "Configure a compatible provider in Settings") }
                    DropdownMenu(menu, { menu = false }) {
                        choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.provider.label) }, onClick = { provider = choice.provider.name; menu = false }) }
                    }
                }
                if (kind == MediaKind.VIDEO.name) Text("Creates one 4-second, 720 × 1280 video. Polling can take several minutes. Gemini video generation is not supported by this adapter.", style = MaterialTheme.typography.bodySmall)
            }
            item { OutlinedTextField(model, { model = it.take(200) }, Modifier.fillMaxWidth(), label = { Text("${kind.lowercase().replaceFirstChar { it.uppercase() }} model ID") },
                supportingText = { Text("Enter a media model ID from your provider. This does not change your chat model.") }, singleLine = true) }
            item { OutlinedTextField(prompt, { prompt = it.take(8000) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                label = { Text("Describe your creation") }, supportingText = { Text("${prompt.length} / 8,000") }) }
            item {
                Text("Media requests may incur charges. No media is generated until you confirm. Stopping local waiting does not cancel a provider job or its charges.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { confirm = true }, enabled = !state.loading && !state.storageBlocked && state.busyId == null && profile != null && model.isNotBlank() && prompt.isNotBlank() &&
                    (kind != MediaKind.VIDEO.name || profile.provider.protocol == ApiProtocol.OPENAI), modifier = Modifier.fillMaxWidth()) { Text("Create ${kind.lowercase()}") }
                if (state.busyId != null) OutlinedButton(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop waiting") }
                (notice ?: state.notice)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
            item { Text("Your creations · ${state.jobs.size}/30", style = MaterialTheme.typography.titleLarge) }
            if (state.jobs.isEmpty()) item { Text("Generated media stays in this app until you export or delete it.", style = MaterialTheme.typography.bodyMedium) }
            items(state.jobs, key = { it.id }) { item ->
                Surface(shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
                        Text("${item.provider.label} · ${item.model}", style = MaterialTheme.typography.titleMedium)
                        Text(item.status, style = MaterialTheme.typography.bodyMedium)
                        if (item.remoteId.isNotBlank()) Text("Job ${item.remoteId}", style = MaterialTheme.typography.labelSmall)
                        if (item.id == state.busyId) {
                            if (item.progress > 0) LinearProgressIndicator(progress = { item.progress / 100f }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (item.localPath.isNotBlank()) {
                            val file = Attachment(name = "Generated image", mimeType = item.mime, size = File(item.localPath).length(), localPath = item.localPath)
                            if (item.kind == MediaKind.IMAGE) LocalAttachmentImage(file, Modifier.fillMaxWidth().height(190.dp), fullSize = true)
                            Row {
                                TextButton(onClick = {
                                    if (item.kind == MediaKind.IMAGE) preview = file
                                    else try {
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(item.localPath))
                                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    } catch (_: android.content.ActivityNotFoundException) { notice = "No video player found. Export the file to play it elsewhere." }
                                }) { Text(if (item.kind == MediaKind.IMAGE) "View" else "Play") }
                                TextButton(onClick = {
                                    exportItem = item
                                    val extension = when (item.mime) { "video/mp4" -> "mp4"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
                                    export.launch("freellm-${item.id.take(8)}.$extension")
                                }) { Text("Export") }
                            }
                        }
                        Row {
                            if (item.remoteId.isNotBlank() && item.localPath.isBlank()) TextButton(enabled = state.busyId == null, onClick = { vm.resume(item, settings) }) { Text("Resume / check job") }
                            TextButton(enabled = state.busyId == null, onClick = { delete = item }) { Text("Delete locally") }
                        }
                    }
                }
            }
        }
    }
    preview?.let { AttachmentPreview(it) { preview = null } }
    if (confirm && profile != null) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Create ${kind.lowercase()}?") },
        text = { Text("Send this prompt to ${profile.provider.label} using $model. The provider may charge for generation. Your conversation and API keys are not included in the prompt.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.create(profile, MediaKind.valueOf(kind), model.trim(), prompt) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
    delete?.let { item -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete local creation?") },
        text = { Text("This removes the saved file and job reference from this device. Provider jobs and exported copies are unaffected.") },
        confirmButton = { TextButton(onClick = { vm.delete(item); delete = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
}
