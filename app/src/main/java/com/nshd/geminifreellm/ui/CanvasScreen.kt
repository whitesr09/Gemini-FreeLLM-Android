package com.nshd.geminifreellm.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.nshd.geminifreellm.model.CanvasDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun CanvasScreen(initial: CanvasDocument, onSave: (CanvasDocument) -> Unit, onAsk: (CanvasDocument, String) -> Unit, onBack: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var language by rememberSaveable { mutableStateOf(initial.language) }
    var code by rememberSaveable { mutableStateOf(initial.code) }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<List<String>>(emptyList()) }
    var redo by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastUndo by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    fun snapshot() = CanvasDocument(title.ifBlank { "Untitled" }, language.ifBlank { "text" }, code)
    fun leave() { onSave(snapshot()); onBack() }
    LaunchedEffect(title, language, code) { delay(700); onSave(snapshot()) }
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val highlightText = MaterialTheme.colorScheme.onPrimaryContainer
    val transform = remember(query, highlight, highlightText) { VisualTransformation { original ->
        val result = buildAnnotatedString {
            append(original)
            if (query.isNotEmpty()) Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(original.text).forEach {
                addStyle(SpanStyle(background = highlight, color = highlightText), it.range.first, it.range.last + 1)
            }
        }
        TransformedText(result, OffsetMapping.Identity)
    } }
    BackHandler { leave() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val content = code
            scope.launch {
                status = withContext(Dispatchers.IO) { runCatching {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(content.toByteArray()) }
                    "File exported"
                }.getOrDefault("Could not export this file") }
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Canvas") }, navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Save and close canvas") } },
            actions = { TextButton(onClick = { onSave(snapshot()); status = "Saved to this conversation" }) { Text("Save") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                OutlinedTextField(title, { title = it.take(80) }, Modifier.weight(1f), label = { Text("File name") }, singleLine = true)
                OutlinedTextField(language, { language = it.take(30) }, Modifier.width(112.dp), label = { Text("Language") }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = undo.isNotEmpty(), onClick = { redo = (redo + code).takeLast(12); code = undo.last(); undo = undo.dropLast(1) }) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                IconButton(enabled = redo.isNotEmpty(), onClick = { undo = (undo + code).takeLast(12); code = redo.last(); redo = redo.dropLast(1) }) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); status = "Code copied" }) { Icon(Icons.Outlined.ContentCopy, "Copy canvas") }
                IconButton(onClick = { export.launch(title.ifBlank { "code.txt" }) }) { Icon(Icons.Outlined.FileDownload, "Export canvas file") }
                FilterChip(wrap, { wrap = !wrap }, label = { Text("Wrap lines") })
                IconButton(enabled = code.isNotEmpty(), onClick = { clear = true }) { Icon(Icons.Outlined.DeleteOutline, "Clear canvas") }
            }
            OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find in code") },
                supportingText = { if (query.isNotEmpty()) Text("${Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(code).count()} matches") })
            OutlinedTextField(code, { value ->
                if (value.length <= 120_000) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUndo > 700 || kotlin.math.abs(value.length - code.length) > 1) { undo = (undo + code).takeLast(12); lastUndo = now }
                    redo = emptyList(); code = value; status = "Autosave enabled"
                } else status = "Canvas limit is 120,000 characters"
            }, modifier = Modifier.weight(1f).fillMaxWidth().then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                visualTransformation = transform, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                placeholder = { Text("Write code here, or open a code block from a reply.") })
            Text(status ?: "${code.count { it == '\n' } + 1} lines · ${code.length} characters · Code is not executed",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                listOf("Explain", "Review", "Fix", "Optimize").forEach { action ->
                    OutlinedButton(enabled = code.isNotBlank(), onClick = { onAsk(snapshot(), action); onBack() }) { Text(action) }
                }
            }
            Text("AI actions prepare a message for you to review and send.", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear canvas?") }, text = { Text("The current code will be removed. Undo can restore it while this editor stays open.") },
        confirmButton = { TextButton(onClick = { undo = (undo + code).takeLast(12); code = ""; redo = emptyList(); clear = false; status = "Autosave enabled" }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
}
