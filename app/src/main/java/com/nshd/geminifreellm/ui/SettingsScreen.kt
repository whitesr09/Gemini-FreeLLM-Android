package com.nshd.geminifreellm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
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
    var url by remember { mutableStateOf(baseUrl) }
    var key by remember { mutableStateOf(apiKey) }
    var name by remember { mutableStateOf(aiName) }
    var theme by remember { mutableStateOf(themeMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SmartToy, contentDescription = null) },
        title = { Text("Settings") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("AI name") },
                        supportingText = { Text("This is the name shown throughout the app.") }
                    )
                }
                item {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("FreeLLMAPI Base URL") }
                    )
                }
                item {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Unified API key") },
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                item {
                    Text("Appearance")
                    ThemeChoice("System", theme == ThemeMode.SYSTEM) { theme = ThemeMode.SYSTEM }
                    ThemeChoice("Light", theme == ThemeMode.LIGHT) { theme = ThemeMode.LIGHT }
                    ThemeChoice("Dark", theme == ThemeMode.DARK) { theme = ThemeMode.DARK }
                    ThemeChoice("AMOLED", theme == ThemeMode.AMOLED) { theme = ThemeMode.AMOLED }
                }
                item {
                    Text("Data & privacy")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onExportChats, modifier = Modifier.weight(1f)) { Text("Export chats") }
                        Button(onClick = onImportChats, modifier = Modifier.weight(1f)) { Text("Import chats") }
                    }
                    Spacer(Modifier.padding(2.dp))
                    TextButton(onClick = onClearChats) { Text("Clear local chat history") }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        url.trim().removeSuffix("/"),
                        key.trim(),
                        name.trim().ifBlank { "Assistant" },
                        theme
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ThemeChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
fun AiNameOnboarding(
    currentName: String,
    onContinue: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = {},
        // Keep the animated orb inside the dialog's icon slot at a fixed size.
        // Passing an empty Modifier here lets the orb's fillMaxSize child expand
        // to the dialog's available constraints and push the input field off-screen.
        icon = { AiOrb(Modifier.size(64.dp), active = true) },
        title = { Text("Name your AI") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose the name you want to see in the chat interface.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("For example: Nova") },
                    label = { Text("AI name") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.trim().isNotBlank()) onContinue(name.trim()) }
            ) {
                Text("Continue")
            }
        }
    )
}
