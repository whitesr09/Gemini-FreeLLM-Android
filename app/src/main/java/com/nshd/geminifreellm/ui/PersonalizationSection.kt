package com.nshd.geminifreellm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
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
fun PersonalizationSection(agent: AgentConfig, onChange: (AgentConfig) -> Unit, showSkills: Boolean = false) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var personaExpanded by rememberSaveable { mutableStateOf(false) }
    var instructionsExpanded by rememberSaveable { mutableStateOf(false) }
    var memoryExpanded by rememberSaveable { mutableStateOf(false) }
    var expandedSkills by rememberSaveable { mutableStateOf(setOf<String>()) }

    if (!showSkills) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Personalize every model", style = MaterialTheme.typography.titleSmall)
                        Text("Saved on this device · included with requests when enabled",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(agent.enabled, { onChange(agent.copy(enabled = it)) },
                        modifier = Modifier.semantics { contentDescription = "Enable personalization" })
                }
            }
        }
        ExpandableTextSetting(
            title = "Persona",
            summary = if (agent.persona.isBlank()) "Not configured" else "${agent.persona.length} characters",
            expanded = personaExpanded,
            onToggle = { personaExpanded = !personaExpanded }
        ) {
            OutlinedTextField(agent.persona, { onChange(agent.copy(persona = it)) }, Modifier.fillMaxWidth(),
                label = { Text("Persona") }, placeholder = { Text("A patient coding partner who explains decisions") }, minLines = 2)
        }
        ExpandableTextSetting(
            title = "Instructions",
            summary = if (agent.instructions.isBlank()) "Not configured" else "${agent.instructions.length} characters",
            expanded = instructionsExpanded,
            onToggle = { instructionsExpanded = !instructionsExpanded }
        ) {
            OutlinedTextField(agent.instructions, { onChange(agent.copy(instructions = it)) }, Modifier.fillMaxWidth(),
                label = { Text("Instructions") }, placeholder = { Text("How should your assistant respond and work?") }, minLines = 3)
        }
        ExpandableTextSetting(
            title = "Memory",
            summary = if (agent.memory.isBlank()) "No saved facts" else "${agent.memory.length} characters",
            expanded = memoryExpanded,
            onToggle = { memoryExpanded = !memoryExpanded }
        ) {
            OutlinedTextField(agent.memory, { onChange(agent.copy(memory = it)) }, Modifier.fillMaxWidth(),
                label = { Text("Memory") }, placeholder = { Text("Preferences, project context and facts to remember") }, minLines = 3,
                supportingText = { Text("You control memory. Edit or delete facts here; chats are not silently added.") })
        }
        Text("Persona, instructions and memory can accompany each chat request, including Auto fallbacks. Models may follow instructions differently.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Design.small)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Skills · ${agent.skills.size}/20", style = MaterialTheme.typography.titleSmall)
                Text("Type \"/\" in chat to invoke an enabled skill.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(enabled = agent.skills.size < 20, onClick = { editId = null; error = null; editing = true }) { Text("Add skill") }
        }
        Text("Import plain text by pasting, choosing a file (including SKILL.md, JSON or YAML), or using an HTTPS raw-text link. Scripts, plugins and tools are not executed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (agent.skills.isEmpty()) {
            Text("No skills yet. Add a skill to create reusable instructions for your chats.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        agent.skills.forEach { skill ->
            Card(
                Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column {
                    Surface(onClick = {
                        expandedSkills = if (skill.id in expandedSkills) expandedSkills - skill.id else expandedSkills + skill.id
                    }, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = Design.medium, vertical = Design.small), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(skill.name.ifBlank { "Untitled skill" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(if (skill.enabled) "Enabled · ${skill.content.length} characters" else "Disabled · ${skill.content.length} characters",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(skill.enabled, { value ->
                                onChange(agent.copy(skills = agent.skills.map { if (it.id == skill.id) it.copy(enabled = value) else it }))
                            }, modifier = Modifier.semantics { contentDescription = "Enable ${skill.name}" })
                            Icon(if (skill.id in expandedSkills) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = if (skill.id in expandedSkills) "Collapse skill" else "Expand skill")
                        }
                    }
                    AnimatedVisibility(skill.id in expandedSkills) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Design.small), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { editId = skill.id; error = null; editing = true }) { Text("Edit") }
                            TextButton(onClick = { onChange(agent.copy(skills = agent.skills.filterNot { it.id == skill.id })); expandedSkills = expandedSkills - skill.id }) { Text("Remove") }
                        }
                    }
                }
            }
        }
        Text("${agent.basePrompt().length} characters sent with every request",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (showSkills && editing) {
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
fun SkillsSection(agent: AgentConfig, onChange: (AgentConfig) -> Unit) {
    PersonalizationSection(agent, onChange, showSkills = true)
}

@Composable
private fun ExpandableTextSetting(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Surface(
                onClick = onToggle,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = Design.medium, vertical = Design.small),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title"
                    )
                }
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Design.medium).padding(bottom = Design.medium),
                    verticalArrangement = Arrangement.spacedBy(Design.small),
                    content = content
                )
            }
        }
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
