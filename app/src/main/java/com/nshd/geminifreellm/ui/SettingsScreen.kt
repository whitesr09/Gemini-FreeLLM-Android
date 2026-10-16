package com.nshd.geminifreellm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nshd.geminifreellm.data.DEFAULT_BASE_URL

private val UrlRegex = Regex("^https?://\\S+")

@Composable
fun SettingsDialog(
    baseUrl: String,
    apiKey: String,
    themeMode: ThemeMode,
    onSave: (String, String, ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var key by remember(apiKey) { mutableStateOf(apiKey) }
    var theme by remember(themeMode) { mutableStateOf(themeMode) }

    val trimmedUrl = url.trim()
    val urlValid = trimmedUrl.matches(UrlRegex)
    val keyValid = key.trim().isNotBlank()
    val showUrlError = trimmedUrl.isNotEmpty() && !urlValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection & appearance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Your Unified API key stays on this device. Never commit it to GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Base URL") },
                    placeholder = { Text(DEFAULT_BASE_URL) },
                    singleLine = true,
                    isError = showUrlError,
                    supportingText = {
                        if (showUrlError) {
                            Text("Use http:// or https:// followed by the host, e.g. https://my-server.com/v1")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = { url = DEFAULT_BASE_URL },
                    modifier = Modifier.align(androidx.compose.ui.Alignment.End)
                ) {
                    Text("Use default server")
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Unified API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = theme == mode,
                            onClick = { theme = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                        ) {
                            Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = urlValid && keyValid,
                onClick = { onSave(trimmedUrl.removeSuffix("/"), key.trim(), theme) }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
