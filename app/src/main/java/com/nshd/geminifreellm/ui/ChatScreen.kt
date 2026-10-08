package com.nshd.geminifreellm.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nshd.geminifreellm.ChatViewModel
import com.nshd.geminifreellm.BuildConfig
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeLlmApp(vm: ChatViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    val mode = runCatching { ThemeMode.valueOf(state.settings.theme) }.getOrDefault(ThemeMode.SYSTEM)
    GeminiTheme(mode) {
        when {
            state.loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading your chats" })
            }
            state.storageBlocked -> Surface(Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding().padding(Design.page), verticalArrangement = Arrangement.Center) {
                    Text("Could not open local data", style = MaterialTheme.typography.titleLarge)
                    Text(state.notice.orEmpty(), Modifier.padding(top = Design.medium))
                }
            }
            settings -> SettingsScreen(state.settings, vm.client, vm::saveSettings, { settings = false })
            else -> ChatWorkspace(state, vm, { settings = true })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatWorkspace(state: ChatViewModel.State, vm: ChatViewModel, onSettings: () -> Unit) {
    val chat = state.active ?: return
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var models by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val busy = state.generatingId == chat.id
    val chatScrollStates = rememberSaveableStateHolder()
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::attach) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            vm.draft(listOf(vm.state.value.active?.draft.orEmpty(), it).filter { value -> value.isNotBlank() }.joinToString(" "))
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); vm.notice(null) }
    }
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        HistoryDrawer(state, vm, onClose = { scope.launch { drawer.close() } }, onSettings = {
            scope.launch { drawer.close() }; onSettings()
        })
    }) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
            TopAppBar(title = {
                TextButton(onClick = { models = true }, contentPadding = PaddingValues(horizontal = Design.small)) {
                    Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.Start) {
                        Text("FreeLLM AI", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(state.settings.active.model.ifBlank { "Choose a model" }, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, "Choose AI model")
                }
            }, navigationIcon = {
                IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.AutoMirrored.Outlined.MenuOpen, "Open chat history") }
            }, actions = {
                IconButton(onClick = vm::newChat) { Icon(Icons.Outlined.Edit, "New chat") }
                var overflow by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { overflow = true }) { Icon(Icons.Outlined.MoreHoriz, "Chat options") }
                    DropdownMenu(overflow, { overflow = false }) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { overflow = false; onSettings() }, leadingIcon = { Icon(Icons.Outlined.Settings, null) })
                        DropdownMenuItem(text = { Text("Share conversation") }, enabled = chat.messages.isNotEmpty(), onClick = { overflow = false; export = true },
                            leadingIcon = { Icon(Icons.Outlined.Share, null) })
                    }
                }
            })
        }, bottomBar = {
            Box(Modifier.fillMaxWidth().imePadding().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                Composer(chat, busy, state.preparing, vm::draft, vm::send, vm::stop,
                    onAttach = { attachmentPicker.launch(arrayOf("image/*", "text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/json")) },
                    onVoice = {
                        try { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your message")) }
                        catch (_: android.content.ActivityNotFoundException) { vm.notice("Speech input is not installed. You can use your keyboard's microphone.") }
                    }, onRemove = vm::removeAttachment)
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                if (chat.messages.isEmpty()) WelcomeState(onPrompt = vm::draft, onSettings = onSettings,
                    needsConnection = state.settings.active.apiKey.isBlank(), modifier = Modifier.widthIn(max = Design.readingWidth).fillMaxSize())
                else chatScrollStates.SaveableStateProvider(chat.id) { ConversationMessages(chat, busy, vm, onSettings) }
            }
        }
    }
    if (models) ModalBottomSheet(onDismissRequest = { models = false }) {
        Column(Modifier.fillMaxWidth().padding(Design.page), verticalArrangement = Arrangement.spacedBy(Design.small)) {
            Text("Choose your AI", style = MaterialTheme.typography.titleLarge)
            Text("The selected provider receives this conversation when you send.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.settings.profiles.forEach { profile ->
                ListItem(headlineContent = { Text(profile.provider.label) }, supportingContent = { Text(profile.model, maxLines = 2) },
                    trailingContent = { if (profile.provider == state.settings.selected) Icon(Icons.Outlined.Check, "Selected") },
                    modifier = Modifier.clickable {
                        scope.launch { if (vm.saveSettings(state.settings.copy(selected = profile.provider))) models = false }
                    })
            }
            OutlinedButton(onClick = { models = false; onSettings() }, modifier = Modifier.fillMaxWidth()) { Text("Manage providers & models") }
            Spacer(Modifier.height(Design.large))
        }
    }
    if (export) AlertDialog(onDismissRequest = { export = false }, title = { Text("Share this conversation?") },
        text = { Text("This includes messages and extracted document text. API keys and image files are excluded.") },
        confirmButton = { TextButton(onClick = {
            export = false
            val text = buildString {
                append("# ${chat.title}\n\n")
                chat.messages.forEach { message ->
                    append("${message.role.name.lowercase()}: ${message.text}\n\n")
                    message.attachments.forEach { append("[${it.name}]\n${it.text}\n\n") }
                }
            }
            if (text.length > 150_000) vm.notice("This chat is too large to share as text. Copy individual messages instead.")
            else try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share conversation")) }
            catch (_: android.content.ActivityNotFoundException) { vm.notice("No app is available to share text.") }
        }) { Text("Share") } }, dismissButton = { TextButton(onClick = { export = false }) { Text("Cancel") } })
}

@Composable
private fun HistoryDrawer(state: ChatViewModel.State, vm: ChatViewModel, onClose: () -> Unit, onSettings: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Conversation?>(null) }
    var delete by remember { mutableStateOf<Conversation?>(null) }
    val filtered by produceState(emptyList<Conversation>(), state.chats, query, archived) {
        if (query.isNotEmpty()) delay(150)
        value = withContext(Dispatchers.Default) {
            state.chats.filter { it.archived == archived && (query.isBlank() || it.title.contains(query, true) || it.messages.any { m -> m.text.contains(query, true) }) }
                .sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })
        }
    }
    ModalDrawerSheet(modifier = Modifier.widthIn(max = 340.dp).imePadding(), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(Design.page), verticalAlignment = Alignment.CenterVertically) {
            Text("FreeLLM AI", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close history") }
        }
        FilledTonalButton(onClick = { vm.newChat(); onClose() }, modifier = Modifier.fillMaxWidth().padding(horizontal = Design.page)) {
            Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(Design.small)); Text("New chat")
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(Design.page), singleLine = true,
            placeholder = { Text("Search your chats") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = Design.card,
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } })
        Row(Modifier.padding(horizontal = Design.page), horizontalArrangement = Arrangement.spacedBy(Design.small)) {
            FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("Chats") })
            FilterChip(selected = archived, onClick = { archived = true }, label = { Text("Archived") })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.tiny)) {
            if (filtered.isEmpty()) item {
                Text(if (query.isNotBlank()) "No matching chats" else if (archived) "Archived chats appear here" else "Your conversations will appear here",
                    Modifier.padding(Design.medium), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val pinned = filtered.filter { it.pinned }
            val recent = filtered.filterNot { it.pinned }
            if (pinned.isNotEmpty()) item { Text("Pinned", Modifier.padding(Design.medium).semantics { heading() }, style = MaterialTheme.typography.labelLarge) }
            items(pinned, key = { it.id }) { chat -> HistoryRow(chat, state.activeId, { vm.select(chat.id); onClose() }, { vm.pin(chat.id) }, { vm.archive(chat.id) }, { rename = chat }, { delete = chat }) }
            if (recent.isNotEmpty()) item { Text(if (archived) "Archived" else "Recent", Modifier.padding(Design.medium).semantics { heading() }, style = MaterialTheme.typography.labelLarge) }
            items(recent, key = { it.id }) { chat -> HistoryRow(chat, state.activeId, { vm.select(chat.id); onClose() }, { vm.pin(chat.id) }, { vm.archive(chat.id) }, { rename = chat }, { delete = chat }) }
        }
        ListItem(headlineContent = { Text("Settings") }, supportingContent = { Text("Providers, appearance & privacy") },
            leadingContent = { Icon(Icons.Outlined.Settings, null) }, modifier = Modifier.clickable(onClick = onSettings),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
        Text("FreeLLM AI · ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            modifier = Modifier.padding(horizontal = Design.page, vertical = Design.small),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    rename?.let { chat ->
        var title by remember(chat.id) { mutableStateOf(chat.title) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename chat") }, text = {
            OutlinedTextField(title, { title = it.take(100) }, label = { Text("Chat title") }, singleLine = true)
        }, confirmButton = { TextButton(onClick = { vm.rename(chat.id, title); rename = null }, enabled = title.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
    delete?.let { chat ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete chat?") }, text = { Text("“${chat.title}” will be permanently removed from this device.") },
            confirmButton = { TextButton(onClick = { vm.delete(chat.id); delete = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
}

@Composable
private fun HistoryRow(chat: Conversation, active: String, select: () -> Unit, pin: () -> Unit, archive: () -> Unit, rename: () -> Unit, delete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = select, shape = Design.card, color = if (chat.id == active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().semantics { selected = chat.id == active }) {
        Row(Modifier.padding(start = Design.medium), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (chat.pinned) Icons.Outlined.PushPin else Icons.Outlined.ChatBubbleOutline, null, Modifier.size(20.dp))
            Text(chat.title, Modifier.weight(1f).padding(horizontal = Design.medium), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, "Actions for ${chat.title}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (chat.pinned) "Unpin" else "Pin") }, onClick = { menu = false; pin() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename() })
                    DropdownMenuItem(text = { Text(if (chat.archived) "Unarchive" else "Archive") }, onClick = { menu = false; archive() })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete() })
                }
            }
        }
    }
}

@Composable
private fun WelcomeState(onPrompt: (String) -> Unit, onSettings: () -> Unit, needsConnection: Boolean, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(Design.page), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Design.medium)) {
        Spacer(Modifier.height(40.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) }
        }
        Text("What can I help with?", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("Your ideas. Your choice of AI.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Design.large))
        val suggestions = listOf(
            Triple(Icons.Outlined.EditNote, "Write or edit", "Help me write a clear, thoughtful message about "),
            Triple(Icons.Outlined.Lightbulb, "Explore an idea", "Help me brainstorm ideas for "),
            Triple(Icons.AutoMirrored.Outlined.Article, "Make it simple", "Explain this in simple terms: "),
            Triple(Icons.Outlined.Code, "Build something", "Help me debug this code: ")
        )
        suggestions.forEach { (icon, label, prompt) ->
            Surface(onClick = { onPrompt(prompt) }, shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(Design.medium).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, Modifier.padding(start = Design.medium), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (needsConnection) TextButton(onClick = onSettings) { Text("Connect your AI provider") }
        Spacer(Modifier.height(Design.medium))
    }
}

@Composable
private fun ConversationMessages(chat: Conversation, busy: Boolean, vm: ChatViewModel, onSettings: () -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }
    suspend fun scrollToLatest() {
        programmaticScroll = true
        try {
            list.scrollToItem(chat.messages.lastIndex)
            // The last reply may be taller than the viewport; bring its bottom into view.
            val lastSize = list.layoutInfo.visibleItemsInfo.lastOrNull { it.index == chat.messages.lastIndex }?.size ?: 0
            list.scrollBy(lastSize.toFloat())
        } finally { programmaticScroll = false }
    }
    val lastText = chat.messages.lastOrNull()?.text
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canScroll) ->
            if (scrolling && !programmaticScroll) follow = !canScroll
        }
    }
    LaunchedEffect(chat.messages.size, lastText, busy) {
        if (follow && chat.messages.isNotEmpty()) scrollToLatest()
    }
    Box(Modifier.widthIn(max = Design.readingWidth).fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(Design.page), verticalArrangement = Arrangement.spacedBy(Design.large)) {
            items(chat.messages, key = { it.id }, contentType = { it.role }) { message ->
                MessageRow(message, busy && message == chat.messages.lastOrNull(),
                    canRetry = !busy && message == chat.messages.lastOrNull() && message.role != ChatMessage.Role.USER,
                    onRetry = vm::retry, onEdit = { vm.edit(message) }, canEdit = !busy, onSettings = onSettings)
            }
        }
        if (!follow && list.canScrollForward) SmallFloatingActionButton(onClick = {
            follow = true; scope.launch { scrollToLatest() }
        }, modifier = Modifier.align(Alignment.BottomCenter).padding(Design.small), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(Icons.Outlined.ArrowDownward, "Jump to latest message")
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun MessageRow(message: ChatMessage, generating: Boolean, canRetry: Boolean, onRetry: () -> Unit, onEdit: () -> Unit, canEdit: Boolean, onSettings: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    val user = message.role == ChatMessage.Role.USER
    val error = message.role == ChatMessage.Role.ERROR
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (!user && !error) Text(message.model.ifBlank { "Assistant" }, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = Design.small))
        Surface(color = when { error -> MaterialTheme.colorScheme.errorContainer; user -> MaterialTheme.colorScheme.surfaceContainer; else -> MaterialTheme.colorScheme.surface },
            shape = Design.card, modifier = if (user) Modifier.widthIn(max = 580.dp) else Modifier.fillMaxWidth()) {
            Column(Modifier.padding(if (user || error) Design.medium else 0.dp), verticalArrangement = Arrangement.spacedBy(Design.small)) {
                message.attachments.forEach { file -> AttachmentLabel(file) }
                when {
                    error -> Text(message.text, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    message.text.isEmpty() && generating -> Text("Thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    user -> SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
                    else -> MessageContent(message.text)
                }
                if (generating) Text("Generating…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else if (message.interrupted) Text("Reply incomplete", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!generating) Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)); copied = true }) {
                Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, if (copied) "Copied" else "Copy message", Modifier.size(20.dp))
            }
            if (user && canEdit) IconButton(onClick = { edit = true }) { Icon(Icons.Outlined.Edit, "Edit message", Modifier.size(20.dp)) }
            if (canRetry) IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, if (error) "Retry reply" else "Regenerate reply", Modifier.size(20.dp)) }
            if (error) TextButton(onClick = onSettings) { Text("Settings") }
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (edit) AlertDialog(onDismissRequest = { edit = false }, title = { Text("Edit this message?") },
        text = { Text("This message and all following replies will be replaced when you edit. Its text and attachments return to the composer.") },
        confirmButton = { TextButton(onClick = { edit = false; onEdit() }) { Text("Edit") } },
        dismissButton = { TextButton(onClick = { edit = false }) { Text("Cancel") } })
}

@Composable
private fun AttachmentLabel(file: Attachment, onRemove: (() -> Unit)? = null) {
    var preview by remember { mutableStateOf(false) }
    if (preview) AttachmentPreview(file) { preview = false }
    Surface(onClick = { preview = true }, shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.semantics { contentDescription = "Open attachment ${file.name}" }) {
        Row(Modifier.padding(start = Design.medium, end = if (onRemove == null) Design.medium else 0.dp).heightIn(min = Design.touch), verticalAlignment = Alignment.CenterVertically) {
            if (file.isImage) LocalAttachmentImage(file, Modifier.size(40.dp))
            else Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(Design.small)) {
                Text(file.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${maxOf(1, file.size / 1024)} KB · ${if (file.isImage) "Image" else "Document"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Remove ${file.name}", Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun Composer(chat: Conversation, busy: Boolean, preparing: Boolean, onChange: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit,
                     onAttach: () -> Unit, onVoice: () -> Unit, onRemove: (String) -> Unit) {
    Column(Modifier.widthIn(max = Design.readingWidth).fillMaxWidth().padding(horizontal = Design.medium, vertical = Design.small), verticalArrangement = Arrangement.spacedBy(Design.small)) {
        if (chat.attachments.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(Design.small)) {
            items(chat.attachments, key = { it.id }) { file -> Box(Modifier.width(240.dp)) { AttachmentLabel(file) { onRemove(file.id) } } }
        }
        if (preparing) Text("Preparing attachment…", Modifier.padding(horizontal = Design.small).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium)
        Surface(shape = Design.composer, color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.padding(Design.tiny), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onAttach, enabled = !busy && !preparing) { Icon(Icons.Outlined.Add, "Attach a file") }
                BasicTextField(chat.draft, onChange, modifier = Modifier.weight(1f).heightIn(min = Design.touch)
                    .padding(vertical = Design.medium).semantics { contentDescription = "Message" }, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner -> Box { if (chat.draft.isEmpty()) Text("Ask anything", color = MaterialTheme.colorScheme.onSurfaceVariant); inner() } })
                if (chat.draft.isBlank() && chat.attachments.isEmpty() && !busy) {
                    IconButton(onClick = onVoice) { Icon(Icons.Outlined.MicNone, "Dictate a message") }
                } else {
                    FilledIconButton(onClick = if (busy) onStop else onSend,
                        enabled = busy || !preparing, modifier = Modifier.size(Design.touch)) {
                        Icon(if (busy) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (busy) "Stop generating" else "Send message")
                    }
                }
            }
        }
        Text("AI can make mistakes. Check important information.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
