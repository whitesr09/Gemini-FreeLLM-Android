package com.nshd.geminifreellm.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nshd.geminifreellm.data.ApiException
import com.nshd.geminifreellm.BuildConfig
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, client: FreeLlmApiClient, onSave: suspend (AppSettings) -> Boolean, onBack: () -> Unit, initialProvider: Provider = settings.selected, onDiagnostics: () -> Unit = {}) {
    var profiles by remember { mutableStateOf(settings.profiles) }
    var selected by remember { mutableStateOf(initialProvider) }
    var profile by remember { mutableStateOf(settings.profiles.firstOrNull { it.provider == initialProvider } ?: ProviderProfile(initialProvider)) }
    var theme by remember { mutableStateOf(settings.theme) }
    var reducedMotion by remember { mutableStateOf(settings.reducedMotion) }
    var timestamps by remember { mutableStateOf(settings.showTimestamps) }
    var compact by remember { mutableStateOf(settings.compactSpacing) }
    var haptics by remember { mutableStateOf(settings.haptics) }
    var providerMenu by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var openDiagnosticsAfterDiscard by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val wasSecure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun snapshot() = settings.copy(profiles = profiles.map { if (it.provider == selected) profile else it } +
        (if (profiles.none { it.provider == selected }) listOf(profile) else emptyList()), selected = selected, theme = theme,
        reducedMotion = reducedMotion, showTimestamps = timestamps, compactSpacing = compact, haptics = haptics)
    fun leave() { if (!saving) { if (snapshot() != settings) discard = true else onBack() } }
    fun update(value: ProviderProfile) {
        probe?.cancel(); working = false; status = null; models = null; profile = value
    }
    BackHandler { leave() }
    GeminiTheme(runCatching { ThemeMode.valueOf(theme) }.getOrDefault(ThemeMode.SYSTEM), reducedMotion) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("Settings") }, navigationIcon = {
                IconButton(onClick = { leave() }, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                TextButton(enabled = !saving && FreeLlmApiClient.validate(profile) == null, onClick = {
                    scope.launch {
                        saving = true
                        if (onSave(snapshot())) onBack()
                        else { error = true; status = "Could not securely save settings. Check available device storage and try again." }
                        saving = false
                    }
                }) { Text(if (saving) "Saving…" else "Save") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = Design.page, vertical = Design.medium), verticalArrangement = Arrangement.spacedBy(Design.medium)) {
                SectionTitle("AI connection")
                Text("Use a provider key or connect a unified API gateway. Each provider keeps its own settings.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selected.label, Modifier.weight(1f)); Icon(Icons.Outlined.KeyboardArrowDown, null)
                    }
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        Provider.entries.forEach { provider ->
                            DropdownMenuItem(text = { Text(provider.label) }, onClick = {
                                profiles = profiles.filterNot { it.provider == selected } + profile
                                selected = provider
                                update(profiles.firstOrNull { it.provider == provider } ?: ProviderProfile(provider))
                                providerMenu = false
                            }, trailingIcon = { if (selected == provider) Icon(Icons.Outlined.Check, null) })
                        }
                    }
                }
                if (selected == Provider.DEEPSEEK) Text("Use a DeepSeek API key with api.deepseek.com. Keys from a gateway belong in that gateway's profile. Discover model IDs below; a chat subscription does not imply API credit.", style = MaterialTheme.typography.bodySmall)
                if (selected == Provider.OLLAMA) Text("On a phone, use your computer's LAN address and enable server access. 10.0.2.2 is for Android emulators.", style = MaterialTheme.typography.bodySmall)
                val url = profile.baseUrl.toHttpUrlOrNull()
                OutlinedTextField(profile.baseUrl, { update(profile.copy(baseUrl = it)) }, Modifier.fillMaxWidth(),
                    label = { Text("API base URL") }, placeholder = { Text("https://your-provider.com/v1") }, singleLine = true,
                    supportingText = { Text("Include the API version path, for example /v1. Requests go to this server.") })
                if (url?.scheme == "http") {
                    Text(if (url.host in listOf("localhost", "127.0.0.1", "::1", "10.0.2.2"))
                        "Local HTTP connection. On Android, 127.0.0.1 means this phone; use your server's LAN address when needed."
                    else "Unencrypted HTTP: your key and messages can be read in transit. Use HTTPS for remote servers.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(profile.apiKey, { update(profile.copy(apiKey = it)) }, Modifier.fillMaxWidth(),
                    label = { Text(if (selected == Provider.FREELLM) "Unified API key" else "API key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    supportingText = { Text("Encrypted on this device with Android Keystore. Leave empty only for a server that allows it.") })
                SectionTitle("Model & capabilities")
                OutlinedTextField(profile.model, { update(profile.copy(model = it)) }, Modifier.fillMaxWidth(), label = { Text("Model ID") },
                    singleLine = true, supportingText = { Text("Use the exact ID from your provider. Free tiers and quotas depend on the provider.") })
                OutlinedButton(enabled = !working && FreeLlmApiClient.validate(profile, false) == null, onClick = {
                    probe = scope.launch {
                        working = true; status = null; error = false
                        try {
                            models = client.models(profile)
                            status = if (models.isNullOrEmpty()) "Connected, but no models were returned. Enter a model ID manually."
                                else "Connected · ${models!!.size} models returned. Select one below."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = true; status = (failure as? ApiException)?.message ?: "Connection test failed." }
                        finally { working = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (working) "Checking connection…" else "Test connection & find models") }
                Text("Checks the model catalog without sending a chat. Some gateways do not provide model discovery.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.let { Text(it, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium) }
                models?.takeIf { it.isNotEmpty() }?.let { catalog ->
                    var showModels by remember { mutableStateOf(false) }
                    var query by remember { mutableStateOf("") }
                    OutlinedButton(onClick = { showModels = true }) { Text("Choose from catalog") }
                    if (showModels) AlertDialog(onDismissRequest = { showModels = false }, title = { Text("Choose model") }, text = {
                        Column {
                            OutlinedTextField(query, { query = it }, label = { Text("Search models") }, singleLine = true)
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                val filtered = catalog.filter { it.contains(query, true) }
                                items(filtered.size, key = { filtered[it] }) { index ->
                                    val id = filtered[index]
                                    TextButton(onClick = { profile = profile.copy(model = id); showModels = false }, modifier = Modifier.fillMaxWidth()) {
                                        Text(id, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }, confirmButton = { TextButton(onClick = { showModels = false }) { Text("Done") } })
                }
                SettingSwitch("Stream responses", "Turn off if your gateway only returns complete replies.", profile.stream) { update(profile.copy(stream = it)) }
                SettingSwitch("Image input", "Enable only when the selected model supports vision.", profile.vision) { update(profile.copy(vision = it)) }
                if (selected == Provider.GEMINI) SettingSwitch("Faster Gemini replies", "Uses low thinking for Gemini 3 Flash/Pro, or disables it for 2.5 Flash/Flash-Lite. Other IDs keep provider defaults. May reduce reasoning quality.", profile.fastReplies) { update(profile.copy(fastReplies = it)) }
                SectionTitle("Appearance")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(selected = theme == mode.name, onClick = { theme = mode.name }, label = { Text(mode.label) },
                            leadingIcon = { if (theme == mode.name) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) })
                    }
                }
                SettingSwitch("Reduce motion", "Use static indicators and immediate transitions.", reducedMotion) { reducedMotion = it }
                SettingSwitch("Message timestamps", "Show send time and response timing below replies.", timestamps) { timestamps = it }
                SettingSwitch("Compact conversations", "Use tighter spacing between messages.", compact) { compact = it }
                SettingSwitch("Touch feedback", "Light haptic feedback when sending a message.", haptics) { haptics = it }
                SectionTitle("Diagnostics")
                OutlinedButton(onClick = { if (snapshot() != settings) { openDiagnosticsAfterDiscard = true; discard = true } else onDiagnostics() }, modifier = Modifier.fillMaxWidth()) { Text("Error log & AI troubleshooting") }
                SectionTitle("Privacy & storage")
                Text("Chats and attachments are saved in this app's private storage. Sending a message shares the conversation with the selected server. Chat exports include message and document text, never API keys. Device backup is disabled.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionTitle("About")
                Text("FreeLLM AI · Your models, one space", style = MaterialTheme.typography.bodyLarge)
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Text chat, coding canvas and compatible image/video APIs. Provider access and billing apply. Live voice calls are not supported.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FreeLlmApiClient.validate(profile)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(Design.large))
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard settings changes?") },
            text = { Text("Your saved connection and appearance will be kept.") },
            confirmButton = { TextButton(onClick = { if (openDiagnosticsAfterDiscard) onDiagnostics() else onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discard = false; openDiagnosticsAfterDiscard = false }) { Text("Keep editing") } })
    }
}

@Composable private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = Design.medium).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable private fun SettingSwitch(title: String, description: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.medium)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange, modifier = Modifier.semantics { this.contentDescription = title })
    }
}
