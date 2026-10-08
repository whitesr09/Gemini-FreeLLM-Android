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
import com.nshd.geminifreellm.data.readPortableText
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    var replacement by rememberSaveable { mutableStateOf("") }
    var replaceOpen by rememberSaveable { mutableStateOf(false) }
    var replaceConfirm by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    var focus by rememberSaveable { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<List<String>>(emptyList()) }
    var redo by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastUndo by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    fun snapshot() = CanvasDocument(title.ifBlank { "Untitled" }, language.ifBlank { "text" }, code)
    fun changeCode(value: String) {
        if (value.length > 120_000) { status = "Canvas limit is 120,000 characters"; return }
        undo = (undo + code).takeLast(12); redo = emptyList(); code = value; status = "Autosave enabled"
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { readPortableText(it, 120_000) } ?: error("Unreadable file") }
                if (code.isEmpty()) changeCode(text) else pendingImport = text
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Choose a UTF-8 code file up to 120,000 characters" }
        }
    }
    fun leave() { onSave(snapshot()); onBack() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestDocument by rememberUpdatedState(snapshot())
    val latestSave by rememberUpdatedState(onSave)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestSave(latestDocument)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
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
            actions = { IconButton(onClick = { focus = !focus }) { Icon(if (focus) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, "Toggle focus mode") }; TextButton(onClick = { onSave(snapshot()); status = "Save queued for this conversation" }) { Text("Save") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
            if (!focus) Row(horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                OutlinedTextField(title, { title = it.take(80) }, Modifier.weight(1f), label = { Text("File name") }, singleLine = true)
                OutlinedTextField(language, { language = it.take(30) }, Modifier.width(112.dp), label = { Text("Language") }, singleLine = true)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = undo.isNotEmpty(), onClick = { redo = (redo + code).takeLast(12); code = undo.last(); undo = undo.dropLast(1) }) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                IconButton(enabled = redo.isNotEmpty(), onClick = { undo = (undo + code).takeLast(12); code = redo.last(); redo = redo.dropLast(1) }) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); status = "Code copied" }) { Icon(Icons.Outlined.ContentCopy, "Copy canvas") }
                IconButton(onClick = { export.launch(title.ifBlank { "code.txt" }) }) { Icon(Icons.Outlined.FileDownload, "Export canvas file") }
                IconButton(onClick = { importFile.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.FileUpload, "Import canvas file") }
                FilterChip(replaceOpen, { replaceOpen = !replaceOpen }, label = { Text("Find / replace") })
                FilterChip(wrap, { wrap = !wrap }, label = { Text("Wrap lines") })
                IconButton(enabled = code.isNotEmpty(), onClick = { clear = true }) { Icon(Icons.Outlined.DeleteOutline, "Clear canvas") }
            }
            if (replaceOpen) {
                val matches = remember(code, query) { if (query.isBlank()) 0 else Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(code).count() }
                OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find in code") },
                    supportingText = { Text("$matches matches · case insensitive") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(replacement, { replacement = it.take(2000) }, Modifier.weight(1f), singleLine = true, label = { Text("Replace with") })
                    TextButton(enabled = matches > 0, onClick = { replaceConfirm = true }) { Text("Replace all") }
                }
            }
            OutlinedTextField(code, { value ->
                if (value.length <= 120_000) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (undo.isEmpty() || now - lastUndo > 700 || kotlin.math.abs(value.length - code.length) > 1) { undo = (undo + code).takeLast(12); lastUndo = now }
                    redo = emptyList(); code = value; status = "Autosave enabled"
                } else status = "Canvas limit is 120,000 characters"
            }, modifier = Modifier.weight(1f).fillMaxWidth().then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                visualTransformation = transform, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                placeholder = { Text("Write code here, or open a code block from a reply.") })
            Text(status ?: "${code.count { it == '\n' } + 1} lines · ${code.length} characters · Code is not executed",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                listOf("Explain", "Review", "Fix", "Optimize", "Add tests", "Document").forEach { action ->
                    OutlinedButton(enabled = code.isNotBlank(), onClick = { onAsk(snapshot(), action); onBack() }) { Text(action) }
                }
            }
            Text("AI actions prepare a message for you to review and send.", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (replaceConfirm) AlertDialog(onDismissRequest = { replaceConfirm = false }, title = { Text("Replace all matches?") },
        text = { Text("All case-insensitive matches will change. Undo can restore the previous code.") },
        confirmButton = { TextButton(onClick = {
            val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
            val count = regex.findAll(code).count()
            if (code.length.toLong() + count.toLong() * (replacement.length - query.length) > 120_000) status = "Replacement exceeds canvas limit"
            else changeCode(regex.replace(code) { replacement })
            replaceConfirm = false
        }) { Text("Replace") } }, dismissButton = { TextButton(onClick = { replaceConfirm = false }) { Text("Cancel") } })
    pendingImport?.let { imported -> AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("Replace canvas with imported file?") },
        text = { Text("Undo can restore your current code while the editor stays open.") },
        confirmButton = { TextButton(onClick = { changeCode(imported); pendingImport = null }) { Text("Import") } },
        dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } }) }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear canvas?") }, text = { Text("The current code will be removed. Undo can restore it while this editor stays open.") },
        confirmButton = { TextButton(onClick = { undo = (undo + code).takeLast(12); code = ""; redo = emptyList(); clear = false; status = "Autosave enabled" }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
}
