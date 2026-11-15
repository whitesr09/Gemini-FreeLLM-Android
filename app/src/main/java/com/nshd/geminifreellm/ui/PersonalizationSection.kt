package com.nshd.geminifreellm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nshd.geminifreellm.data.readPortableText
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Personalization: persona, instructions, user-managed memory and prompt skills.
 * Persona/instructions/memory are sent with every request; skills are invoked
 * on demand by typing "/" in chat. No character limits on any field.
 */
@Composable
fun PersonalizationSection(agent: AgentConfig, onChange: (AgentConfig) -> Unit, showSkills: Boolean = true) {
    var expandedField by rememberSaveable { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
    ) {
        Column(Modifier.padding(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Personalize every model", style = MaterialTheme.typography.titleSmall)
                    Text("Saved encrypted on this device", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(agent.enabled, { onChange(agent.copy(enabled = it)) },
                    modifier = Modifier.semantics { contentDescription = "Enable personalization" })
            }
            Text(
                "Persona, instructions and memory are sent with requests, including Auto fallbacks. Models may follow instructions differently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    CompactTextSetting(
        title = "Persona",
        value = agent.persona,
        placeholder = "A patient coding partner who explains decisions",
        minLines = 2,
        expanded = expandedField == "persona",
        onToggle = { expandedField = if (expandedField == "persona") null else "persona" },
        onValueChange = { onChange(agent.copy(persona = it)) }
    )
    CompactTextSetting(
        title = "Instructions",
        value = agent.instructions,
        placeholder = "How should your assistant respond and work?",
        minLines = 3,
        expanded = expandedField == "instructions",
        onToggle = { expandedField = if (expandedField == "instructions") null else "instructions" },
        onValueChange = { onChange(agent.copy(instructions = it)) }
    )
    CompactTextSetting(
        title = "Memory",
        value = agent.memory,
        placeholder = "Preferences, project context and facts to remember",
        minLines = 3,
        supportingText = "You control memory. Edit or delete facts here; chats are not silently added.",
        expanded = expandedField == "memory",
        onToggle = { expandedField = if (expandedField == "memory") null else "memory" },
        onValueChange = { onChange(agent.copy(memory = it)) }
    )
    Text(
        "${agent.basePrompt().length} characters sent with every request",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (showSkills) SkillsSection(agent, onChange)
}

@Composable
private fun CompactTextSetting(
    title: String,
    value: String,
    placeholder: String,
    minLines: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onValueChange: (String) -> Unit,
    supportingText: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        value.ifBlank { placeholder },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (value.isNotBlank()) Text(
                        "${value.length} characters",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = if (expanded) "Collapse $title editor" else "Expand $title editor",
                    modifier = Modifier.size(22.dp).rotate(if (expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(Design.tiny)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(title) },
                        placeholder = { Text(placeholder) },
                        minLines = minLines
                    )
                    supportingText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun SkillsSection(agent: AgentConfig, onChange: (AgentConfig) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var expandedSkill by rememberSaveable { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Your skills · ${agent.skills.size}/20", style = MaterialTheme.typography.titleSmall)
            Text("Type \"/\" in chat to invoke an enabled skill.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(enabled = agent.skills.size < 20, onClick = { editId = null; error = null; editing = true }) {
            Text("Add skill")
        }
    }
    agent.skills.forEach { skill ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        expandedSkill = if (expandedSkill == skill.id) null else skill.id
                    }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(skill.name.ifBlank { "Untitled skill" }, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${skill.content.length} characters · ${if (skill.enabled) "enabled" else "disabled"}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(skill.enabled, { value ->
                        onChange(agent.copy(skills = agent.skills.map { if (it.id == skill.id) it.copy(enabled = value) else it }))
                    }, modifier = Modifier.semantics { contentDescription = "Enable ${skill.name}" })
                    Icon(Icons.Outlined.ChevronRight, if (expandedSkill == skill.id) "Collapse ${skill.name}" else "Expand ${skill.name}",
                        modifier = Modifier.size(20.dp).rotate(if (expandedSkill == skill.id) 90f else 0f))
                }
                AnimatedVisibility(visible = expandedSkill == skill.id) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editId = skill.id; error = null; editing = true }) { Text("Edit") }
                        TextButton(onClick = { onChange(agent.copy(skills = agent.skills.filterNot { it.id == skill.id })) }) {
                            Text("Remove")
                        }
                    }
                }
            }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (editing) {
        SkillEditorDialog(
            initial = agent.skills.firstOrNull { it.id == editId },
            onDismiss = { editing = false },
            onKeep = { name, content ->
                val old = agent.skills.firstOrNull { it.id == editId }
                val skill = AgentSkill(old?.id ?: newId(), name.trim(), content, old?.enabled ?: true)
                val next = if (old == null) agent.skills + skill else agent.skills.map { if (it.id == old.id) skill else it }
                val problem = agent.copy(skills = next).validationError()
                if (problem == null) { error = null; onChange(agent.copy(skills = next)); editing = false } else error = problem
            }
        )
    }
}

@Composable
private fun SkillEditorDialog(initial: AgentSkill?, onDismiss: () -> Unit, onKeep: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var content by rememberSaveable { mutableStateOf(initial?.content ?: "") }
    var url by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importJob = scope.launch {
            importing = true
            try {
                content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { readPortableText(it) } ?: error("Could not read file")
                }
                error = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not import. Choose a UTF-8 text file up to 16,000 characters." }
            finally { importing = false }
        }
    }
    Dialog(onDismissRequest = { importJob?.cancel(); onDismiss() }) {
        Card(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(Modifier.padding(Design.medium).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Design.small)) {
                Text(if (initial == null) "Add skill" else "Edit skill", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Skill name") }, singleLine = true,
                    placeholder = { Text("e.g. UI&UX") })
                OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth(), label = { Text("Skill instructions") },
                    placeholder = { Text("What should the model do when this skill is invoked?") }, minLines = 5)
                TextButton(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import text file") }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS raw text URL") }, singleLine = true)
                TextButton(enabled = !importing && url.isNotBlank(), onClick = { importJob = scope.launch {
                    importing = true
                    try {
                        val target = url.toHttpUrlOrNull()
                        require(target?.scheme == "https" && target.username.isEmpty() && target.password.isEmpty())
                        content = SkillHttp.load(target!!.toString())
                        error = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Import failed. Use a direct HTTPS text link (no login or redirects), up to 16,000 characters." }
                    finally { importing = false }
                } }) { Text(if (importing) "Importing…" else "Load preview") }
                Text("Review imported instructions before saving. Imports replace the text in this editor.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { importJob?.cancel(); onDismiss() }) { Text("Cancel") }
                    TextButton(enabled = !importing && name.isNotBlank() && content.isNotBlank(),
                        onClick = { onKeep(name, content) }) { Text("Keep skill") }
                }
            }
        }
    }
}

private object SkillHttp {
    suspend fun load(url: String): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val text = response.use { require(it.isSuccessful); (it.body ?: error("Empty response body")).byteStream().use { stream -> readPortableText(stream) } }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }

    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
}
