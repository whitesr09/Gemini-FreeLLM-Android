package com.nshd.geminifreellm.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import com.nshd.geminifreellm.data.ExportFormat
import com.nshd.geminifreellm.data.ModelInfo
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ChatSession

private val ChatRadius=RoundedCornerShape(16.dp)

@Composable
fun ChatScreen(
    aiName:String,session:ChatSession,sessions:List<ChatSession>,selectedModel:String,models:List<ModelInfo>,
    input:String,pendingAttachments:List<Attachment>,busy:Boolean,webSearchEnabled:Boolean=false,localToolsEnabled:Boolean=true,
    localRagEnabled:Boolean=false,structuredOutputEnabled:Boolean=false,contextLabel:String="",
    onInputChange:(String)->Unit,onSend:()->Unit,onStop:()->Unit,onNewChat:()->Unit,onNewTemporaryChat:()->Unit=onNewChat,
    onSelectSession:(String)->Unit,onSettings:()->Unit,onAttach:()->Unit,onVoice:()->Unit,onGenerateImage:()->Unit,onGenerateVideo:()->Unit,
    imageSupported:Boolean,videoSupported:Boolean,onSelectModel:(String)->Unit,onRemovePending:(String)->Unit,
    onToggleWebSearch:()->Unit={},onToggleLocalTools:()->Unit={},onToggleLocalRag:()->Unit={},onToggleStructuredOutput:()->Unit={},
    onCopy:(String)->Unit,onShare:(String)->Unit,onSpeak:(String)->Unit,onEdit:(Long,String)->Unit,onRegenerate:(Long)->Unit,
    onExport:(ChatMessage,ExportFormat)->Unit,onExportAttachment:(Attachment)->Unit,onDeleteSessions:(Set<String>)->Unit,onToggleStar:(String)->Unit,
    onArchive:(String)->Unit={},onUnarchive:(String)->Unit={},onRename:(String,String)->Unit={_,_->},
    onOpenAttachment:(Attachment)->Unit={},onDeleteAttachment:(Attachment)->Unit={},onShareAttachment:(Attachment)->Unit={},
    onRegenerateMedia:(ChatMessage,Attachment)->Unit={_,_->}
){
    var drawerOpen by rememberSaveable{mutableStateOf(false)}
    var modelOpen by rememberSaveable{mutableStateOf(false)}
    var editing by remember{mutableStateOf<ChatMessage?>(null)}
    var renameId by remember{mutableStateOf<String?>(null)}
    var renameText by rememberSaveable{mutableStateOf("")}
    val favoriteModels=remember{mutableStateSetOf<String>()}
    Box(Modifier.fillMaxSize().background(LocalAppColors.current.background)){
        Column(Modifier.fillMaxSize()){
            TopBar(aiName,selectedModel,busy,{drawerOpen=true},onNewChat,onSettings){modelOpen=!modelOpen}
            MessageList(session.messages,aiName,busy,Modifier.weight(1f),onCopy,onShare,onSpeak,{id,_->editing=session.messages.firstOrNull{it.id==id}},
                onRegenerate,onExport,onExportAttachment,onOpenAttachment,onDeleteAttachment,onShareAttachment,onRegenerateMedia)
            Composer(input,pendingAttachments,busy,webSearchEnabled,localToolsEnabled,localRagEnabled,structuredOutputEnabled,contextLabel,
                onInputChange,onSend,onStop,onAttach,onVoice,onGenerateImage,onGenerateVideo,imageSupported,videoSupported,onRemovePending,
                onToggleWebSearch,onToggleLocalTools,onToggleLocalRag,onToggleStructuredOutput)
        }
        if(modelOpen)Popup(alignment=Alignment.TopEnd,onDismissRequest={modelOpen=false}){
            ModelMenu(models,selectedModel,favoriteModels,{id->if(!favoriteModels.add(id))favoriteModels.remove(id)}){onSelectModel(it);modelOpen=false}
        }
        if(drawerOpen){
            Box(Modifier.fillMaxSize().background(LocalAppColors.current.scrim).pointerInput(Unit){detectTapGestures{drawerOpen=false}})
            HistoryDrawer(aiName,sessions,session.id,{drawerOpen=false;onNewChat()},{drawerOpen=false;onNewTemporaryChat()},{drawerOpen=false;onSelectSession(it)},
                {drawerOpen=false;onSettings()},{id->renameId=id;renameText=sessions.firstOrNull{it.id==id}?.title.orEmpty()},
                onDeleteSessions,onToggleStar,onArchive,onUnarchive)
        }
        editing?.let{m->EditMessageDialog(m.text,{editing=null}){v->onEdit(m.id,v);editing=null}}
        renameId?.let{id->RenameSessionDialog(renameText,{renameId=null}){v->onRename(id,v);renameId=null}}
    }
}

@Composable private fun TopBar(aiName:String,selectedModel:String,busy:Boolean,onMenu:()->Unit,onNewChat:()->Unit,onSettings:()->Unit,onModel:()->Unit){
    val c=LocalAppColors.current
    Row(Modifier.fillMaxWidth().height(60.dp).background(c.background).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
        HeaderButton("☰","Open chat history",onMenu);Spacer(Modifier.width(8.dp));AiOrb(Modifier.size(30.dp),busy);Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)){BasicText(aiName.ifBlank{"Assistant"},color=c.text,fontSize=15.sp,maxLines=1);BasicText(selectedModel.ifBlank{"Auto"},color=c.muted,fontSize=11.sp,maxLines=1)}
        HeaderButton("⌄","Choose model",onModel);HeaderButton("+","New chat",onNewChat);HeaderButton("⚙","Settings",onSettings)
    }
}
@Composable private fun HeaderButton(symbol:String,description:String,onClick:()->Unit){val c=LocalAppColors.current;Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick=onClick).semantics{role=Role.Button;contentDescription=description},contentAlignment=Alignment.Center){BasicText(symbol,color=c.muted,fontSize=21.sp)}}

@Composable private fun HistoryDrawer(aiName:String,sessions:List<ChatSession>,currentId:String,onNewChat:()->Unit,onNewTemporaryChat:()->Unit,onSelect:(String)->Unit,onSettings:()->Unit,onRename:(String)->Unit,onDeleteSessions:(Set<String>)->Unit,onToggleStar:(String)->Unit,onArchive:(String)->Unit,onUnarchive:(String)->Unit){
    val c=LocalAppColors.current
    var query by rememberSaveable{mutableStateOf("")};var selecting by rememberSaveable{mutableStateOf(false)};var selected by remember{mutableStateOf(emptySet<String>())};var filter by rememberSaveable{mutableStateOf("All")};var sort by rememberSaveable{mutableStateOf("Recent")}
    val filtered=remember(sessions,query,filter,sort){
        val base=sessions.filter{when(filter){"Starred"->it.starred&&!it.archived;"Archived"->it.archived;else->!it.archived}}
            .filter{query.isBlank()||it.title.contains(query,true)||it.messages.any{m->m.text.contains(query,true)}}
        if(sort=="A-Z")base.sortedBy{it.title.lowercase()}else base.sortedByDescending{it.updatedAt}
    }
    Box(Modifier.fillMaxHeight().width(320.dp).background(c.surface).pointerInput(Unit){detectTapGestures{}}){
        Column(Modifier.fillMaxSize().padding(14.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){AiOrb(Modifier.size(38.dp),true);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){BasicText(aiName,color=c.text,fontSize=15.sp);BasicText("Chats",color=c.muted,fontSize=12.sp)};HeaderButton("⚙","Settings",onSettings)}
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Box(Modifier.weight(1f).height(44.dp).background(c.elevated,RoundedCornerShape(12.dp)).clickable(onClick=onNewChat),contentAlignment=Alignment.Center){BasicText("+ New",color=c.text,fontSize=13.sp)}
                Box(Modifier.width(74.dp).height(44.dp).background(c.elevated,RoundedCornerShape(12.dp)).clickable(onClick=onNewTemporaryChat),contentAlignment=Alignment.Center){BasicText("Temp",color=c.text,fontSize=12.sp)}
            }
            Spacer(Modifier.height(10.dp));SearchField(query){query=it}
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                listOf("All","Starred","Archived").forEach{item->SmallPill(item,filter==item){filter=item}}
                SmallPill(sort,false){sort=if(sort=="Recent")"A-Z" else "Recent"}
            }
            Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
                BasicText("History",color=c.text,fontSize=13.sp,modifier=Modifier.weight(1f))
                BasicText(if(selecting)"Done" else "Select",color=c.accent,fontSize=12.sp,modifier=Modifier.clickable{selecting=!selecting;selected=emptySet()}.padding(8.dp))
                if(selecting&&selected.isNotEmpty())BasicText("Delete",color=c.error,fontSize=12.sp,modifier=Modifier.clickable{onDeleteSessions(selected);selected=emptySet();selecting=false}.padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(bottom=8.dp)){
                items(filtered,key={it.id}){item->
                    val isSelected=item.id in selected
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if(item.id==currentId&&!selecting)c.elevated else Color.Transparent)
                        .clickable{if(selecting)selected=if(isSelected)selected-item.id else selected+item.id else onSelect(item.id)}.padding(10.dp),verticalAlignment=Alignment.CenterVertically){
                        BasicText(if(selecting)if(isSelected)"✓" else "○" else "•",color=if(isSelected)c.accent else c.muted,fontSize=16.sp);Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)){BasicText(item.title,color=c.text,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis);item.preview().takeIf{it.isNotBlank()}?.let{BasicText(it,color=c.muted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}}
                        if(!selecting){ActionText(if(item.starred)"★" else "☆"){onToggleStar(item.id)};ActionText("✎"){onRename(item.id)};ActionText(if(item.archived)"↗" else "⌄"){if(item.archived)onUnarchive(item.id)else onArchive(item.id)}}
                    }
                }
            }
            BasicText("Chats stay on this device unless you send them to your API.",color=c.muted,fontSize=10.sp)
        }
    }
}
@Composable private fun SearchField(query:String,onChange:(String)->Unit){val c=LocalAppColors.current;BasicTextField(query,onChange,singleLine=true,modifier=Modifier.fillMaxWidth().height(44.dp).background(c.elevated,RoundedCornerShape(12.dp)).padding(horizontal=14.dp,vertical=11.dp),textStyle=TextStyle(color=c.text,fontSize=14.sp),cursorBrush=SolidColor(c.accent),decorationBox={inner->Box{if(query.isBlank())BasicText("⌕  Search",color=c.muted,fontSize=14.sp);inner()}})}
@Composable private fun SmallPill(label:String,selected:Boolean,onClick:()->Unit){val c=LocalAppColors.current;BasicText(label,color=if(selected)c.background else c.muted,fontSize=10.sp,modifier=Modifier.clip(RoundedCornerShape(12.dp)).background(if(selected)c.accent else c.elevated).clickable(onClick=onClick).semantics{role=Role.Button;contentDescription=label}.padding(horizontal=10.dp,vertical=7.dp))}
@Composable private fun ModelMenu(models:List<ModelInfo>,selected:String,favorites:MutableSet<String>,onToggleFavorite:(String)->Unit,onSelect:(String)->Unit){
    val c=LocalAppColors.current;var query by rememberSaveable{mutableStateOf("")};var filter by rememberSaveable{mutableStateOf("All")};var sort by rememberSaveable{mutableStateOf("Name")}
    val visible=remember(models,query,filter,sort,favorites){(models.ifEmpty{listOf(ModelInfo("auto","Auto",true))}).filter{it.available}.filter{query.isBlank()||it.name.contains(query,true)||it.id.contains(query,true)||(it.provider?.contains(query,true)==true)}.filter{
        when(filter){"Vision"->it.supportsVision;"Reasoning"->it.reasoning;"Coding"->it.coding;"Fav"->it.id in favorites;else->true}
    }.let{list->if(sort=="Capability")list.sortedByDescending{(if(it.supportsVision)4 else 0)+(if(it.reasoning)2 else 0)+(if(it.coding)1 else 0)+(if(it.supportsImageGeneration)1 else 0)+(if(it.supportsVideoGeneration)1 else 0)}else list.sortedBy{it.name.lowercase()}}}
    Column(Modifier.widthIn(min=280.dp,max=360.dp).background(c.surface,RoundedCornerShape(14.dp)).border(1.dp,c.border,RoundedCornerShape(14.dp)).padding(8.dp)){
        SearchField(query){query=it}
        Row(Modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("All","Vision","Reasoning","Coding","Fav").forEach{item->SmallPill(item,filter==item){filter=item}};SmallPill(sort,false){sort=if(sort=="Name")"Capability" else "Name"}}
        LazyColumn(Modifier.heightIn(max=440.dp)){items(visible,key={it.id}){model->Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable{onSelect(model.id)}.padding(10.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){BasicText(model.name,color=c.text,fontSize=13.sp,fontWeight=FontWeight.Medium);if(model.id!=model.name)BasicText(model.id,color=c.muted,fontSize=10.sp)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){model.provider?.let{BasicText(it,color=c.muted,fontSize=9.sp)};model.speed?.let{BasicText("· "+it,color=c.muted,fontSize=9.sp)};model.contextSize?.let{BasicText("· "+(it/1000)+"k ctx",color=c.muted,fontSize=9.sp)}}
                val caps=buildList{if(model.supportsVision)add("Vision");if(model.reasoning)add("Reasoning");if(model.coding)add("Coding");if(model.supportsImageGeneration)add("Image");if(model.supportsVideoGeneration)add("Video")}
                if(caps.isNotEmpty())BasicText(caps.joinToString(" · "),color=c.accent,fontSize=9.sp)
            }
            ActionText(if(model.id in favorites)"★" else "☆"){onToggleFavorite(model.id)};if(model.id==selected)BasicText("✓",color=c.accent,fontSize=16.sp)
        }}}
    }
}

private data class BranchInfo(val index:Int,val total:Int,val previousId:Long?,val nextId:Long?)
@Composable private fun MessageList(messages:List<ChatMessage>,aiName:String,busy:Boolean,modifier:Modifier,onCopy:(String)->Unit,onShare:(String)->Unit,onSpeak:(String)->Unit,onEdit:(Long,String)->Unit,onRegenerate:(Long)->Unit,onExport:(ChatMessage,ExportFormat)->Unit,onExportAttachment:(Attachment)->Unit,onOpenAttachment:(Attachment)->Unit,onDeleteAttachment:(Attachment)->Unit,onShareAttachment:(Attachment)->Unit,onRegenerateMedia:(ChatMessage,Attachment)->Unit){
    val state=rememberLazyListState();val selectedBranches=remember{mutableStateMapOf<Long,Long>()};val groups=remember(messages){messages.filter{it.role==ChatMessage.Role.ASSISTANT&&it.parentMessageId!=null}.groupBy{it.parentMessageId!!}}
    LaunchedEffect(messages.lastOrNull()?.id){if(messages.isNotEmpty())state.animateScrollToItem(messages.lastIndex)}
    if(messages.isEmpty()){EmptyState(modifier);return}
    LazyColumn(state=state,modifier=modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=14.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        items(messages,key={it.id}){message->
            val parent=message.parentMessageId;val siblings=parent?.let{groups[it].orEmpty()}.orEmpty()
            if(siblings.size>1){
                val selectedId=selectedBranches[parent!!]?:siblings.first().id
                if(message.id==selectedId){
                    val idx=siblings.indexOfFirst{it.id==message.id}
                    MessageBlock(message,aiName,onCopy,onShare,onSpeak,onEdit,onRegenerate,onExport,onExportAttachment,onOpenAttachment,onDeleteAttachment,onShareAttachment,onRegenerateMedia,
                        BranchInfo(idx,siblings.size,siblings.getOrNull(idx-1)?.id,siblings.getOrNull(idx+1)?.id)){id->selectedBranches[parent]=id}
                }
            }else MessageBlock(message,aiName,onCopy,onShare,onSpeak,onEdit,onRegenerate,onExport,onExportAttachment,onOpenAttachment,onDeleteAttachment,onShareAttachment,onRegenerateMedia,null,{})
        }
        if(busy)item{Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(horizontal=4.dp)){AiOrb(Modifier.size(25.dp),true);Spacer(Modifier.width(8.dp));BasicText("Thinking",color=LocalAppColors.current.muted,fontSize=12.sp)}}
    }
}
@Composable private fun EmptyState(modifier:Modifier){val c=LocalAppColors.current;Box(modifier.fillMaxSize(),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(32.dp)){AiOrb(Modifier.size(72.dp),true);Spacer(Modifier.height(20.dp));BasicText("How can I help?",color=c.text,fontSize=25.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(8.dp));BasicText("Start a new conversation.",color=c.muted,fontSize=14.sp)}}}

@Composable private fun MessageBlock(message:ChatMessage,aiName:String,onCopy:(String)->Unit,onShare:(String)->Unit,onSpeak:(String)->Unit,onEdit:(Long,String)->Unit,onRegenerate:(Long)->Unit,onExport:(ChatMessage,ExportFormat)->Unit,onExportAttachment:(Attachment)->Unit,onOpenAttachment:(Attachment)->Unit,onDeleteAttachment:(Attachment)->Unit,onShareAttachment:(Attachment)->Unit,onRegenerateMedia:(ChatMessage,Attachment)->Unit,branch:BranchInfo?,onSelectBranch:(Long)->Unit){
    val c=LocalAppColors.current;val user=message.role==ChatMessage.Role.USER;val error=message.role==ChatMessage.Role.ERROR;var showDetails by rememberSaveable(message.id){mutableStateOf(false)}
    Column(Modifier.fillMaxWidth(),horizontalAlignment=if(user)Alignment.End else Alignment.Start){Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(9.dp),modifier=Modifier.widthIn(max=820.dp)){
        if(!user)AiOrb(Modifier.size(28.dp),false)
        Column(horizontalAlignment=if(user)Alignment.End else Alignment.Start){
            BasicText(if(user)"You" else if(error)"Error" else aiName,color=if(error)c.error else c.text,fontSize=12.sp,fontWeight=FontWeight.Medium);Spacer(Modifier.height(5.dp))
            if(user)Box(Modifier.background(c.userBubble,ChatRadius).padding(horizontal=15.dp,vertical=11.dp)){MarkdownText(message.text,error,onCopy,onShare)}else MarkdownText(message.text,error,onCopy,onShare)
            if(message.attachments.isNotEmpty()){Spacer(Modifier.height(8.dp));AttachmentList(message.attachments,onExportAttachment,onOpenAttachment,onDeleteAttachment,onShareAttachment){onRegenerateMedia(message,it)}}
            if(message.text.isNotBlank()&&!error)MessageActions(message,onCopy,onShare,onSpeak,onEdit,onRegenerate,onExport,showDetails){showDetails=!showDetails}
            branch?.let{b->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)){ActionText("‹"){b.previousId?.let{id->onSelectBranch(id)}};BasicText((b.index+1).toString()+"/"+b.total,color=c.muted,fontSize=10.sp);ActionText("›"){b.nextId?.let{id->onSelectBranch(id)}}}}
            message.metadata?.takeIf{showDetails}?.let{DetailsPanel(it)}
        }
    }}
}
@Composable private fun MarkdownText(text:String,error:Boolean,onCopy:(String)->Unit,onShare:(String)->Unit){MarkdownRenderer(markdown=text,onCopyCode=onCopy,onShareCode=onShare,isError=error)}
@Composable private fun MessageActions(message:ChatMessage,onCopy:(String)->Unit,onShare:(String)->Unit,onSpeak:(String)->Unit,onEdit:(Long,String)->Unit,onRegenerate:(Long)->Unit,onExport:(ChatMessage,ExportFormat)->Unit,details:Boolean,onToggleDetails:()->Unit){Row(Modifier.padding(top=7.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)){
    ActionText("Copy"){onCopy(message.text)};ActionText("Share"){onShare(message.text)};ActionText("Read"){onSpeak(message.text)}
    if(message.role==ChatMessage.Role.USER)ActionText("Edit"){onEdit(message.id,message.text)}else ActionText("Retry"){onRegenerate(message.id)}
    message.metadata?.let{ActionText(if(details)"Hide" else "Details",onToggleDetails)}
    ActionText("MD"){onExport(message,ExportFormat.MARKDOWN)};ActionText("JSON"){onExport(message,ExportFormat.JSON)};ActionText("HTML"){onExport(message,ExportFormat.HTML)};ActionText("TXT"){onExport(message,ExportFormat.TEXT)}
}}
@Composable private fun DetailsPanel(meta:com.nshd.geminifreellm.model.ResponseMetadata){val c=LocalAppColors.current;Column(Modifier.fillMaxWidth().padding(top=5.dp).background(c.elevated,RoundedCornerShape(9.dp)).padding(9.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
    meta.model?.let{BasicText("Model: "+it,color=c.muted,fontSize=10.sp)};meta.provider?.let{BasicText("Provider: "+it,color=c.muted,fontSize=10.sp)};meta.routedVia?.let{BasicText("Route: "+it,color=c.muted,fontSize=10.sp)}
    meta.latencyMs?.let{BasicText("Latency: "+it+" ms",color=c.muted,fontSize=10.sp)};if(meta.fallbackAttempts>0)BasicText("Fallbacks: "+meta.fallbackAttempts,color=c.muted,fontSize=10.sp);meta.requestId?.let{BasicText("Request: "+it.take(80),color=c.muted,fontSize=10.sp)};meta.tokenUsage?.let{BasicText("Usage: "+it,color=c.muted,fontSize=10.sp)}
}}
@Composable private fun ActionText(label:String,onClick:()->Unit){BasicText(label,color=LocalAppColors.current.muted,fontSize=10.sp,modifier=Modifier.clickable(onClick=onClick).semantics{role=Role.Button;contentDescription=label}.padding(horizontal=7.dp,vertical=5.dp))}

@Composable private fun AttachmentList(attachments:List<Attachment>,onExport:(Attachment)->Unit,onOpen:(Attachment)->Unit,onDelete:(Attachment)->Unit,onShare:(Attachment)->Unit,onRegenerate:(Attachment)->Unit){
    val c=LocalAppColors.current
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(attachments,key={it.id}){attachment->
        val media=attachment.mimeType.startsWith("image/")||attachment.mimeType.startsWith("video/")||attachment.kind==Attachment.Kind.GENERATED_IMAGE||attachment.kind==Attachment.Kind.GENERATED_VIDEO
        Column(Modifier.widthIn(max=200.dp).background(c.elevated,RoundedCornerShape(10.dp)).clickable{if(media)onOpen(attachment)else onExport(attachment)}.padding(9.dp)){
            if(attachment.mimeType.startsWith("image/")||attachment.kind==Attachment.Kind.GENERATED_IMAGE){
                val thumb=remember(attachment.localPath){runCatching{val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(attachment.localPath,b);val sample=maxOf(1,maxOf(b.outWidth,b.outHeight)/420);BitmapFactory.decodeFile(attachment.localPath,BitmapFactory.Options().apply{inSampleSize=sample})}.getOrNull()}
                thumb?.let{androidx.compose.foundation.Image(it.asImageBitmap(),attachment.name,Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Crop);Spacer(Modifier.height(6.dp))}
            }
            BasicText(attachment.name,color=c.text,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis);BasicText(if(media)"Tap to view" else "Tap to save",color=c.muted,fontSize=9.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){ActionText("Share"){onShare(attachment)};ActionText("Delete"){onDelete(attachment)};if(attachment.kind==Attachment.Kind.GENERATED_IMAGE||attachment.kind==Attachment.Kind.GENERATED_VIDEO)ActionText("Retry"){onRegenerate(attachment)}}
        }
    }}
}
@Composable private fun Composer(input:String,pendingAttachments:List<Attachment>,busy:Boolean,webSearchEnabled:Boolean,localToolsEnabled:Boolean,localRagEnabled:Boolean,structuredOutputEnabled:Boolean,contextLabel:String,onInputChange:(String)->Unit,onSend:()->Unit,onStop:()->Unit,onAttach:()->Unit,onVoice:()->Unit,onGenerateImage:()->Unit,onGenerateVideo:()->Unit,imageSupported:Boolean,videoSupported:Boolean,onRemovePending:(String)->Unit,onToggleWebSearch:()->Unit,onToggleLocalTools:()->Unit,onToggleLocalRag:()->Unit,onToggleStructuredOutput:()->Unit){
    val c=LocalAppColors.current;var toolsOpen by rememberSaveable{mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().background(c.background).padding(horizontal=12.dp,vertical=9.dp)){
        if(pendingAttachments.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=7.dp)){items(pendingAttachments,key={it.id}){file->Row(Modifier.background(c.elevated,RoundedCornerShape(9.dp)).padding(start=9.dp,end=5.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically){BasicText(file.name,color=c.text,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=140.dp));ActionText("×"){onRemovePending(file.id)}}}}
        Box(Modifier.fillMaxWidth().background(c.elevated,RoundedCornerShape(18.dp)).border(1.dp,c.border,RoundedCornerShape(18.dp)).padding(8.dp)){Column{
            BasicTextField(input,onInputChange,modifier=Modifier.fillMaxWidth().heightIn(min=42.dp,max=150.dp).padding(horizontal=7.dp,vertical=7.dp),textStyle=TextStyle(color=c.text,fontSize=15.sp,lineHeight=22.sp),cursorBrush=SolidColor(c.accent),decorationBox={inner->Box{if(input.isBlank())BasicText("Message your AI…",color=c.muted,fontSize=15.sp);inner()}})
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                CircleTool("+","Composer tools"){toolsOpen=!toolsOpen};CircleTool("⌕","Voice input",onVoice);if(toolsOpen){
                    CircleTool("▣","Attach file",onAttach);CircleTool(if(webSearchEnabled)"●" else "○","Web search",onToggleWebSearch);CircleTool(if(localToolsEnabled)"◇" else "·","Local tools",onToggleLocalTools)
                    CircleTool(if(localRagEnabled)"R" else "r","Local document retrieval",onToggleLocalRag);CircleTool(if(structuredOutputEnabled)"J" else "j","Structured JSON output",onToggleStructuredOutput)
                    if(imageSupported)CircleTool("▧","Generate image",onGenerateImage);if(videoSupported)CircleTool("▶","Generate video",onGenerateVideo)
                }
                Spacer(Modifier.weight(1f));if(contextLabel.isNotBlank())BasicText(contextLabel,color=c.muted,fontSize=9.sp,modifier=Modifier.padding(horizontal=5.dp))
                val canSend=!busy&&(input.isNotBlank()||pendingAttachments.isNotEmpty())
                Box(Modifier.size(48.dp).clip(CircleShape).background(if(busy)c.error else if(canSend)c.accent else c.surface).clickable(enabled=busy||canSend,onClick=if(busy)onStop else onSend).semantics{role=Role.Button;contentDescription=if(busy)"Stop generation" else "Send message"},contentAlignment=Alignment.Center){BasicText(if(busy)"■" else "↑",color=if(busy||canSend)Color.White else c.muted,fontSize=22.sp)}
            }
        }}
        BasicText("AI can make mistakes. Check important information.",color=c.muted,fontSize=9.sp,modifier=Modifier.align(Alignment.CenterHorizontally).padding(top=4.dp))
    }
}
@Composable private fun CircleTool(symbol:String,description:String,onClick:()->Unit){val c=LocalAppColors.current;Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick=onClick).semantics{role=Role.Button;contentDescription=description},contentAlignment=Alignment.Center){BasicText(symbol,color=c.muted,fontSize=19.sp)}}
@Composable private fun EditMessageDialog(initial:String,onDismiss:()->Unit,onSave:(String)->Unit){var text by rememberSaveable(initial){mutableStateOf(initial)};AppDialog("Edit message",onDismiss,{AppField(text,{text=it.take(120_000)},"Message",singleLine=false,minLines=5)},{AppTextButton("Cancel",onDismiss);AppButton("Send",enabled=text.isNotBlank(),modifier=Modifier.width(100.dp),onClick={onSave(text.trim())})})}
@Composable private fun RenameSessionDialog(initial:String,onDismiss:()->Unit,onSave:(String)->Unit){var text by rememberSaveable(initial){mutableStateOf(initial)};AppDialog("Rename chat",onDismiss,{AppField(text,{text=it.take(100)},"Chat name")},{AppTextButton("Cancel",onDismiss);AppButton("Save",enabled=text.isNotBlank(),modifier=Modifier.width(100.dp),onClick={onSave(text.trim())})})}
@Composable fun AiOrb(modifier:Modifier=Modifier,active:Boolean=false){val transition=rememberInfiniteTransition(label="orb");val pulse by transition.animateFloat(.92f,1.06f,infiniteRepeatable(tween(1200),RepeatMode.Reverse),label="pulse");val c=LocalAppColors.current;Box(if(active)modifier.graphicsLayer(scaleX=pulse,scaleY=pulse) else modifier,contentAlignment=Alignment.Center){Canvas(Modifier.fillMaxSize()){drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha=.95f),c.accent.copy(alpha=.22f),Color.Transparent)),radius=size.minDimension/2f);drawCircle(c.accent.copy(alpha=.12f),radius=size.minDimension*.33f)}}}
