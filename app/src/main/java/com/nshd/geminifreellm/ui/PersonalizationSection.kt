package com.nshd.geminifreellm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
fun PersonalizationSection(agent: AgentConfig, onChange: (AgentConfig) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Personalize every model", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(agent.enabled, { onChange(agent.copy(enabled = it)) })
            }
            Text("Persona, instructions and memory travel with each chat request, including Auto fallbacks. Saved encrypted on this device. Models may follow instructions differently.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    OutlinedTextField(agent.persona, { onChange(agent.copy(persona = it)) }, Modifier.fillMaxWidth(),
        label = { Text("Persona") }, placeholder = { Text("A patient coding partner who explains decisions") }, minLines = 2)
    OutlinedTextField(agent.instructions, { onChange(agent.copy(instructions = it)) }, Modifier.fillMaxWidth(),
        label = { Text("Instructions") }, placeholder = { Text("How should your assistant respond and work?") }, minLines = 3)
    OutlinedTextField(agent.memory, { onChange(agent.copy(memory = it)) }, Modifier.fillMaxWidth(),
        label = { Text("Memory") }, placeholder = { Text("Preferences, project context and facts to remember") }, minLines = 3,
        supportingText = { Text("You control memory. Edit or delete facts here; chats are not silently added.") })
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Design.small)) {
        Text("Skills · ${agent.skills.size}/20", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(enabled = agent.skills.size < 20, onClick = { editId = null; error = null; editing = true }) { Text("Add skill") }
    }
    Text("Type \"/\" in any chat to list your enabled skills and attach one to a message. Imports accept plain text: paste, choose a file (including SKILL.md, JSON or YAML), or use an HTTPS raw-text link. Scripts, plugins and tools are not executed.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    agent.skills.forEach { skill ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.tiny)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(skill.name.ifBlank { "Untitled skill" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(skill.enabled, { value ->
                        onChange(agent.copy(skills = agent.skills.map { if (it.id == skill.id) it.copy(enabled = value) else it }))
                    })
                }
                Text("${skill.content.length} characters · ${if (skill.enabled) "type \"/${skill.name}\" in chat to use" else "disabled"}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { editId = skill.id; error = null; editing = true }) { Text("Edit") }
                    TextButton(onClick = { onChange(agent.copy(skills = agent.skills.filterNot { it.id == skill.id })) }) { Text("Remove") }
                }
            }
        }
    }
    Text("${agent.basePrompt().length} characters sent with every request",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
