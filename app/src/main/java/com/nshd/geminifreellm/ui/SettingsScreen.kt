package com.nshd.geminifreellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val DEFAULT_API = "https://nshd-freellm-api.onrender.com/v1"

@Composable
fun SettingsDialog(
    baseUrl: String,
    apiKey: String,
    aiName: String,
    themeMode: ThemeMode,
    onSave: (String, String, String, ThemeMode) -> Unit,
    onDismiss: () -> Unit,
    onExportChats: () -> Unit,
    onImportChats: () -> Unit,
    onClearChats: () -> Unit
) {
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    var name by remember(aiName) { mutableStateOf(aiName) }
    var theme by remember(themeMode) { mutableStateOf(themeMode) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AppDialog(
        title = "Settings",
        onDismiss = onDismiss,
        content = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                item {
                    SectionTitle("Assistant")
                    AppField(name, { name = it.take(40); error = null }, "AI name", singleLine = true)
                }
                item {
                    SectionTitle("Connection")
                    AppField(url, { url = it; error = null }, "FreeLLMAPI base URL", singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppTextButton("Restore default") { url = DEFAULT_API; error = null }
                        Spacer(Modifier.weight(1f))
                        BasicText("Your API key stays local to this app.", color = LocalAppColors.current.muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    AppField(key, { key = it; error = null }, "Unified API key", singleLine = true, password = !showKey)
                    Spacer(Modifier.height(4.dp))
                    AppTextButton(if (showKey) "Hide key" else "Show key") { showKey = !showKey }
                }
                item {
                    SectionTitle("Appearance")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ThemePill("System", theme == ThemeMode.SYSTEM) { theme = ThemeMode.SYSTEM }
                        ThemePill("Light", theme == ThemeMode.LIGHT) { theme = ThemeMode.LIGHT }
                        ThemePill("Dark", theme == ThemeMode.DARK) { theme = ThemeMode.DARK }
                        ThemePill("AMOLED", theme == ThemeMode.AMOLED) { theme = ThemeMode.AMOLED }
                    }
                }
                item {
                    SectionTitle("Chat data")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        AppTextButton("Export") { onExportChats() }
                        AppTextButton("Import") { onImportChats() }
                        Spacer(Modifier.weight(1f))
                        AppTextButton("Clear") { onClearChats() }
                    }
                }
                if (error != null) item {
                    BasicText(error!!, color = LocalAppColors.current.error, fontSize = 12.sp)
                }
            }
        },
        actions = {
            AppTextButton("Cancel", onDismiss)
            Spacer(Modifier.width(8.dp))
            AppButton(
                "Save",
                onClick = {
                    val normalized = normalizeBaseUrl(url)
                    when {
                        name.trim().isBlank() -> error = "Enter a name for your AI."
                        normalized.isBlank() || !(normalized.startsWith("http://") || normalized.startsWith("https://")) -> error = "Enter a valid http/https base URL."
                        else -> onSave(normalized, key.trim(), name.trim(), theme)
                    }
                },
                modifier = Modifier.width(120.dp)
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
            AppField(name, { name = it.take(40) }, "AI name", singleLine = true)
        },
        actions = {
            AppButton("Continue", { if (name.trim().isNotBlank()) onContinue(name.trim()) }, enabled = name.trim().isNotBlank(), modifier = Modifier.width(130.dp))
        }
    )
}

@Composable
private fun SectionTitle(text: String) {
    BasicText(text, color = LocalAppColors.current.text, fontSize = 14.sp)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun TextBlock(text: String) {
    BasicText(text, color = LocalAppColors.current.muted, fontSize = 14.sp, lineHeight = 21.sp)
}

@Composable
private fun ThemePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        Modifier.weight(1f).height(40.dp).background(if (selected) c.accentSoft else c.background, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) c.accent else c.border, RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { BasicText(label, color = if (selected) c.accent else c.muted, fontSize = 12.sp) }
}

private fun normalizeBaseUrl(value: String): String {
    var v = value.trim().removeSuffix("/")
    if (v.endsWith("/chat/completions")) v = v.removeSuffix("/chat/completions")
    return v
}
