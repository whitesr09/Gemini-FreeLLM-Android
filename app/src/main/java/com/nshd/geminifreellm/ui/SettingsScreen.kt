package com.nshd.geminifreellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nshd.geminifreellm.data.AppSettings
import com.nshd.geminifreellm.data.ConnectionCheck
import com.nshd.geminifreellm.data.StorageUsage

private const val DEFAULT_API = "https://nshd-freellm-api.onrender.com/v1"

@Composable
fun SettingsDialog(
    settings: AppSettings,
    apiKey: String,
    storageUsage: StorageUsage?,
    diagnostics: List<String>,
    connectionTesting: Boolean,
    connectionResult: ConnectionCheck?,
    connectionError: String?,
    onTestConnection: (String, String) -> Unit,
    onSave: (AppSettings, String) -> Unit,
    onDismiss: () -> Unit,
    onExportChats: () -> Unit,
    onImportChats: () -> Unit,
    onClearChats: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onMaintenance: () -> Unit,
    onClearCrashReport: () -> Unit,
    onClearCache: () -> Unit,
    onAppLock: (Boolean) -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AppDialog(
        title = "Settings",
        onDismiss = onDismiss,
        content = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp)
            ) {
                item {
                    SectionTitle("Assistant")
                    AppField(
                        draft.aiName,
                        { draft = draft.copy(aiName = it.take(40)); error = null },
                        "AI name"
                    )
                    Spacer(Modifier.height(10.dp))
                    AppField(
                        draft.systemPrompt,
                        { draft = draft.copy(systemPrompt = it.take(20_000)); error = null },
                        "System instruction",
                        singleLine = false,
                        minLines = 4
                    )
                }

                item {
                    SectionTitle("Connection")
                    AppField(
                        draft.baseUrl,
                        { draft = draft.copy(baseUrl = it); error = null },
                        "FreeLLMAPI base URL"
                    )
                    AppTextButton("Use default") { draft = draft.copy(baseUrl = DEFAULT_API) }
                    AppField(
                        key,
                        { key = it; error = null },
                        "Unified API key",
                        password = !showKey
                    )
                    AppTextButton(if (showKey) "Hide key" else "Show key") { showKey = !showKey }
                    BasicText(
                        "The key is stored with Android Keystore-backed encryption and is not included in diagnostics.",
                        color = LocalAppColors.current.muted,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    AppTextButton(if (connectionTesting) "Testing…" else "Test connection") {
                        if (!connectionTesting) onTestConnection(draft.baseUrl, key)
                    }
                    connectionError?.let {
                        BasicText(it, color = LocalAppColors.current.error, fontSize = 11.sp)
                    }
                    connectionResult?.let { check ->
                        BasicText(
                            "Reachable " + if (check.serverReachable) "✓" else "✗" +
                                " · Auth " + if (check.authenticationAccepted) "✓" else "✗" +
                                " · " + check.modelsAvailable + " models · " + check.latencyMs + " ms",
                            color = LocalAppColors.current.muted,
                            fontSize = 11.sp
                        )
                        BasicText(
                            "Vision " + if (check.visionAvailable) "✓" else "—" +
                                " · Images " + if (check.imageGenerationAvailable) "✓" else "—" +
                                " · Video " + if (check.videoGenerationAvailable) "✓" else "—",
                            color = LocalAppColors.current.muted,
                            fontSize = 11.sp
                        )
                    }
                }

                item {
                    SectionTitle("Chat behavior")
                    ToggleRow("Temporary chats by default", draft.temporaryDefault) {
                        draft = draft.copy(temporaryDefault = it)
                    }
                    ToggleRow("Web grounding by default", draft.webSearchDefault) {
                        draft = draft.copy(webSearchDefault = it)
                    }
                    ToggleRow("Local safe tools", draft.localToolsEnabled) {
                        draft = draft.copy(localToolsEnabled = it)
                    }
                    ToggleRow("Animations", draft.animationsEnabled) {
                        draft = draft.copy(animationsEnabled = it)
                    }
                    Spacer(Modifier.height(4.dp))
                    AppField(
                        draft.contextLimit.toString(),
                        {
                            draft = draft.copy(contextLimit = it.toIntOrNull()?.coerceIn(8_000, 1_000_000) ?: draft.contextLimit)
                        },
                        "Context limit (characters)"
                    )
                }

                item {
                    SectionTitle("Appearance")
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                        ThemePill("System", draft.theme == "SYSTEM") { draft = draft.copy(theme = "SYSTEM") }
                        ThemePill("Light", draft.theme == "LIGHT") { draft = draft.copy(theme = "LIGHT") }
                        ThemePill("Dark", draft.theme == "DARK") { draft = draft.copy(theme = "DARK") }
                        ThemePill("AMOLED", draft.theme == "AMOLED") { draft = draft.copy(theme = "AMOLED") }
                    }
                }

                item {
                    SectionTitle("Privacy & app lock")
                    ToggleRow("Require device authentication on resume", draft.appLockEnabled) {
                        draft = draft.copy(appLockEnabled = it)
                        onAppLock(it)
                    }
                    BasicText(
                        "Uses the Android system credential confirmation screen. Your conversations remain on-device unless you send them to your configured API.",
                        color = LocalAppColors.current.muted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }

                item {
                    SectionTitle("Chat data")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        AppTextButton("Export ZIP") { onExportChats() }
                        AppTextButton("Import ZIP") { onImportChats() }
                        Spacer(Modifier.weight(1f))
                        AppTextButton("Clear all") { onClearChats() }
                    }
                }

                item {
                    SectionTitle("Storage")
                    if (storageUsage == null) {
                        BasicText("Calculating…", color = LocalAppColors.current.muted, fontSize = 11.sp)
                    } else {
                        val u = storageUsage
                        BasicText(
                            "Attachments " + formatBytes(u.attachments) +
                                " · Images " + formatBytes(u.generatedImages) +
                                " · Videos " + formatBytes(u.generatedVideos),
                            color = LocalAppColors.current.muted,
                            fontSize = 11.sp
                        )
                        BasicText(
                            "Cache " + formatBytes(u.cache) + " · Total app-managed " + formatBytes(u.total),
                            color = LocalAppColors.current.muted,
                            fontSize = 11.sp
                        )
                        AppTextButton("Clear cache") { onClearCache() }
                    }
                }

                item {
                    SectionTitle("Diagnostics & maintenance")
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        diagnostics.take(10).forEach { line ->
                            BasicText(
                                line,
                                color = if (line.startsWith("✗")) LocalAppColors.current.error else LocalAppColors.current.muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        AppTextButton("Export report") { onExportDiagnostics() }
                        AppTextButton("Maintenance") { onMaintenance() }
                        AppTextButton("Clear crash") { onClearCrashReport() }
                    }
                }

                error?.let { message ->
                    item { BasicText(message, color = LocalAppColors.current.error, fontSize = 12.sp) }
                }
            }
        },
        actions = {
            AppTextButton("Cancel", onClick = onDismiss)
            Spacer(Modifier.width(7.dp))
            AppButton(
                "Save",
                modifier = Modifier.width(120.dp),
                onClick = {
                    val url = draft.baseUrl.trim().removeSuffix("/")
                    when {
                        draft.aiName.trim().isBlank() -> error = "Enter a name for your AI."
                        !url.startsWith("https://") -> error = "Use an HTTPS API base URL."
                        else -> onSave(draft.copy(baseUrl = url, aiName = draft.aiName.trim()), key.trim())
                    }
                }
            )
        }
    )
}

@Composable
fun AiNameOnboarding(currentName: String, onContinue: (String) -> Unit) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    AppDialog(
        title = "Name your AI",
        onDismiss = {},
        content = {
            TextBlock("Give your assistant a name. You can change it later in Settings.")
            Spacer(Modifier.height(12.dp))
            AppField(name, { name = it.take(40) }, "AI name")
        },
        actions = {
            AppButton(
                "Continue",
                enabled = name.trim().isNotBlank(),
                modifier = Modifier.width(130.dp),
                onClick = { onContinue(name.trim()) }
            )
        }
    )
}

@Composable
private fun ToggleRow(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(label, color = c.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(54.dp).height(32.dp)
                .background(if (enabled) c.accentSoft else c.background, RoundedCornerShape(16.dp))
                .border(1.dp, if (enabled) c.accent else c.border, RoundedCornerShape(16.dp))
                .clickable { onChange(!enabled) },
            contentAlignment = Alignment.Center
        ) {
            BasicText(if (enabled) "ON" else "OFF", color = if (enabled) c.accent else c.muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(text, color = LocalAppColors.current.text, fontSize = 14.sp)
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun TextBlock(text: String) {
    BasicText(text, color = LocalAppColors.current.muted, fontSize = 14.sp, lineHeight = 21.sp)
}

@Composable
private fun RowScope.ThemePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier.weight(1f)
            .height(40.dp)
            .background(if (selected) c.accentSoft else c.background, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) c.accent else c.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(label, color = if (selected) c.accent else c.muted, fontSize = 12.sp)
    }
}

private fun formatBytes(value: Long): String {
    return when {
        value >= 1024L * 1024L * 1024L -> String.format("%.1f GB", value / 1024.0 / 1024.0 / 1024.0)
        value >= 1024L * 1024L -> String.format("%.1f MB", value / 1024.0 / 1024.0)
        value >= 1024L -> String.format("%.1f KB", value / 1024.0)
        else -> value.toString() + " B"
    }
}
