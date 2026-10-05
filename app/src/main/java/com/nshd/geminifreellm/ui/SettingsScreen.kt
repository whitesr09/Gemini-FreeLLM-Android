package com.nshd.geminifreellm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable fun SettingsDialog(baseUrl:String,apiKey:String,themeMode:ThemeMode,onSave:(String,String,ThemeMode)->Unit,onDismiss:()->Unit){var url by remember(baseUrl){mutableStateOf(baseUrl)};var key by remember(apiKey){mutableStateOf(apiKey)};var theme by remember(themeMode){mutableStateOf(themeMode)};AlertDialog(onDismissRequest=onDismiss,title={Text("Connection & appearance")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Text("Your Unified API key stays on this device. Never commit it to GitHub.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);OutlinedTextField(url,{url=it},label={Text("Base URL")},placeholder={Text("http://127.0.0.1:3001/v1")},singleLine=true,modifier=Modifier.fillMaxWidth());OutlinedTextField(key,{key=it},label={Text("Unified API key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth());Text("Theme",style=MaterialTheme.typography.titleSmall);SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){ThemeMode.entries.forEachIndexed{index,mode->SegmentedButton(selected=theme==mode,onClick={theme=mode},shape=SegmentedButtonDefaults.itemShape(index,ThemeMode.entries.size)){Text(mode.name.lowercase().replaceFirstChar{it.uppercase()})}}}}},confirmButton={Button(enabled=url.isNotBlank()&&key.isNotBlank(),onClick={onSave(url,key,theme)}){Text("Save")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})}
