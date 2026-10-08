package com.nshd.geminifreellm.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nshd.geminifreellm.data.readPortableText
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(initial: AgentConfig, onSave: suspend (AgentConfig) -> Boolean, onBack: () -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var persona by rememberSaveable { mutableStateOf(initial.persona) }
    var instructions by rememberSaveable { mutableStateOf(initial.instructions) }
    var memory by rememberSaveable { mutableStateOf(initial.memory) }
    // JSON saver keeps skills, including unsaved edits, across activity recreation.
    var skillJson by rememberSaveable { mutableStateOf(com.nshd.geminifreellm.data.agentToJson(initial).toString()) }
    val skills = remember(skillJson) { com.nshd.geminifreellm.data.agentFromJson(org.json.JSONObject(skillJson)).skills }
    fun setSkills(value: List<AgentSkill>) { skillJson = com.nshd.geminifreellm.data.agentToJson(AgentConfig(skills = value)).toString() }
    fun snapshot() = AgentConfig(enabled, persona, instructions, memory, skills)
    var editing by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var importing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun leave() { if (!saving) { if (snapshot() != initial) discard = true else onBack() } }
    fun closeEditor() { scope.launch { importJob?.cancelAndJoin(); importing = false; editing = false } }
    BackHandler { if (editing) closeEditor() else leave() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && editing) importJob = scope.launch {
            importing = true
            try { content = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readPortableText(it) } ?: error("Could not read file") }; error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not import. Choose a UTF-8 text file up to 16,000 characters." }
            finally { importing = false }
        }
    }
    if (editing) {
        Scaffold(topBar = { TopAppBar(title = { Text(if (editId == null) "Add skill" else "Edit skill") },
            navigationIcon = { IconButton(onClick = { closeEditor() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Cancel skill edit") } },
            actions = { TextButton(enabled = !importing && name.isNotBlank() && content.isNotBlank(), onClick = {
                val old = skills.firstOrNull { it.id == editId }
                val skill = AgentSkill(old?.id ?: newId(), name.trim(), content, old?.enabled ?: true)
                val next = if (old == null) skills + skill else skills.map { if (it.id == old.id) skill else it }
                error = snapshot().copy(skills = next).validationError()
                if (error == null) { setSkills(next); editing = false }
            }) { Text("Keep skill") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(Design.page), verticalArrangement = Arrangement.spacedBy(Design.small)) {
            OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Skill name") }, singleLine = true)
            OutlinedTextField(content, { content = it.take(16_000) }, Modifier.fillMaxWidth(), label = { Text("Skill instructions") }, minLines = 4, maxLines = 8)
            TextButton(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import text file") }
            OutlinedTextField(url, { url = it.take(2048) }, Modifier.fillMaxWidth(), label = { Text("HTTPS raw text URL") }, singleLine = true)
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
            Text("Review imported instructions before saving. Imports replace the text in this editor.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        }
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Agent studio") }, navigationIcon = {
        IconButton(onClick = { leave() }, enabled = !saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close agent studio") }
    }, actions = { TextButton(enabled = !saving, onClick = { scope.launch {
        val value = snapshot()
        error = value.validationError()
        if (error == null) { saving = true; if (onSave(value)) onBack() else error = "Could not save agent settings."; saving = false }
    } }) { Text("Save agent") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(Design.page), verticalArrangement = Arrangement.spacedBy(Design.medium)) {
            Card { Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Personalize every model", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Switch(enabled, { enabled = it }) }
                Text("Persona, instructions, memory and enabled skills are sent with each chat request, including Auto fallbacks. Saved encrypted on this device. Models may follow instructions differently.", style = MaterialTheme.typography.bodySmall)
            } }
            OutlinedTextField(persona, { persona = it.take(4000) }, Modifier.fillMaxWidth(), label = { Text("Persona") }, placeholder = { Text("A patient coding partner who explains decisions") }, minLines = 2, maxLines = 5)
            OutlinedTextField(instructions, { instructions = it.take(8000) }, Modifier.fillMaxWidth(), label = { Text("Instructions") }, placeholder = { Text("How should your assistant respond and work?") }, minLines = 3, maxLines = 6)
            OutlinedTextField(memory, { memory = it.take(8000) }, Modifier.fillMaxWidth(), label = { Text("Memory") }, placeholder = { Text("Preferences, project context and facts to remember") }, minLines = 3, maxLines = 6,
                supportingText = { Text("You control memory. Edit or delete facts here; chats are not silently added.") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Skills · ${skills.size}/20", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(enabled = skills.size < 20, onClick = { editId = null; name = ""; content = ""; url = ""; error = null; editing = true }) { Text("Add skill") }
            }
            Text("Import prompt skills from any source as plain text: paste, choose a file (including SKILL.md, JSON or YAML), or use an HTTPS raw-text link. Scripts, plugins and tools are not executed.", style = MaterialTheme.typography.bodySmall)
            skills.forEach { skill ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(Design.medium)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(skill.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Switch(skill.enabled, { value -> setSkills(skills.map { if (it.id == skill.id) it.copy(enabled = value) else it }) })
                    }
                    Text("${skill.content.length} characters", style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(onClick = { editId = skill.id; name = skill.name; content = skill.content; url = ""; error = null; editing = true }) { Text("Edit") }
                        TextButton(onClick = { setSkills(skills.filterNot { it.id == skill.id }) }) { Text("Remove") }
                    }
                } }
            }
            Text("${snapshot().prompt().length}/48,000 active context characters", style = MaterialTheme.typography.labelSmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard agent changes?") }, text = { Text("Your previously saved agent settings will be kept.") },
        confirmButton = { TextButton(onClick = onBack) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}
private object SkillHttp {
    suspend fun load(url: String): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val text = response.use { require(it.isSuccessful); it.body!!.byteStream().use { stream -> readPortableText(stream) } }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }

    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
}
