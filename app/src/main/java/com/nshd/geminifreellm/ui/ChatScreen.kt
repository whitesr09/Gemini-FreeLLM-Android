package com.nshd.geminifreellm.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Popup
import com.nshd.geminifreellm.data.ExportFormat
import com.nshd.geminifreellm.data.ModelInfo
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ChatSession

private val ChatRadius = RoundedCornerShape(16.dp)

private fun iconLabel(symbol: String): String = when (symbol) {
    "☰" -> "Open chat history"
    "⌄" -> "Choose model"
    "+" -> "New chat"
    "⚙" -> "Settings"
    else -> "Button"
}

@Composable
fun ChatScreen(aiName: String, session: ChatSession, sessions: List<ChatSession>, selectedModel: String, models: List<ModelInfo>, input: String, pendingAttachments: List<Attachment>, busy: Boolean, webSearchEnabled: Boolean = false, localToolsEnabled: Boolean = true, contextLabel: String = "", onInputChange: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit, onNewChat: () -> Unit, onNewTemporaryChat: () -> Unit = onNewChat, onSelectSession: (String) -> Unit, onSettings: () -> Unit, onAttach: () -> Unit, onVoice: () -> Unit, onGenerateImage: () -> Unit, onGenerateVideo: () -> Unit, imageSupported: Boolean, videoSupported: Boolean, onSelectModel: (String) -> Unit, onRemovePending: (String) -> Unit, onToggleWebSearch: () -> Unit = {}, onToggleLocalTools: () -> Unit = {}, onCopy: (String) -> Unit, onShare: (String) -> Unit, onSpeak: (String) -> Unit, onEdit: (Long, String) -> Unit, onRegenerate: (Long) -> Unit, onExport: (ChatMessage, ExportFormat) -> Unit, onExportAttachment: (Attachment) -> Unit, onDeleteSessions: (Set<String>) -> Unit, onToggleStar: (String) -> Unit, onArchive: (String) -> Unit = {}, onUnarchive: (String) -> Unit = {}, onOpenAttachment: (Attachment) -> Unit = {}, onDeleteAttachment: (Attachment) -> Unit = {}, onShareAttachment: (Attachment) -> Unit = {}, onRegenerateMedia: (ChatMessage, Attachment) -> Unit = { _, _ -> }) {
    var drawerOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(aiName, selectedModel, busy, { drawerOpen = true }, onNewChat, onSettings) { modelOpen = !modelOpen }
            MessageList(session.messages, aiName, busy, Modifier.weight(1f), onCopy, onShare, onSpeak, onEdit, onRegenerate, onExport, onExportAttachment, onOpenAttachment, onDeleteAttachment, onShareAttachment, onRegenerateMedia)
            Composer(input, pendingAttachments, busy, webSearchEnabled, localToolsEnabled, contextLabel, onInputChange, onSend, onStop, onAttach, onVoice, onGenerateImage, onGenerateVideo, imageSupported, videoSupported, onRemovePending, onToggleWebSearch, onToggleLocalTools)
        }
        if (modelOpen) Popup(alignment = Alignment.TopEnd, onDismissRequest = { modelOpen = false }) { ModelMenu(models, selectedModel) { onSelectModel(it); modelOpen = false } }
        if (drawerOpen) {
            Box(Modifier.fillMaxSize().background(LocalAppColors.current.scrim).pointerInput(Unit) { detectTapGestures { drawerOpen = false } })
            HistoryDrawer(aiName, sessions, session.id, { drawerOpen = false; onNewChat() }, { drawerOpen = false; onNewTemporaryChat() }, { drawerOpen = false; onSelectSession(it) }, { drawerOpen = false; onSettings() }, onDeleteSessions, onToggleStar, onArchive, onUnarchive)
        }
    }
}

@Composable private fun TopBar(aiName: String, selectedModel: String, busy: Boolean, onMenu: () -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit, onModel: () -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().height(60.dp).background(c.background).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderButton("☰", onMenu); Spacer(Modifier.width(8.dp)); AiOrb(Modifier.size(30.dp), busy); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { BasicText(aiName.ifBlank { "Assistant" }, color = c.text, fontSize = 15.sp, maxLines = 1); BasicText(selectedModel.ifBlank { "Auto" }, color = c.muted, fontSize = 11.sp, maxLines = 1) }
        HeaderButton("⌄", onModel); HeaderButton("+", onNewChat); HeaderButton("⚙", onSettings)
    }
}
@Composable private fun HeaderButton(symbol: String, onClick: () -> Unit) { val c = LocalAppColors.current; Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick).semantics {
            role = Role.Button
            contentDescription = iconLabel(symbol)
        }, contentAlignment = Alignment.Center) { BasicText(symbol, color = c.muted, fontSize = 21.sp) } }

@Composable private fun HistoryDrawer(aiName: String, sessions: List<ChatSession>, currentId: String, onNewChat: () -> Unit, onNewTemporaryChat: () -> Unit, onSelect: (String) -> Unit, onSettings: () -> Unit, onDeleteSessions: (Set<String>) -> Unit, onToggleStar: (String) -> Unit, onArchive: (String) -> Unit, onUnarchive: (String) -> Unit) {
    val c = LocalAppColors.current
    var query by remember { mutableStateOf("") }; var selecting by remember { mutableStateOf(false) }; var selected by remember { mutableStateOf(emptySet<String>()) }; var filter by remember { mutableStateOf("All") }
    val filtered = remember(sessions, query, filter) {
        sessions.filter {
            when (filter) {
                "Starred" -> it.starred && !it.archived
                "Archived" -> it.archived
                else -> !it.archived
            }
        }.filter { query.isBlank() || it.title.contains(query, true) || it.messages.any { m -> m.text.contains(query, true) } }
    }
    Box(Modifier.fillMaxHeight().width(320.dp).background(c.surface).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { AiOrb(Modifier.size(38.dp), true); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { BasicText(aiName, color = c.text, fontSize = 15.sp); BasicText("Chats", color = c.muted, fontSize = 12.sp) }; HeaderButton("⚙", onSettings) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f).height(44.dp).background(c.elevated, RoundedCornerShape(12.dp)).clickable(onClick = onNewChat).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { BasicText("+ New", color = c.text, fontSize = 13.sp) }
                Box(Modifier.width(74.dp).height(44.dp).background(c.elevated, RoundedCornerShape(12.dp)).clickable(onClick = onNewTemporaryChat).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) { BasicText("Temp", color = c.text, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp)); SearchField(query) { query = it }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf("All","Starred","Archived").forEach { item -> SmallPill(item, filter == item) { filter = item } }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("History", color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                BasicText(if (selecting) "Done" else "Select", color = c.accent, fontSize = 12.sp, modifier = Modifier.clickable { selecting = !selecting; selected = emptySet() }.padding(8.dp))
                if (selecting && selected.isNotEmpty()) BasicText("Delete", color = c.error, fontSize = 12.sp, modifier = Modifier.clickable { onDeleteSessions(selected); selected = emptySet(); selecting = false }.padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(filtered, key = { it.id }) { item ->
                    val isSelected = item.id in selected
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (item.id == currentId && !selecting) c.elevated else Color.Transparent).clickable { if (selecting) selected = if (isSelected) selected - item.id else selected + item.id else onSelect(item.id) }.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(if (selecting) if (isSelected) "✓" else "○" else "•", color = if (isSelected) c.accent else c.muted, fontSize = 16.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) { BasicText(item.title, color = c.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); item.preview().takeIf { it.isNotBlank() }?.let { BasicText(it, color = c.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                        if (!selecting) {
                            BasicText(if (item.starred) "★" else "☆", color = if (item.starred) c.accent else c.muted, fontSize = 16.sp, modifier = Modifier.clickable { onToggleStar(item.id) }.padding(4.dp))
                            BasicText(if (item.archived) "↗" else "⌄", color = c.muted, fontSize = 13.sp, modifier = Modifier.clickable { if (item.archived) onUnarchive(item.id) else onArchive(item.id) }.padding(5.dp))
                        }
                    }
                }
            }
            BasicText("Conversations are stored locally on this device.", color = c.muted, fontSize = 10.sp)
        }
    }
}


@Composable
private fun SmallPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(if (selected) c.accentSoft else c.background)
            .border(1.dp, if (selected) c.accent else c.border, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, color = if (selected) c.accent else c.muted, fontSize = 11.sp)
    }
}

@Composable private fun SearchField(query: String, onChange: (String) -> Unit) { val c = LocalAppColors.current; BasicTextField(value = query, onValueChange = onChange, modifier = Modifier.fillMaxWidth().height(44.dp).background(c.elevated, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 11.dp), singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 14.sp), cursorBrush = SolidColor(c.accent), decorationBox = { inner -> Box { if (query.isBlank()) BasicText("⌕  Search chats", color = c.muted, fontSize = 14.sp); inner() } }) }

@Composable
private fun ModelMenu(models: List<ModelInfo>, selected: String, onSelect: (String) -> Unit) {
    val c = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    val choices = models.filter { it.available }
        .filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) || it.provider.orEmpty().contains(query, true) }
    Column(Modifier.widthIn(min = 250.dp, max = 340.dp).background(c.surface, RoundedCornerShape(14.dp)).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(8.dp)) {
        BasicTextField(
            value = query,
            onValueChange = { query = it.take(80) },
            modifier = Modifier.fillMaxWidth().height(40.dp).background(c.elevated, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 13.sp),
            cursorBrush = SolidColor(c.accent),
            decorationBox = { inner -> Box { if (query.isBlank()) BasicText("Search models…", color = c.muted, fontSize = 13.sp); inner() } }
        )
        Spacer(Modifier.height(6.dp))
        if (choices.isEmpty()) BasicText("No matching models", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
        choices.take(20).forEach { model ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).clickable { onSelect(model.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(model.name, color = c.text, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        model.provider?.takeIf { it.isNotBlank() }?.let { BasicText(it, color = c.muted, fontSize = 9.sp) }
                        model.contextSize?.let { BasicText((it / 1000).coerceAtLeast(1).toString() + "k ctx", color = c.muted, fontSize = 9.sp) }
                        if (model.supportsVision) BasicText("Vision", color = c.muted, fontSize = 9.sp)
                        if (model.reasoning) BasicText("Reasoning", color = c.muted, fontSize = 9.sp)
                        if (model.coding) BasicText("Code", color = c.muted, fontSize = 9.sp)
                    }
                }
                if (model.id == selected) BasicText("✓", color = c.accent, fontSize = 16.sp)
            }
        }
    }
}
@Composable
private fun MessageList(messages: List<ChatMessage>, aiName: String, busy: Boolean, modifier: Modifier, onCopy: (String) -> Unit, onShare: (String) -> Unit, onSpeak: (String) -> Unit, onEdit: (Long, String) -> Unit, onRegenerate: (Long) -> Unit, onExport: (ChatMessage, ExportFormat) -> Unit, onExportAttachment: (Attachment) -> Unit, onOpenAttachment: (Attachment) -> Unit, onDeleteAttachment: (Attachment) -> Unit, onShareAttachment: (Attachment) -> Unit, onRegenerateMedia: (ChatMessage, Attachment) -> Unit) {
    val c = LocalAppColors.current
    val state = rememberLazyListState()
    var branchSelection by remember(messages) { mutableStateOf(emptyMap<Long, Int>()) }
    val branchGroups = remember(messages) {
        messages.filter { it.role == ChatMessage.Role.ASSISTANT && it.parentMessageId != null }
            .groupBy { it.parentMessageId!! }
            .filterValues { it.size > 1 }
    }
    val displayMessages = remember(messages, branchSelection) {
        messages.filter { message ->
            val siblings = message.parentMessageId?.let { branchGroups[it] }
            if (siblings.isNullOrEmpty()) true
            else message.id == siblings[(branchSelection[message.parentMessageId] ?: siblings.lastIndex).coerceIn(0, siblings.lastIndex)].id
        }
    }
    LaunchedEffect(displayMessages.lastOrNull()?.id) { if (displayMessages.isNotEmpty()) state.animateScrollToItem(displayMessages.lastIndex) }
    if (displayMessages.isEmpty()) { EmptyState(modifier); return }
    LazyColumn(state = state, modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items(displayMessages, key = { it.id }) { message ->
            val siblings = message.parentMessageId?.let { branchGroups[it] }
            if (siblings.isNullOrEmpty()) {
                MessageBlock(message, aiName, onCopy, onShare, onSpeak, onEdit, onRegenerate, onExport, onExportAttachment, onOpenAttachment, onDeleteAttachment, onShareAttachment, onRegenerateMedia)
            } else {
                val index = siblings.indexOfFirst { it.id == message.id }.coerceAtLeast(0)
                MessageBlock(message, aiName, onCopy, onShare, onSpeak, onEdit, onRegenerate, onExport, onExportAttachment, index, siblings.size) { next ->
                    branchSelection = branchSelection + (message.parentMessageId!! to next.coerceIn(0, siblings.lastIndex))
                }
            }
        }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) { AiOrb(Modifier.size(25.dp), true); Spacer(Modifier.width(8.dp)); BasicText("Thinking", color = c.muted, fontSize = 12.sp) } }
    }
}
@Composable private fun EmptyState(modifier: Modifier) { val c = LocalAppColors.current; Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) { AiOrb(Modifier.size(72.dp), true); Spacer(Modifier.height(20.dp)); BasicText("How can I help?", color = c.text, fontSize = 25.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(8.dp)); BasicText("Start a new conversation.", color = c.muted, fontSize = 14.sp) } } }

@Composable private fun MessageBlock(message: ChatMessage, aiName: String, onCopy: (String) -> Unit, onShare: (String) -> Unit, onSpeak: (String) -> Unit, onEdit: (Long, String) -> Unit, onRegenerate: (Long) -> Unit, onExport: (ChatMessage, ExportFormat) -> Unit, onExportAttachment: (Attachment) -> Unit, onOpenAttachment: (Attachment) -> Unit, onDeleteAttachment: (Attachment) -> Unit, onShareAttachment: (Attachment) -> Unit, onRegenerateMedia: (ChatMessage, Attachment) -> Unit, branchIndex: Int = -1, branchCount: Int = 0, onBranchChange: ((Int) -> Unit)? = null) {
    val c = LocalAppColors.current
    val user = message.role == ChatMessage.Role.USER
    val error = message.role == ChatMessage.Role.ERROR
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.widthIn(max = 760.dp)) {
            if (!user) AiOrb(Modifier.size(28.dp), false)
            Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                BasicText(if (user) "You" else if (error) "Error" else aiName, color = if (error) c.error else c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(5.dp))
                if (user) Box(Modifier.background(c.userBubble, ChatRadius).padding(horizontal = 15.dp, vertical = 11.dp)) { MessageText(message.text, error) } else MessageText(message.text, error)
                if (message.attachments.isNotEmpty()) { Spacer(Modifier.height(8.dp)); AttachmentList(message.attachments, onExportAttachment, onOpenAttachment, onDeleteAttachment, onShareAttachment, { attachment -> onRegenerateMedia(message, attachment) }) }
                if (message.text.isNotBlank() && !error) MessageActions(message, onCopy, onShare, onSpeak, onEdit, onRegenerate, onExport, branchIndex, branchCount, onBranchChange)
            }
        }
    }
}
@Composable private fun MessageText(text: String, error: Boolean) {
    val c = LocalAppColors.current
    if (text.isBlank()) return
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { context -> android.widget.TextView(context).apply {
            setTextIsSelectable(true)
            linksClickable = true
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            setPadding(0, 0, 0, 0)
        } },
        update = { view ->
            view.setTextColor(if (error) c.error.toArgb() else c.text.toArgb())
            view.textSize = 15f
            view.setLineSpacing(0f, 1.35f)
            view.text = android.text.Html.fromHtml(MarkdownRenderer.toSafeHtml(text), android.text.Html.FROM_HTML_MODE_LEGACY)
        },
        modifier = Modifier.fillMaxWidth()
    )
}
@Composable private fun MessageActions(message: ChatMessage, onCopy: (String) -> Unit, onShare: (String) -> Unit, onSpeak: (String) -> Unit, onEdit: (Long, String) -> Unit, onRegenerate: (Long) -> Unit, onExport: (ChatMessage, ExportFormat) -> Unit, branchIndex: Int = -1, branchCount: Int = 0, onBranchChange: ((Int) -> Unit)? = null) {
    Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ActionText("Copy") { onCopy(message.text) }
        ActionText("Share") { onShare(message.text) }
        ActionText("Read") { onSpeak(message.text) }
        if (message.role == ChatMessage.Role.USER) ActionText("Edit") { onEdit(message.id, message.text) }
        ActionText("Retry") { onRegenerate(message.id) }
        if (branchCount > 1 && onBranchChange != null) {
            ActionText("‹ " + (branchIndex + 1) + "/" + branchCount) { onBranchChange((branchIndex - 1 + branchCount) % branchCount) }
            ActionText((branchIndex + 1).toString() + "/" + branchCount + " ›") { onBranchChange((branchIndex + 1) % branchCount) }
        }
        ActionText("Export") { onExport(message, ExportFormat.MARKDOWN) }
        ActionText("TXT") { onExport(message, ExportFormat.TEXT) }
    }
}
@Composable private fun ActionText(label: String, onClick: () -> Unit) {
    BasicText(
        label,
        color = LocalAppColors.current.muted,
        fontSize = 10.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; contentDescription = label }
            .padding(horizontal = 7.dp, vertical = 5.dp)
    )
}

@Composable private fun AttachmentList(attachments: List<Attachment>, onExport: (Attachment) -> Unit, onOpen: (Attachment) -> Unit, onDelete: (Attachment) -> Unit, onShare: (Attachment) -> Unit, onRegenerate: (Attachment) -> Unit) { val c = LocalAppColors.current; LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(attachments, key = { it.id }) { attachment -> Column(Modifier.widthIn(max = 190.dp).background(c.elevated, RoundedCornerShape(10.dp)).clickable { if (attachment.mimeType.startsWith("image/") || attachment.mimeType.startsWith("video/") || attachment.kind == Attachment.Kind.GENERATED_IMAGE || attachment.kind == Attachment.Kind.GENERATED_VIDEO) onOpen(attachment) else onExport(attachment) }.padding(9.dp)) { if (attachment.kind == Attachment.Kind.IMAGE || attachment.kind == Attachment.Kind.GENERATED_IMAGE) { val bitmap = remember(attachment.localPath) { runCatching { BitmapFactory.decodeFile(attachment.localPath) }.getOrNull() }; if (bitmap != null) { androidx.compose.foundation.Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop); Spacer(Modifier.height(6.dp)) } }; BasicText(attachment.name, color = c.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); BasicText(if (attachment.mimeType.startsWith("image/") || attachment.mimeType.startsWith("video/") || attachment.kind == Attachment.Kind.GENERATED_IMAGE || attachment.kind == Attachment.Kind.GENERATED_VIDEO) "Tap to view" else "Tap to save", color = c.muted, fontSize = 9.sp) } } } }

@Composable
private fun Composer(
    input: String,
    pendingAttachments: List<Attachment>,
    busy: Boolean,
    webSearchEnabled: Boolean,
    localToolsEnabled: Boolean,
    contextLabel: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
    onGenerateImage: () -> Unit,
    onGenerateVideo: () -> Unit,
    imageSupported: Boolean,
    videoSupported: Boolean,
    onRemovePending: (String) -> Unit,
    onToggleWebSearch: () -> Unit,
    onToggleLocalTools: () -> Unit
) {
    val c = LocalAppColors.current
    var toolsOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        if (pendingAttachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 7.dp)
            ) {
                items(pendingAttachments, key = { it.id }) { file ->
                    Row(
                        Modifier
                            .background(c.elevated, RoundedCornerShape(9.dp))
                            .padding(start = 9.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            file.name,
                            color = c.text,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                        BasicText(
                            "×",
                            color = c.muted,
                            fontSize = 17.sp,
                            modifier = Modifier
                                .clickable { onRemovePending(file.id) }
                                .padding(start = 7.dp)
                        )
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .background(c.elevated, RoundedCornerShape(18.dp))
                .border(1.dp, c.border, RoundedCornerShape(18.dp))
                .padding(8.dp)
        ) {
            Column {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 42.dp, max = 130.dp)
                        .padding(horizontal = 7.dp, vertical = 7.dp),
                    textStyle = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(c.accent),
                    decorationBox = { inner ->
                        Box {
                            if (input.isBlank()) {
                                BasicText("Message your AI…", color = c.muted, fontSize = 15.sp)
                            }
                            inner()
                        }
                    }
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleTool("+") { toolsOpen = !toolsOpen }
                    CircleTool("⌕") { onVoice() }

                    if (toolsOpen) {
                        CircleTool("▣") {
                            toolsOpen = false
                            onAttach()
                        }
                        CircleTool(if (webSearchEnabled) "●" else "○") { onToggleWebSearch() }
                        CircleTool(if (localToolsEnabled) "◇" else "·") { onToggleLocalTools() }
                        if (imageSupported) CircleTool("▧") {
                            toolsOpen = false
                            onGenerateImage()
                        }
                        if (videoSupported) CircleTool("▶") {
                            toolsOpen = false
                            onGenerateVideo()
                        }
                    }

                    Spacer(Modifier.weight(1f))
                    if (contextLabel.isNotBlank()) BasicText(contextLabel, color = c.muted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 5.dp))

                    val canSend = !busy && (input.isNotBlank() || pendingAttachments.isNotEmpty())
                    val canStop = busy
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (canStop) c.error else if (canSend) c.accent else c.surface)
                            .clickable(enabled = canStop || canSend, onClick = if (canStop) onStop else onSend),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicText(
                            if (canStop) "■" else "↑",
                            color = if (canStop || canSend) Color.White else c.muted,
                            fontSize = 22.sp
                        )
                    }
                }
            }
        }

        BasicText(
            "AI can make mistakes. Check important information.",
            color = c.muted,
            fontSize = 9.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun CircleTool(symbol: String, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(symbol, color = c.muted, fontSize = 20.sp)
    }
}

@Composable
fun AiOrb(modifier: Modifier = Modifier, active: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse"
    )
    val c = LocalAppColors.current
    val finalModifier = if (active) {
        modifier.graphicsLayer(scaleX = pulse, scaleY = pulse)
    } else {
        modifier
    }

    Box(finalModifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(
                    listOf(
                        c.accent.copy(alpha = 0.95f),
                        c.accent.copy(alpha = 0.22f),
                        Color.Transparent
                    )
                ),
                radius = size.minDimension / 2f
            )
            drawCircle(
                c.accent.copy(alpha = 0.12f),
                radius = size.minDimension * 0.33f
            )
        }
    }
}
