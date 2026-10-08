package com.nshd.geminifreellm.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nshd.geminifreellm.data.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun DiagnosticsScreen(log: DiagnosticLog, onAsk: (String) -> Unit, onBack: () -> Unit) {
    val events by log.events.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("All") }
    var clear by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("Error log & diagnostics") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to settings") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(Design.page), verticalArrangement = Arrangement.spacedBy(Design.medium)) {
            item {
                Text("Understand what happened", style = MaterialTheme.typography.titleLarge)
                Text("Stored on this device for up to 7 days, limited to 100 events. Logs exclude API keys, messages, attachment contents and raw server responses.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                    listOf("All", "Crashes", "Other errors").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }) }
                }
                FilledTonalButton(enabled = events.isNotEmpty(), onClick = { report = log.report() }) { Text("Ask AI to explain") }
                Row {
                    TextButton(enabled = events.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(log.report())); status = "Sanitized report copied" }) { Text("Copy") }
                    TextButton(enabled = events.isNotEmpty(), onClick = {
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, log.report()), "Share diagnostic report")) }
                        catch (_: android.content.ActivityNotFoundException) { status = "No sharing app is available" }
                    }) { Text("Share") }
                    TextButton(enabled = events.isNotEmpty(), onClick = { clear = true }) { Text("Clear log") }
                }
                status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            val filtered = events.filter { filter == "All" || (it.category == "Crash") == (filter == "Crashes") }
            if (filtered.isEmpty()) item { Text("No recorded errors in this category.", style = MaterialTheme.typography.bodyLarge) }
            items(filtered) { event ->
                var expanded by remember(event) { mutableStateOf(false) }
                Surface(shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
                        Text("${event.category} · ${event.provider}", style = MaterialTheme.typography.titleMedium)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.time)), style = MaterialTheme.typography.labelSmall)
                        Text(event.summary, style = MaterialTheme.typography.bodyMedium)
                        Text(if (expanded) "Hide technical details" else "Show technical details", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        if (expanded) SelectionContainer { Text(event.details, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item { Text("AI can suggest steps and explain logs. Fixing the installed app's code requires an app update. System kills and native crashes may not leave a Java crash record.", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Delete local error log?") }, text = { Text("Recorded diagnostics will be removed from this device.") },
        confirmButton = { TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { log.clear() }; clear = false } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
    report?.let { text -> AlertDialog(onDismissRequest = { report = null }, title = { Text("Prepare AI diagnosis?") },
        text = { Text("A new chat will contain the sanitized report (${text.length} characters, newest 20 events). Review it before sending to your selected provider.") },
        confirmButton = { TextButton(onClick = { report = null; onAsk(text) }) { Text("Review in chat") } },
        dismissButton = { TextButton(onClick = { report = null }) { Text("Cancel") } }) }
}
