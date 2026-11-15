package com.nshd.geminifreellm.ui

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
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
import com.nshd.geminifreellm.data.BackupManager
import com.nshd.geminifreellm.BuildConfig
import com.nshd.geminifreellm.data.FreeLlmApiClient
import com.nshd.geminifreellm.ConnectionTestResult
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onTestConnection: suspend (ProviderProfile) -> ConnectionTestResult, onSave: suspend (AppSettings) -> Boolean, onBack: () -> Unit, initialProvider: Provider = settings.selected, onDiagnostics: () -> Unit = {},
    onExportBackup: (Uri) -> Unit = {}, onImportBackup: (Uri) -> Unit = {}) {
    var profiles by remember { mutableStateOf(settings.profiles) }
    var selected by remember { mutableStateOf(initialProvider) }
    var profile by remember { mutableStateOf(settings.profiles.firstOrNull { it.provider == initialProvider } ?: ProviderProfile(initialProvider)) }
    var theme by remember { mutableStateOf(settings.theme) }
    var reducedMotion by remember { mutableStateOf(settings.reducedMotion) }
    var timestamps by remember { mutableStateOf(settings.showTimestamps) }
    var compact by remember { mutableStateOf(settings.compactSpacing) }
    var haptics by remember { mutableStateOf(settings.haptics) }
    var agent by remember { mutableStateOf(settings.agent) }
    var providerMenu by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var connectionTestToken by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var expandedSection by rememberSaveable { mutableStateOf<String?>(null) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var discard by rememberSaveable { mutableStateOf(false) }
    var openDiagnosticsAfterDiscard by remember { mutableStateOf(false) }
    // Keep the baseline stable while the user edits; upstream state changes must not
    // silently redefine what counts as an unsaved local draft.
    val initialDraft = remember { settings.copy(selected = initialProvider,
        profiles = settings.profiles + (if (settings.profiles.none { it.provider == initialProvider }) listOf(ProviderProfile(initialProvider)) else emptyList())) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)) { uri -> uri?.let(onExportBackup) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { pendingImport = it } }
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
        reducedMotion = reducedMotion, showTimestamps = timestamps, compactSpacing = compact, haptics = haptics, agent = agent)
    fun leave() { if (!saving) { if (snapshot() != initialDraft) discard = true else onBack() } }
    val latestProfile by rememberUpdatedState(profile)
    fun update(value: ProviderProfile) {
        connectionTestToken++
        working = false; status = null; models = null; profile = value
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Design.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Gemini", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text("Free LLM AI", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("Your models, one space", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                SettingsCategory(
                    title = "Connection", subtitle = "API key, provider and model management",
                    icon = Icons.Outlined.VpnKey, expanded = expandedSection == "connection",
                    onClick = { expandedSection = if (expandedSection == "connection") null else "connection" }
                )
                AnimatedVisibility(visible = expandedSection == "connection") {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
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
                        OutlinedTextField(profile.model, { update(profile.copy(model = it)) }, Modifier.fillMaxWidth(), label = { Text("Model ID") },
                            singleLine = true, supportingText = { Text("Use the exact ID from your provider. Free tiers and quotas depend on the provider.") })
                        OutlinedButton(enabled = !working && FreeLlmApiClient.validate(profile, false) == null, onClick = {
                            val testedProfile = profile
                            val requestId = ++connectionTestToken
                            scope.launch {
                                working = true; status = null; error = false
                                try {
                                    val result = onTestConnection(testedProfile)
                                    if (connectionTestToken == requestId && latestProfile == testedProfile) {
                                        models = result.models
                                        status = result.message
                                        error = result.error
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    if (connectionTestToken == requestId && latestProfile == testedProfile) {
                                        error = true
                                        status = failure.message ?: "Connection test failed."
                                    }
                                } finally {
                                    if (connectionTestToken == requestId) working = false
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (working) "Checking connection…" else "Test connection & find models") }
                        Text("Sends a tiny test message to verify your API key, then loads the model catalog. Some gateways do not provide model discovery.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        status?.let { Text(it, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium) }
                        models?.takeIf { it.isNotEmpty() }?.let { catalog ->
                            OutlinedButton(onClick = { showModels = true }) { Text("Choose from catalog") }
                            if (showModels) AlertDialog(onDismissRequest = { showModels = false }, title = { Text("Choose model") }, text = {
                                Column {
                                    OutlinedTextField(query, { query = it }, label = { Text("Search models") }, singleLine = true)
                                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                        val filtered = catalog.filter { it.contains(query, true) }
                                        items(filtered.size, key = { filtered[it] }) { index ->
                                            val id = filtered[index]
                                            TextButton(onClick = { update(profile.copy(model = id)); showModels = false }, modifier = Modifier.fillMaxWidth()) {
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
                        FreeLlmApiClient.validate(profile)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }

                SettingsCategory(
                    title = "Personalisation", subtitle = "Persona, instructions and memory",
                    icon = Icons.Outlined.PersonOutline, expanded = expandedSection == "personalisation",
                    onClick = { expandedSection = if (expandedSection == "personalisation") null else "personalisation" }
                )
                AnimatedVisibility(visible = expandedSection == "personalisation") {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
                        PersonalizationSection(agent, { agent = it }, showSkills = false)
                    }
                }

                SettingsCategory(
                    title = "Skills", subtitle = "Reusable prompts and custom workflows",
                    icon = Icons.Outlined.AutoAwesome, expanded = expandedSection == "skills",
                    onClick = { expandedSection = if (expandedSection == "skills") null else "skills" }
                )
                AnimatedVisibility(visible = expandedSection == "skills") {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
                        SkillsSection(agent, { agent = it })
                    }
                }

                SettingsCategory(
                    title = "Appearance", subtitle = "Theme, motion and chat density",
                    icon = Icons.Outlined.Palette, expanded = expandedSection == "appearance",
                    onClick = { expandedSection = if (expandedSection == "appearance") null else "appearance" }
                )
                AnimatedVisibility(visible = expandedSection == "appearance") {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
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
                    }
                }

                SettingsCategory(
                    title = "Diagnostics", subtitle = "Error log and troubleshooting",
                    icon = Icons.Outlined.BugReport, expanded = expandedSection == "diagnostics",
                    onClick = { expandedSection = if (expandedSection == "diagnostics") null else "diagnostics" }
                )
                AnimatedVisibility(visible = expandedSection == "diagnostics") {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
                        OutlinedButton(onClick = { if (snapshot() != settings) { openDiagnosticsAfterDiscard = true; discard = true } else onDiagnostics() }, modifier = Modifier.fillMaxWidth()) { Text("Error log & AI troubleshooting") }
                    }
                }

                SettingsCategory(
                    title = "Backup & Restore", subtitle = "Export or restore chats and settings",
                    icon = Icons.Outlined.CloudUpload, expanded = expandedSection == "backup",
                    onClick = { expandedSection = if (expandedSection == "backup") null else "backup" }
                )
                AnimatedVisibility(visible = expandedSection == "backup") {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
                        Text("Chats and attachments are saved in this app's private storage. Sending a message shares the conversation with the selected server. Chat exports include message and document text, never API keys. Device backup is disabled.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        
                        Text("Save all chats, settings and attachments to a file you keep, and restore it on this or another device.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                            OutlinedButton(onClick = { exportLauncher.launch(BackupManager.suggestedName()) }, modifier = Modifier.weight(1f)) { Text("Export backup") }
                            OutlinedButton(onClick = { importLauncher.launch(arrayOf(BackupManager.MIME_TYPE, "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Text("Import backup") }
                        }
                        Text("API keys are encrypted for this device: after restoring on a new device, re-enter them in the Connection section.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                SettingsCategory(
                    title = "About", subtitle = "Version and app information",
                    icon = Icons.Outlined.Info, expanded = expandedSection == "about",
                    onClick = { expandedSection = if (expandedSection == "about") null else "about" }
                )
                AnimatedVisibility(visible = expandedSection == "about") {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
                        Text("FreeLLM AI · Your models, one space", style = MaterialTheme.typography.bodyLarge)
                        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Text chat, coding canvas and compatible image/video APIs. Provider access and billing apply. Live voice calls are not supported.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(Design.large))
                    }
                }

            }
        }
        pendingImport?.let { uri ->
            AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("Restore backup?") },
                text = { Text("This replaces all current chats, settings and attachments on this device.") },
                confirmButton = { TextButton(onClick = { pendingImport = null; onImportBackup(uri); onBack() }) { Text("Restore") } },
                dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } })
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard settings changes?") },
            text = { Text("Your saved connection, personalization and appearance will be kept.") },
            confirmButton = { TextButton(onClick = { if (openDiagnosticsAfterDiscard) onDiagnostics() else onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discard = false; openDiagnosticsAfterDiscard = false }) { Text("Keep editing") } })
    }
}

@Composable
private fun SettingsCategory(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.58f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).rotate(if (expanded) 90f else 0f)
            )
        }
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
