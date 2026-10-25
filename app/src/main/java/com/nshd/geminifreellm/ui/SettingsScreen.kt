package com.nshd.geminifreellm.ui

import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import com.nshd.geminifreellm.R
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val startingProfile = remember(settings, initialProvider) {
        settings.profiles.firstOrNull { it.id == settings.activeProfileId && it.provider == initialProvider }
            ?: settings.profiles.firstOrNull { it.provider == initialProvider }
            ?: ProviderProfile(initialProvider)
    }
    var profile by remember(startingProfile) { mutableStateOf(startingProfile) }
    var theme by remember { mutableStateOf(settings.theme) }
    var reducedMotion by remember { mutableStateOf(settings.reducedMotion) }
    var timestamps by remember { mutableStateOf(settings.showTimestamps) }
    var compact by remember { mutableStateOf(settings.compactSpacing) }
    var haptics by remember { mutableStateOf(settings.haptics) }
    var agent by remember { mutableStateOf(settings.agent) }
    var providerMenu by remember { mutableStateOf(false) }
    var expandedSection by rememberSaveable { mutableStateOf<String?>("Connection") }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var connectionTestToken by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var openDiagnosticsAfterDiscard by remember { mutableStateOf(false) }
    var pendingDeleteProfileId by remember { mutableStateOf<String?>(null) }
    // Keep the baseline stable while the user edits; upstream state changes must not
    // silently redefine what counts as an unsaved local draft.
    val initialDraft = remember(settings, initialProvider, startingProfile) {
        settings.copy(selected = initialProvider,
            profiles = if (settings.profiles.any { it.id == startingProfile.id }) settings.profiles else settings.profiles + startingProfile,
            activeProfileId = startingProfile.id)
    }
    val compatibleProfiles = (profiles.filterNot { it.id == profile.id } +
        if (profile.provider == Provider.CUSTOM) listOf(profile) else emptyList())
        .filter { it.provider == Provider.CUSTOM }.distinctBy { it.id }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val wasSecure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun snapshot() = settings.copy(
        profiles = profiles.map { if (it.id == profile.id) profile else it } +
            (if (profiles.none { it.id == profile.id }) listOf(profile) else emptyList()),
        selected = selected, activeProfileId = profile.id, theme = theme,
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
                .padding(horizontal = Design.page, vertical = Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
                Row(Modifier.fillMaxWidth().padding(vertical = Design.small), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Design.medium)) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Gemini Free LLM AI", style = MaterialTheme.typography.titleLarge)
                        Text("Configure your AI workspace", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsCategoryCard(
                    title = "Connection",
                    subtitle = "${if (selected == Provider.CUSTOM) profile.name else selected.label} · API keys and model management",
                    icon = Icons.Outlined.VpnKey,
                    expanded = expandedSection == "Connection",
                    reducedMotion = reducedMotion,
                    onClick = { expandedSection = if (expandedSection == "Connection") null else "Connection" }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
    
                    Text("Connect providers or save multiple OpenAI-compatible agents. Each saved connection keeps its own URL, key, model and capabilities.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (selected == Provider.CUSTOM) profile.name else selected.label, Modifier.weight(1f)); Icon(Icons.Outlined.KeyboardArrowDown, null)
                        }
                        DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                            Provider.entries.forEach { provider ->
                                DropdownMenuItem(text = { Text(provider.label) }, onClick = {
                                    profiles = (profiles.filterNot { it.id == profile.id } + profile).distinctBy { it.id }
                                    val next = if (provider == selected && provider == Provider.CUSTOM) profile
                                        else profiles.firstOrNull { it.provider == provider } ?: ProviderProfile(provider)
                                    selected = provider
                                    update(next)
                                    providerMenu = false
                                }, trailingIcon = { if (selected == provider) Icon(Icons.Outlined.Check, null) })
                            }
                        }
                    }

                    if (selected == Provider.CUSTOM) {
                        Column(verticalArrangement = Arrangement.spacedBy(Design.small)) {
                            Text("Saved compatible agents", style = MaterialTheme.typography.titleSmall)
                            Text("Each connection keeps its own URL, key, model and capabilities. Use Choose your AI to switch between saved agents; select one here to edit it.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = {
                                profiles = (profiles.filterNot { it.id == profile.id } + profile).distinctBy { it.id }
                                val next = ProviderProfile(provider = Provider.CUSTOM,
                                    name = "Compatible API ${compatibleProfiles.size + 1}")
                                selected = Provider.CUSTOM
                                update(next)
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("＋ Add another compatible agent")
                            }
                            compatibleProfiles.forEach { saved ->
                                Surface(
                                    shape = Design.card,
                                    color = if (saved.id == profile.id) MaterialTheme.colorScheme.secondaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(Modifier.padding(start = Design.medium, end = Design.small, top = Design.small, bottom = Design.small),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(saved.name.ifBlank { "OpenAI-compatible agent" },
                                                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(saved.baseUrl.ifBlank { "Base URL not set" }, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(saved.model.ifBlank { "No model selected" }, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (saved.id == profile.id) {
                                            Text("Editing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        } else {
                                            TextButton(onClick = {
                                                profiles = (profiles.filterNot { it.id == profile.id } + profile).distinctBy { it.id }
                                                selected = Provider.CUSTOM
                                                update(saved)
                                            }) { Text("Select / edit") }
                                        }
                                        TextButton(onClick = { pendingDeleteProfileId = saved.id }) {
                                            Text("Remove", color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                        OutlinedTextField(profile.name, { update(profile.copy(name = it.take(60))) },
                            Modifier.fillMaxWidth(), label = { Text("Connection name") }, singleLine = true,
                            supportingText = { Text("A short label to identify this endpoint when switching agents.") })
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
                    // Hoisted above the conditional: remember() inside ?.let is unsupported
                    // and can bind state to the wrong slot when the condition flips.
                    var showModels by remember { mutableStateOf(false) }
                    var query by remember { mutableStateOf("") }
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
                    }
                }
                SettingsCategoryCard(
                    title = "Personalisation",
                    subtitle = "Persona, instructions and memory",
                    icon = Icons.Outlined.PersonOutline,
                    expanded = expandedSection == "Personalisation",
                    reducedMotion = reducedMotion,
                    onClick = { expandedSection = if (expandedSection == "Personalisation") null else "Personalisation" }
                ) {
                    PersonalizationSection(agent, { agent = it })
                }
                SettingsCategoryCard(
                    title = "Skills",
                    subtitle = "${agent.skills.size} skills · reusable instructions for chat",
                    icon = Icons.Outlined.AutoAwesome,
                    expanded = expandedSection == "Skills",
                    reducedMotion = reducedMotion,
                    onClick = { expandedSection = if (expandedSection == "Skills") null else "Skills" }
                ) {
                    SkillsSection(agent, { agent = it })
                }
                SettingsCategoryCard(
                    title = "Appearance",
                    subtitle = "Theme, motion and conversation layout",
                    icon = Icons.Outlined.Palette,
                    expanded = expandedSection == "Appearance",
                    reducedMotion = reducedMotion,
                    onClick = { expandedSection = if (expandedSection == "Appearance") null else "Appearance" }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
    
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                        ThemeMode.entries.forEach { mode ->
                            val previewColor = when (mode) {
                                ThemeMode.SYSTEM -> Color(0xFF64748B)
                                ThemeMode.LIGHT -> Color(0xFF176B87)
                                ThemeMode.DARK -> Color(0xFF8BD8F0)
                                ThemeMode.AMOLED -> Color(0xFF11191D)
                                ThemeMode.VIOLET -> Color(0xFF783EC8)
                                ThemeMode.CHATGPT -> Color(0xFF16856B)
                            }
                            FilterChip(
                                selected = theme == mode.name,
                                onClick = { theme = mode.name },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                                        Box(Modifier.size(12.dp).clip(CircleShape).background(previewColor))
                                        Text(mode.label)
                                    }
                                },
                                leadingIcon = { if (theme == mode.name) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) }
                            )
                        }
                    }
                    Text("App icon", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text("Switch the launcher icon. The launcher refreshes within a few seconds; the app itself keeps running.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    var iconChoice by remember { mutableStateOf(LauncherIcon.currentChoice(context)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                        listOf(
                            Triple("Current", R.drawable.ic_launcher_foreground, LauncherIcon.Choice.CURRENT),
                            Triple("Gold", R.drawable.ic_launcher_glow_foreground, LauncherIcon.Choice.GOLD),
                            Triple("Blue", R.drawable.ic_launcher_blue_foreground, LauncherIcon.Choice.BLUE)
                        ).forEach { (label, drawable, choice) ->
                            val selected = iconChoice == choice
                            Surface(
                                modifier = Modifier.weight(1f).clickable { iconChoice = choice; LauncherIcon.setChoice(context, choice) },
                                shape = Design.card,
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Column(Modifier.padding(Design.small), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Design.small)) {
                                    Image(painterResource(drawable), contentDescription = label, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                                        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                    SettingSwitch("Reduce motion", "Use static indicators and immediate transitions.", reducedMotion) { reducedMotion = it }
                    SettingSwitch("Message timestamps", "Show send time and response timing below replies.", timestamps) { timestamps = it }
                    SettingSwitch("Compact conversations", "Use tighter spacing between messages.", compact) { compact = it }
                    SettingSwitch("Touch feedback", "Light haptic feedback when sending a message.", haptics) { haptics = it }
                    }
                }
                SettingsCategoryCard(
                    title = "Backup & Restore",
                    subtitle = "Export or restore chats, settings and attachments",
                    icon = Icons.Outlined.CloudUpload,
                    expanded = expandedSection == "Backup & Restore",
                    reducedMotion = reducedMotion,
                    onClick = { expandedSection = if (expandedSection == "Backup & Restore") null else "Backup & Restore" }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
                    Text("Privacy & storage", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    
                    Text("Chats and attachments are saved in this app's private storage. Sending a message shares the conversation with the selected server. Chat exports include message and document text, never API keys. Device backup is disabled.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Backup & restore", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    
                    Text("Save all chats, settings and attachments to a file you keep, and restore it on this or another device.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)) { uri ->
                        uri?.let(onExportBackup)
                    }
                    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        uri?.let { pendingImport = it }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                        OutlinedButton(onClick = { exportLauncher.launch(BackupManager.suggestedName()) }, modifier = Modifier.weight(1f)) { Text("Export backup") }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf(BackupManager.MIME_TYPE, "application/octet-stream")) }, modifier = Modifier.weight(1f)) { Text("Import backup") }
                    }
                    Text("API keys are encrypted for this device: after restoring on a new device, re-enter them in the Connection section.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsCategoryCard(
                    title = "About",
                    subtitle = "Version ${BuildConfig.VERSION_NAME} · diagnostics and app information",
                    icon = Icons.Outlined.Info,
                    expanded = expandedSection == "About",
                    reducedMotion = reducedMotion,
                    onClick = { expandedSection = if (expandedSection == "About") null else "About" }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
    
                    OutlinedButton(onClick = { if (snapshot() != initialDraft) { openDiagnosticsAfterDiscard = true; discard = true } else onDiagnostics() }, modifier = Modifier.fillMaxWidth()) { Text("Error log & AI troubleshooting") }
    
                    Text("FreeLLM AI · Your models, one space", style = MaterialTheme.typography.bodyLarge)
                    Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Text chat, coding canvas and compatible image/video APIs. Provider access and billing apply. Live voice calls are not supported.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FreeLlmApiClient.validate(profile)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                Spacer(Modifier.height(Design.large))
            }
        }
        pendingImport?.let { uri ->
            AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("Restore backup?") },
                text = { Text("This replaces all current chats, settings and attachments on this device.") },
                confirmButton = { TextButton(onClick = { pendingImport = null; onImportBackup(uri); onBack() }) { Text("Restore") } },
                dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } })
        }

        pendingDeleteProfileId?.let { deletingId ->
            val deleting = compatibleProfiles.firstOrNull { it.id == deletingId }
            AlertDialog(
                onDismissRequest = { pendingDeleteProfileId = null },
                title = { Text("Remove compatible agent?") },
                text = { Text("Remove \"${deleting?.name ?: "this agent"}\" from saved connections? This does not affect the remote server account.") },
                confirmButton = {
                    TextButton(onClick = {
                        val removedActive = profile.id == deletingId
                        val remaining = (profiles.filterNot { it.id == deletingId || it.id == profile.id } +
                            if (profile.id != deletingId) listOf(profile) else emptyList())
                            .distinctBy { it.id }
                        profiles = remaining
                        if (removedActive) {
                            val fallback = remaining.firstOrNull { it.provider == Provider.CUSTOM }
                                ?: remaining.firstOrNull() ?: ProviderProfile()
                            selected = fallback.provider
                            update(fallback)
                        }
                        pendingDeleteProfileId = null
                    }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { pendingDeleteProfileId = null }) { Text("Cancel") } }
            )
        }

        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard settings changes?") },
            text = { Text("Your saved connection, personalization and appearance will be kept.") },
            confirmButton = { TextButton(onClick = { if (openDiagnosticsAfterDiscard) onDiagnostics() else onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { discard = false; openDiagnosticsAfterDiscard = false }) { Text("Keep editing") } })
    }
}

@Composable
private fun SettingsCategoryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    reducedMotion: Boolean,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Surface(
                onClick = onClick,
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "$title settings, ${if (expanded) "expanded" else "collapsed"}" }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Design.medium, vertical = Design.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Design.medium)
                ) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = if (reducedMotion) EnterTransition.None else expandVertically() + fadeIn(tween(160)),
                exit = if (reducedMotion) ExitTransition.None else shrinkVertically() + fadeOut(tween(120))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Design.medium).padding(bottom = Design.medium),
                    verticalArrangement = Arrangement.spacedBy(Design.medium),
                    content = content
                )
            }
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
