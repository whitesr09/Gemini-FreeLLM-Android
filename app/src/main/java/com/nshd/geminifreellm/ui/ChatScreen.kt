package com.nshd.geminifreellm.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nshd.geminifreellm.data.ExportFormat
import com.nshd.geminifreellm.data.ModelInfo
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ChatSession
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    aiName: String,
    session: ChatSession,
    sessions: List<ChatSession>,
    selectedModel: String,
    models: List<ModelInfo>,
    input: String,
    pendingAttachments: List<Attachment>,
    busy: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onSettings: () -> Unit,
    onAttach: () -> Unit,
    onGenerateImage: () -> Unit,
    onGenerateVideo: () -> Unit,
    onSelectModel: (String) -> Unit,
    onRemovePending: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRegenerate: (Long) -> Unit,
    onExport: (ChatMessage, ExportFormat) -> Unit,
    onExportAttachment: (Attachment) -> Unit,
    onDeleteSessions: (Set<String>) -> Unit,
    onToggleStar: (String) -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            HistoryDrawer(
                aiName = aiName,
                sessions = sessions,
                currentId = session.id,
                onNewChat = {
                    onNewChat()
                    scope.launch { drawerState.close() }
                },
                onSelect = {
                    onSelectSession(it)
                    scope.launch { drawerState.close() }
                },
                onSettings = {
                    onSettings()
                    scope.launch { drawerState.close() }
                },
                onDeleteSessions = onDeleteSessions,
                onToggleStar = onToggleStar
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AiOrb(Modifier.size(34.dp), active = busy)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(aiName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    selectedModel.ifBlank { "Auto" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Chats")
                        }
                    },
                    actions = {
                        ModelPicker(models, selectedModel, onSelectModel)
                        IconButton(onClick = onNewChat) {
                            Icon(Icons.Filled.Add, contentDescription = "New chat")
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                MessageList(
                    messages = session.messages,
                    aiName = aiName,
                    busy = busy,
                    modifier = Modifier.weight(1f),
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onExport = onExport,
                    onExportAttachment = onExportAttachment
                )
                Composer(
                    input = input,
                    pendingAttachments = pendingAttachments,
                    busy = busy,
                    onInputChange = onInputChange,
                    onSend = onSend,
                    onAttach = onAttach,
                    onGenerateImage = onGenerateImage,
                    onGenerateVideo = onGenerateVideo,
                    onRemovePending = onRemovePending
                )
            }
        }
    }
}

@Composable
private fun HistoryDrawer(
    aiName: String,
    sessions: List<ChatSession>,
    currentId: String,
    onNewChat: () -> Unit,
    onSelect: (String) -> Unit,
    onSettings: () -> Unit,
    onDeleteSessions: (Set<String>) -> Unit,
    onToggleStar: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions else sessions.filter {
            it.title.contains(query, true) || it.messages.any { m -> m.text.contains(query, true) }
        }
    }

    Surface(
        modifier = Modifier.fillMaxHeight().widthIn(min = 290.dp, max = 360.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiOrb(Modifier.size(42.dp), active = true)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(aiName, fontWeight = FontWeight.SemiBold)
                    Text("Your chats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("New chat")
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search chats") },
                shape = RoundedCornerShape(18.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("History", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    selectionMode = !selectionMode
                    selectedIds = emptySet()
                }) {
                    Icon(
                        if (selectionMode) Icons.Filled.Check else Icons.Filled.SelectAll,
                        contentDescription = "Select chats"
                    )
                }
                if (selectionMode && selectedIds.isNotEmpty()) {
                    IconButton(onClick = {
                        onDeleteSessions(selectedIds)
                        selectedIds = emptySet()
                        selectionMode = false
                    }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete selected")
                    }
                }
            }

            Divider()

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filtered, key = { it.id }) { item ->
                    val selected = item.id in selectedIds
                    NavigationDrawerItem(
                        label = {
                            Column(Modifier.padding(vertical = 2.dp)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val preview = item.preview()
                                if (preview.isNotBlank()) {
                                    Text(
                                        preview,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        selected = item.id == currentId && !selectionMode,
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (selected) selectedIds - item.id else selectedIds + item.id
                            } else onSelect(item.id)
                        },
                        modifier = Modifier.animateItem(),
                        icon = {
                            Icon(
                                if (item.starred) Icons.Filled.Star else Icons.Filled.FolderOpen,
                                contentDescription = null
                            )
                        },
                        badge = {
                            if (selectionMode) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = {
                                        selectedIds = if (it) selectedIds + item.id else selectedIds - item.id
                                    }
                                )
                            } else {
                                IconButton(onClick = { onToggleStar(item.id) }) {
                                    Icon(
                                        if (item.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = "Star"
                                    )
                                }
                            }
                        }
                    )
                }
            }

            Text(
                "Chats stay on this device unless you attach them to a request.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<ModelInfo>,
    selectedModel: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Tune, contentDescription = "Model")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val choices = models.filter { it.available }.ifEmpty {
                listOf(ModelInfo("auto", "Auto", true))
            }
            choices.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(model.name)
                            if (model.id != model.name) {
                                Text(model.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                    trailingIcon = {
                        if (model.id == selectedModel) Icon(Icons.Filled.Check, contentDescription = null)
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    aiName: String,
    busy: Boolean,
    modifier: Modifier,
    onCopy: (String) -> Unit,
    onRegenerate: (Long) -> Unit,
    onExport: (ChatMessage, ExportFormat) -> Unit,
    onExportAttachment: (Attachment) -> Unit
) {
    val state = rememberLazyListState()
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) state.animateScrollToItem(messages.lastIndex)
    }

    if (messages.isEmpty()) {
        EmptyState(aiName, modifier)
        return
    }

    LazyColumn(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(messages, key = { _, item -> item.id }) { _, message ->
            MessageCard(
                message = message,
                aiName = aiName,
                onCopy = { onCopy(message.text) },
                onRegenerate = { onRegenerate(message.id) },
                onExport = { onExport(message, it) },
                onExportAttachment = onExportAttachment
            )
        }
        if (busy) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    AiOrb(Modifier.size(28.dp), active = true)
                    Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageCard(
    message: ChatMessage,
    aiName: String,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onExportAttachment: (Attachment) -> Unit
) {
    val user = message.role == ChatMessage.Role.USER
    val error = message.role == ChatMessage.Role.ERROR

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier.widthIn(max = 720.dp),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start
        ) {
            if (!user) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AiOrb(Modifier.size(28.dp), active = false)
                    Spacer(Modifier.width(8.dp))
                    Text(aiName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(5.dp))
            }

            Surface(
                color = when {
                    error -> MaterialTheme.colorScheme.errorContainer
                    user -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
                },
                shape = RoundedCornerShape(
                    topStart = 22.dp,
                    topEnd = 22.dp,
                    bottomStart = if (user) 22.dp else 6.dp,
                    bottomEnd = if (user) 6.dp else 22.dp
                )
            ) {
                Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                    if (message.text.isNotBlank()) MarkdownMessage(message.text)
                    if (message.attachments.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            message.attachments.forEach { attachment ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                    modifier = Modifier.clickable { onExportAttachment(attachment) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            when {
                                                attachment.mimeType.startsWith("image/") -> Icons.Filled.Image
                                                attachment.mimeType.startsWith("video/") -> Icons.Filled.VideoLibrary
                                                else -> Icons.Filled.Description
                                            },
                                            contentDescription = "Save " + attachment.name,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (attachment.kind == Attachment.Kind.GENERATED_IMAGE && attachment.localPath.isNotBlank()) {
                                            val bmp = remember(attachment.localPath) { BitmapFactory.decodeFile(attachment.localPath) }
                                            if (bmp != null) {
                                                Spacer(Modifier.width(7.dp))
                                                androidx.compose.foundation.Image(
                                                    bitmap = bmp.asImageBitmap(),
                                                    contentDescription = null,
                                                    modifier = Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (!user && message.text.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = onRegenerate) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Regenerate")
                    }
                    var exportExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { exportExpanded = true }) {
                            Icon(Icons.Filled.FileDownload, contentDescription = "Export")
                        }
                        DropdownMenu(expanded = exportExpanded, onDismissRequest = { exportExpanded = false }) {
                            ExportFormat.entries.forEach { format ->
                                DropdownMenuItem(
                                    text = { Text("Save as " + format.extension.uppercase()) },
                                    onClick = {
                                        exportExpanded = false
                                        onExport(format)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownMessage(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        val blocks = remember(text) { splitMarkdownBlocks(text) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                if (block.code) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            if (block.language.isNotBlank()) {
                                Text(
                                    block.language,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.height(5.dp))
                            }
                            Text(
                                block.text,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    Text(block.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private data class MarkdownBlock(
    val code: Boolean,
    val language: String = "",
    val text: String
)

private fun splitMarkdownBlocks(text: String): List<MarkdownBlock> {
    val fence = 96.toChar().toString().repeat(3)
    val regex = Regex("(?s)" + Regex.escape(fence) + "([^\\n]*)\\n(.*?)" + Regex.escape(fence))
    val result = mutableListOf<MarkdownBlock>()
    var cursor = 0
    regex.findAll(text).forEach { match ->
        val before = text.substring(cursor, match.range.first)
        if (before.isNotBlank()) result += MarkdownBlock(false, text = before.trim())
        result += MarkdownBlock(true, match.groupValues[1].trim(), match.groupValues[2].trimEnd())
        cursor = match.range.last + 1
    }
    val tail = text.substring(cursor)
    if (tail.isNotBlank()) result += MarkdownBlock(false, text = tail.trim())
    return if (result.isEmpty()) listOf(MarkdownBlock(false, text = text)) else result
}

@Composable
private fun Composer(
    input: String,
    pendingAttachments: List<Attachment>,
    busy: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onGenerateImage: () -> Unit,
    onGenerateVideo: () -> Unit,
    onRemovePending: (String) -> Unit
) {
    var toolsExpanded by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pendingAttachments.forEach { item ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(
                                Modifier.padding(start = 10.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (item.mimeType.startsWith("image/")) Icons.Filled.Image else Icons.Filled.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { onRemovePending(item.id) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(7.dp))
            }

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box {
                    IconButton(onClick = { toolsExpanded = true }) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = "AI tools")
                    }
                    DropdownMenu(expanded = toolsExpanded, onDismissRequest = { toolsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Generate image") },
                            leadingIcon = { Icon(Icons.Filled.AddPhotoAlternate, null) },
                            onClick = {
                                toolsExpanded = false
                                onGenerateImage()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Generate video") },
                            leadingIcon = { Icon(Icons.Filled.VideoLibrary, null) },
                            onClick = {
                                toolsExpanded = false
                                onGenerateVideo()
                            }
                        )
                    }
                }

                IconButton(onClick = onAttach, enabled = !busy) {
                    Icon(Icons.Filled.Description, contentDescription = "Attach file")
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    placeholder = { Text("Message…") },
                    minLines = 1,
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp)
                )

                IconButton(
                    onClick = onSend,
                    enabled = !busy && (input.isNotBlank() || pendingAttachments.isNotEmpty()),
                    modifier = Modifier.size(50.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Text(
                "AI responses can be wrong. Verify important information.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, top = 5.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(aiName: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
            AiOrb(Modifier.size(86.dp), active = true)
            Spacer(Modifier.height(20.dp))
            Text("Meet " + aiName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Ask, code, analyze files, create images and turn answers into downloadable documents.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = {}, label = { Text("Code") })
                FilterChip(selected = false, onClick = {}, label = { Text("Analyze") })
                FilterChip(selected = false, onClick = {}, label = { Text("Create") })
            }
        }
    }
}

@Composable
fun AiOrb(modifier: Modifier = Modifier, active: Boolean) {
    val transition = rememberInfiniteTransition(label = "orb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(if (active) 2400 else 7000, easing = FastOutSlowInEasing),
            RepeatMode.Restart
        ),
        label = "rotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .rotate(rotation)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary,
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.primary
                    )
                )
            )
            .padding(3.dp)
    ) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                Modifier.align(Alignment.Center)
                    .size((18 * pulse).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
