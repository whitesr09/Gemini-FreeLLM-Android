package com.nshd.geminifreellm.ui

import kotlinx.coroutines.launch

import android.graphics.Bitmap
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

private val ChatRadius = RoundedCornerShape(18.dp)

private fun iconLabel(symbol: String): String = when (symbol) {
    "menu" -> "Open chat history"
    "model" -> "Choose model"
    "add" -> "New chat"
    "settings" -> "Settings"
    else -> symbol.replaceFirstChar { it.uppercase() }
}

@Composable
fun ChatScreen(aiName: String, session: ChatSession, sessions: List<ChatSession>, selectedModel: String, models: List<ModelInfo>, input: String, pendingAttachments: List<Attachment>, busy: Boolean, webSearchEnabled: Boolean = false, localToolsEnabled: Boolean = true, contextLabel: String = "", onInputChange: (String) -> Unit, onSend: () -> Unit, onStop: () -> Unit, onNewChat: () -> Unit, onNewTemporaryChat: () -> Unit = onNewChat, onSelectSession: (String) -> Unit, onSettings: () -> Unit, onAttach: () -> Unit, onVoice: () -> Unit, onGenerateImage: () -> Unit, onGenerateVideo: () -> Unit, imageSupported: Boolean, videoSupported: Boolean, onSelectModel: (String) -> Unit, onRemovePending: (String) -> Unit, onToggleWebSearch: () -> Unit = {}, onToggleLocalTools: () -> Unit = {}, onCopy: (String) -> Unit, onShare: (String) -> Unit, onSpeak: (String) -> Unit, onEdit: (Long, String) -> Unit, onRegenerate: (Long) -> Unit, onExport: (ChatMessage, ExportFormat) -> Unit, onExportAttachment: (Attachment) -> Unit, onDeleteSessions: (Set<String>) -> Unit, onToggleStar: (String) -> Unit, onArchive: (String) -> Unit = {}, onUnarchive: (String) -> Unit = {}, onRename: (String) -> Unit = {}, onOpenAttachment: (Attachment) -> Unit = {}, onDeleteAttachment: (Attachment) -> Unit = {}, onShareAttachment: (Attachment) -> Unit = {}, onRegenerateMedia: (ChatMessage, Attachment) -> Unit = { _, _ -> }, onSuggestedPrompt: (String) -> Unit = {}) {
    var drawerOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(LocalAppColors.current.background)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(aiName, selectedModel, busy, { drawerOpen = true }, onNewChat, onSettings) { modelOpen = !modelOpen }
            MessageList(session.messages, aiName, busy, Modifier.weight(1f), onCopy, onShare, onSpeak, onEdit, onRegenerate, onExport, onExportAttachment, onOpenAttachment, onDeleteAttachment, onShareAttachment, onRegenerateMedia, onSuggestedPrompt)
            Composer(input, pendingAttachments, busy, webSearchEnabled, localToolsEnabled, contextLabel, onInputChange, onSend, onStop, onAttach, onVoice, onGenerateImage, onGenerateVideo, imageSupported, videoSupported, onRemovePending, onToggleWebSearch, onToggleLocalTools)
        }
        if (modelOpen) Popup(alignment = Alignment.TopEnd, onDismissRequest = { modelOpen = false }) { ModelMenu(models, selectedModel) { onSelectModel(it); modelOpen = false } }
        if (drawerOpen) {
            Box(Modifier.fillMaxSize().background(LocalAppColors.current.scrim).pointerInput(Unit) { detectTapGestures { drawerOpen = false } })
            HistoryDrawer(aiName, sessions, session.id, { drawerOpen = false; onNewChat() }, { drawerOpen = false; onNewTemporaryChat() }, { drawerOpen = false; onSelectSession(it) }, { drawerOpen = false; onSettings() }, onDeleteSessions, onToggleStar, onArchive, onUnarchive, onRename)
        }
    }
}

@Composable private fun TopBar(aiName: String, selectedModel: String, busy: Boolean, onMenu: () -> Unit, onNewChat: () -> Unit, onSettings: () -> Unit, onModel: () -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().height(60.dp).background(c.background).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderButton("menu", onMenu); Spacer(Modifier.width(8.dp)); AiOrb(Modifier.size(30.dp), busy); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) { BasicText(aiName.ifBlank { "Assistant" }, color = c.text, fontSize = 15.sp, maxLines = 1); BasicText(selectedModel.ifBlank { "Auto" }, color = c.muted, fontSize = 11.sp, maxLines = 1) }
        HeaderButton("model", onModel); HeaderButton("add", onNewChat); HeaderButton("settings", onSettings)
    }
}
@Composable private fun HeaderButton(symbol: String, onClick: () -> Unit) { AppIconButton(symbol, iconLabel(symbol), onClick) }

@Composable private fun HistoryDrawer(aiName: String, sessions: List<ChatSession>, currentId: String, onNewChat: () -> Unit, onNewTemporaryChat: () -> Unit, onSelect: (String) -> Unit, onSettings: () -> Unit, onDeleteSessions: (Set<String>) -> Unit, onToggleStar: (String) -> Unit, onArchive: (String) -> Unit, onUnarchive: (String) -> Unit, onRename: (String) -> Unit) {
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
            Row(verticalAlignment = Alignment.CenterVertically) { AiOrb(Modifier.size(38.dp), false); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { BasicText(aiName, color = c.text, fontSize = 15.sp); BasicText("Chats", color = c.muted, fontSize = 12.sp) }; HeaderButton("settings", onSettings) }
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
                            AppIconButton(if (item.starred) "star" else "starBorder", if (item.starred) "Unstar conversation" else "Star conversation", onClick = { onToggleStar(item.id) })
                            AppIconButton(if (item.archived) "unarchive" else "archive", if (item.archived) "Unarchive conversation" else "Archive conversation", onClick = { if (item.archived) onUnarchive(item.id) else onArchive(item.id) })
                            AppIconButton("edit", "Rename conversation", onClick = { onRename(item.id) })
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
            .height(48.dp)
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

@Composable private fun SearchField(query: String, onChange: (String) -> Unit) { val c = LocalAppColors.current; BasicTextField(value = query, onValueChange = onChange, modifier = Modifier.fillMaxWidth().height(44.dp).background(c.elevated, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 11.dp), singleLine = true, textStyle = TextStyle(color = c.text, fontSize = 14.sp), cursorBrush = SolidColor(c.accent), decorationBox = { inner -> Box { if (query.isBlank()) Row(verticalAlignment = Alignment.CenterVertically) { AppIcon("search", "Search chats", Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); BasicText("Search chats", color = c.muted, fontSize = 14.sp) }; inner() } }) }

@Composable
private fun ModelMenu(models: List<ModelInfo>, selected: String, onSelect: (String) -> Unit) {
    val c = LocalAppColors.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var favorites by remember { mutableStateOf(emptySet<String>()) }
    val available = models.filter { it.available }.ifEmpty { listOf(ModelInfo("auto", "Auto", true)) }
    val filtered = available.filter { model ->
        val matches = (model.name + " " + model.id + " " + (model.provider ?: "")).contains(query, true)
        val categoryMatches = when (category) {
            "Vision" -> model.supportsVision
            "Reasoning" -> model.reasoning
            "Coding" -> model.coding
            "Favorites" -> model.id in favorites
            else -> true
        }
        matches && categoryMatches
    }.sortedWith(compareByDescending<ModelInfo> { it.id == selected }.thenByDescending { it.id in favorites }.thenBy { it.name.lowercase() })
    Column(
        Modifier.widthIn(min = 280.dp, max = 360.dp)
            .background(c.surface, RoundedCornerShape(14.dp))
            .border(1.dp, c.border, RoundedCornerShape(14.dp))
            .padding(8.dp)
    ) {
        SearchField(query) { query = it }
        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("All", "Vision", "Reasoning", "Coding", "Favorites").forEach { item ->
                SmallPill(item, category == item) { category = item }
            }
        }
        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(filtered, key = { it.id }) { model ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect(model.id) }.padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(model.name, color = c.text, fontSize = 13.sp)
                            Spacer(Modifier.width(6.dp))
                            if (model.id == selected) BasicText("Selected", color = c.accent, fontSize = 9.sp)
                        }
                        if (model.id != model.name) BasicText(model.id, color = c.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            model.provider?.let { BasicText(it, color = c.muted, fontSize = 9.sp) }
                            model.speed?.let { BasicText(it, color = c.muted, fontSize = 9.sp) }
                            model.contextSize?.let { BasicText(formatContextSize(it), color = c.muted, fontSize = 9.sp) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (model.supportsVision) BasicText("Vision", color = c.muted, fontSize = 9.sp)
                            if (model.reasoning) BasicText("Reasoning", color = c.muted, fontSize = 9.sp)
                            if (model.coding) BasicText("Coding", color = c.muted, fontSize = 9.sp)
                        }
                    }
                    AppIconButton("star", if (model.id in favorites) "Remove favorite" else "Favorite model") {
                        favorites = if (model.id in favorites) favorites - model.id else favorites + model.id
                    }
                }
            }
        }
    }
}

private fun formatContextSize(value: Long): String = if (value >= 1_000_000L) String.format("%.1fM ctx", value / 1_000_000.0) else String.format("%.0fk ctx", value / 1_000.0)
@Composable private fun MessageList(messages: List<ChatMessage>, aiName: String, busy: Boolean, modifier: Modifier, onCopy: (String) -> Unit, onShare: (String) -> Unit, onSpeak: (String) -> Unit, onEdit: (Long, String) -> Unit, onRegenerate: (Long) -> Unit, onExport: (ChatMessage, ExportFormat) -> Unit, onExportAttachment: (Attachment) -> Unit, onOpenAttachment: (Attachment) -> Unit, onDeleteAttachment: (Attachment) -> Unit, onShareAttachment: (Attachment) -> Unit, onRegenerateMedia: (ChatMessage, Attachment) -> Unit, onSuggestedPrompt: (String) -> Unit) {
    val c = LocalAppColors.current; val state = rememberLazyListState(); val scope = rememberCoroutineScope(); LaunchedEffect(messages.lastOrNull()?.id) { if (messages.isNotEmpty()) state.animateScrollToItem(messages.lastIndex) }
    if (messages.isEmpty()) { EmptyState(modifier, onSuggestedPrompt); return }
    LazyColumn(state = state, modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        itemsIndexed(messages, key = { _, it -> it.id }) { index, message ->
            val siblings = if (message.role == ChatMessage.Role.ASSISTANT && message.parentMessageId != null) {
                messages.filter { it.role == ChatMessage.Role.ASSISTANT && it.parentMessageId == message.parentMessageId }
            } else emptyList()
            val branchIndex = siblings.indexOfFirst { it.id == message.id }
            MessageBlock(message, aiName, onCopy, onShare, onSpeak, onEdit, onRegenerate, onExport, onExportAttachment, onOpenAttachment, onDeleteAttachment, onShareAttachment, onRegenerateMedia, branchIndex, siblings.size) { delta ->
                val target = siblings.getOrNull(branchIndex + delta) ?: return@MessageBlock
                val targetIndex = messages.indexOfFirst { it.id == target.id }
                if (targetIndex >= 0) scope.launch { state.animateScrollToItem(targetIndex) }
            }
        }
        if (busy) item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) { AiOrb(Modifier.size(25.dp), true); Spacer(Modifier.width(8.dp)); BasicText("Thinking", color = c.muted, fontSize = 12.sp) } }
    }
}
@Composable private fun EmptyState(modifier: Modifier,onSuggestedPrompt:(String)->Unit){
 val c=LocalAppColors.current; val prompts=listOf("Explain a topic simply","Help me write something","Analyze a document","Brainstorm ideas")
 Box(modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(DesignSpace.xxl.dp)){
  AiOrb(Modifier.size(72.dp),false);Spacer(Modifier.height(DesignSpace.lg.dp));BasicText("How can I help?",color=c.text,fontSize=27.sp,fontWeight=FontWeight.SemiBold)
  Spacer(Modifier.height(DesignSpace.sm.dp));BasicText("Ask anything, attach a file, or start with a suggestion.",color=c.muted,fontSize=14.sp,lineHeight=21.sp);Spacer(Modifier.height(DesignSpace.lg.dp))
  prompts.forEach{prompt->Box(Modifier.fillMaxWidth().padding(vertical=4.dp).heightIn(min=48.dp).clip(RoundedCornerShape(DesignRadius.card.dp)).background(c.elevated).border(1.dp,c.border,RoundedCornerShape(DesignRadius.card.dp)).clickable{onSuggestedPrompt(prompt)}.semantics{role=Role.Button;contentDescription=prompt}.padding(horizontal=DesignSpace.md.dp,vertical=DesignSpace.sm.dp),contentAlignment=Alignment.CenterStart){BasicText(prompt,color=c.text,fontSize=14.sp)}}
 }}
}

@Composable private fun MessageBlock(message: ChatMessage, aiName: String, onCopy: (String) -> Unit, onShare: (String) -> Unit, onSpeak: (String) -> Unit, onEdit: (Long, String) -> Unit, onRegenerate: (Long) -> Unit, onExport: (ChatMessage, ExportFormat) -> Unit, onExportAttachment: (Attachment) -> Unit, onOpenAttachment: (Attachment) -> Unit, onDeleteAttachment: (Attachment) -> Unit, onShareAttachment: (Attachment) -> Unit, onRegenerateMedia: (ChatMessage, Attachment) -> Unit, branchIndex: Int, branchCount: Int, onBranchNavigate: (Int) -> Unit) {
    val c = LocalAppColors.current; val user = message.role == ChatMessage.Role.USER; val error = message.role == ChatMessage.Role.ERROR
    var showDetails by remember(message.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.widthIn(max = 760.dp)) {
            if (!user) AiOrb(Modifier.size(28.dp), false)
            Column(horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                BasicText(if (user) "You" else if (error) "Error" else aiName, color = if (error) c.error else c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(5.dp))
                if (user) Box(Modifier.background(c.userBubble, ChatRadius).padding(horizontal = 15.dp, vertical = 11.dp)) { MessageText(message.text, error, onCopy) } else MessageText(message.text, error, onCopy)
                if (message.attachments.isNotEmpty()) { Spacer(Modifier.height(8.dp)); AttachmentList(message.attachments, onExportAttachment, onOpenAttachment, onDeleteAttachment, onShareAttachment, { attachment -> onRegenerateMedia(message, attachment) }) }
                if (message.text.isNotBlank() && !error) {
                    MessageActions(message, onCopy, onShare, onSpeak, onEdit, onRegenerate, onExport) { showDetails = true }
                    if (branchCount > 1) {
                        Row(Modifier.height(48.dp).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            BranchButton("‹", "Previous response", branchIndex > 0) { onBranchNavigate(-1) }
                            BasicText((branchIndex + 1).coerceAtLeast(1).toString() + "/" + branchCount, color = c.muted, fontSize = 10.sp)
                            BranchButton("›", "Next response", branchIndex in 0 until branchCount - 1) { onBranchNavigate(1) }
                        }
                    }
                }
                if (showDetails) MetadataDetailsDialog(message, onDismiss = { showDetails = false })
            }
        }
    }
}

@Composable
private fun BranchButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    BasicText(
        symbol,
        color = if (enabled) LocalAppColors.current.text else LocalAppColors.current.muted.copy(alpha = 0.35f),
        fontSize = 22.sp,
        modifier = Modifier.size(44.dp).clickable(enabled = enabled, onClick = onClick).semantics {
            role = Role.Button
            contentDescription = description
        }.padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable private fun MessageText(text:String,error:Boolean,onCopyCode:(String)->Unit){if(text.isNotBlank())MarkdownMessage(text,error,onCopyCode)}
@Composable
private fun MessageActions(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onSpeak: (String) -> Unit,
    onEdit: (Long, String) -> Unit,
    onRegenerate: (Long) -> Unit,
    onExport: (ChatMessage, ExportFormat) -> Unit,
    onDetails: () -> Unit
) {
    Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ActionText("Copy") { onCopy(message.text) }
        ActionText("Share") { onShare(message.text) }
        ActionText("Read") { onSpeak(message.text) }
        if (message.role == ChatMessage.Role.USER) ActionText("Edit") { onEdit(message.id, message.text) }
        if (message.role == ChatMessage.Role.ASSISTANT) ActionText("Retry") { onRegenerate(message.id) }
        if (message.metadata != null) ActionText("Details") { onDetails() }
        ActionText("Export") { onExport(message, ExportFormat.MARKDOWN) }
        ActionText("TXT") { onExport(message, ExportFormat.TEXT) }
        ActionText("JSON") { onExport(message, ExportFormat.JSON) }
        ActionText("HTML") { onExport(message, ExportFormat.HTML) }
    }
}

@Composable
private fun MetadataDetailsDialog(message: ChatMessage, onDismiss: () -> Unit) {
    val meta = message.metadata ?: return
    AppDialog(
        title = "Response details",
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                meta.model?.let { BasicText("Model  $it", color = LocalAppColors.current.text, fontSize = 12.sp) }
                meta.provider?.let { BasicText("Provider  $it", color = LocalAppColors.current.text, fontSize = 12.sp) }
                meta.routedVia?.let { BasicText("Route  $it", color = LocalAppColors.current.muted, fontSize = 12.sp) }
                if (meta.fallbackAttempts > 0) BasicText("Fallbacks  ${meta.fallbackAttempts}", color = LocalAppColors.current.muted, fontSize = 12.sp)
                meta.latencyMs?.let { BasicText("Latency  $it ms", color = LocalAppColors.current.muted, fontSize = 12.sp) }
                meta.requestId?.let { BasicText("Request ID  $it", color = LocalAppColors.current.muted, fontSize = 12.sp) }
                meta.tokenUsage?.let { BasicText("Usage  $it", color = LocalAppColors.current.muted, fontSize = 12.sp) }
            }
        },
        actions = { AppTextButton("Close", onClick = onDismiss) }
    )
}

@Composable private fun AttachmentList(attachments:List<Attachment>,onExport:(Attachment)->Unit,onOpen:(Attachment)->Unit,onDelete:(Attachment)->Unit,onShare:(Attachment)->Unit,onRegenerate:(Attachment)->Unit){
 val c=LocalAppColors.current;LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(attachments,key={it.id}){a->
  val media=a.mimeType.startsWith("image/")||a.mimeType.startsWith("video/")||a.kind==Attachment.Kind.GENERATED_IMAGE||a.kind==Attachment.Kind.GENERATED_VIDEO
  Column(Modifier.widthIn(max=190.dp).background(c.elevated,RoundedCornerShape(DesignRadius.control.dp)).clickable{if(media)onOpen(a)else onExport(a)}.padding(9.dp)){
   if(a.kind==Attachment.Kind.IMAGE||a.kind==Attachment.Kind.GENERATED_IMAGE){val bitmap=rememberSampledBitmap(a.localPath);if(bitmap!=null){androidx.compose.foundation.Image(bitmap.asImageBitmap(),a.name,Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop);Spacer(Modifier.height(6.dp))}}
   BasicText(a.name,color=c.text,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis);BasicText((if(media)"Tap to view"else"Tap to save")+" · "+formatBytes(a.sizeBytes),color=c.muted,fontSize=9.sp)
  }
 }}
}
@Composable private fun rememberSampledBitmap(path:String):Bitmap?{val state=produceState<Bitmap?>(initialValue=null,path){value=withContext(Dispatchers.IO){val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path,b);if(b.outWidth<=0||b.outHeight<=0)return@withContext null;val sample=maxOf(1,maxOf(b.outWidth/900,b.outHeight/900));BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565})}};return state.value}
private fun formatBytes(value:Long):String=when{value>=1024L*1024L->String.format("%.1f MB",value/1024.0/1024.0);value>=1024L->String.format("%.1f KB",value/1024.0);else->"$value B"}

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
                        .heightIn(min = 48.dp, max = 140.dp)
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
                    CircleTool("add") { toolsOpen = !toolsOpen }
                    CircleTool("mic") { onVoice() }

                    if (toolsOpen) {
                        CircleTool("attach") {
                            toolsOpen = false
                            onAttach()
                        }
                        CircleTool("web") { onToggleWebSearch() }
                        CircleTool("tools") { onToggleLocalTools() }
                        if (imageSupported) CircleTool("image") {
                            toolsOpen = false
                            onGenerateImage()
                        }
                        if (videoSupported) CircleTool("video") {
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
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (canStop) c.error else if (canSend) c.accent else c.surface)
                            .clickable(enabled = canStop || canSend, onClick = if (canStop) onStop else onSend),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(if (canStop) "stop" else "send", if (canStop) "Stop generation" else "Send message", Modifier.size(24.dp), if (canStop || canSend) Color.White else c.muted)
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
    AppIconButton(symbol, symbol.replaceFirstChar { it.uppercase() }, onClick)
}

@Composable
fun AiOrb(modifier: Modifier = Modifier, active: Boolean = false) {
    val motion = LocalMotionSettings.current
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse"
    )
    val c = LocalAppColors.current
    val finalModifier = if (active && motion.animationsEnabled) {
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
