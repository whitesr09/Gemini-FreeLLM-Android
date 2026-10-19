package com.nshd.geminifreellm.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.style.TextOverflow
import com.nshd.geminifreellm.model.CanvasDocument
import com.nshd.geminifreellm.data.CodeRunner
import com.nshd.geminifreellm.data.JsRunResult
import com.nshd.geminifreellm.data.readPortableText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun canvasCodeLines(text: String): List<String> {
    val lines = ArrayList<String>(text.count { it == '\n' } + 1)
    var start = 0
    text.forEachIndexed { index, char ->
        if (char == '\n') {
            lines += text.substring(start, index)
            start = index + 1
        }
    }
    // Keep the final empty line after a trailing newline. The gutter and editor
    // must agree on the number of logical lines, even for an unfinished file.
    lines += text.substring(start)
    return lines
}

internal class CanvasOffsetMapping(private val text: String, private val prefixLen: Int) : OffsetMapping {
    private val lineStarts = ArrayList<Int>(text.length / 40 + 2).apply {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }

    fun lineOf(offset: Int): Int {
        val bounded = offset.coerceIn(0, text.length)
        val index = lineStarts.binarySearch(bounded)
        return (if (index >= 0) index else -index - 2).coerceAtLeast(0)
    }

    override fun originalToTransformed(offset: Int): Int =
        offset.coerceIn(0, text.length) + (lineOf(offset) + 1) * prefixLen

    override fun transformedToOriginal(offset: Int): Int {
        val transformedLength = text.length + lineStarts.size * prefixLen
        val bounded = offset.coerceIn(0, transformedLength)
        var low = 0
        var high = lineStarts.size - 1
        var line = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lineStarts[mid] + mid * prefixLen <= bounded) {
                line = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val linePrefixStart = lineStarts[line] + line * prefixLen
        val lineContentStart = linePrefixStart + prefixLen
        return if (bounded < lineContentStart) lineStarts[line]
        else (bounded - (line + 1) * prefixLen).coerceIn(lineStarts[line], text.length)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun CanvasScreen(initial: CanvasDocument, onSave: (CanvasDocument) -> Unit, onAsk: (CanvasDocument, String) -> Unit, onBack: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var language by rememberSaveable { mutableStateOf(initial.language) }
    var code by rememberSaveable { mutableStateOf(initial.code) }
    var editorExpanded by rememberSaveable(initial.title, initial.language, initial.code) { mutableStateOf(initial.code.isBlank()) }
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
    var running by remember { mutableStateOf(false) }
    var runResult by remember { mutableStateOf<JsRunResult?>(null) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var editorMenu by remember { mutableStateOf(false) }
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
    fun runCode() {
        running = true; runResult = null
        val snapshotCode = code
        scope.launch(Dispatchers.Default) {
            val result = CodeRunner.runJs(snapshotCode)
            withContext(Dispatchers.Main) {
                running = false; runResult = result
                status = when (result) {
                    is JsRunResult.Success -> "Run finished"
                    is JsRunResult.Failure -> "Run failed" + if (result.line > 0) " at line ${result.line}" else ""
                }
            }
        }
    }
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
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    // Line numbers are baked into the visual transformation (fixed-width prefix per
    // line, so cursor mapping stays exact); search highlighting shifts with them.
    val transform = remember(query, highlight, highlightText, gutterColor) { VisualTransformation { original ->
        val text = original.text
        val logicalLines = canvasCodeLines(text)
        val numberWidth = maxOf(4, logicalLines.size.toString().length)
        val prefixLen = numberWidth + 3 // number + space + gutter bar + space
        fun prefix(n: Int) = "%${numberWidth}d │ ".format(Locale.US, n)
        val mapping = CanvasOffsetMapping(text, prefixLen)
        val result = buildAnnotatedString {
            val lines = logicalLines
            lines.forEachIndexed { index, line ->
                val p = prefix(index + 1)
                val start = length
                append(p); addStyle(SpanStyle(color = gutterColor), start, start + p.length)
                append(line)
                if (index != lines.lastIndex) append('\n')
            }
            if (query.isNotEmpty()) Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
                val shift = (mapping.lineOf(match.range.first) + 1) * prefixLen
                addStyle(SpanStyle(background = highlight, color = highlightText), match.range.first + shift, match.range.last + 1 + shift)
            }
        }
        TransformedText(result, mapping)
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
            actions = {
                IconButton(onClick = { focus = !focus }) {
                    Icon(if (focus) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen, "Toggle focus mode")
                }
                IconButton(onClick = { editorExpanded = !editorExpanded }) {
                    Icon(if (editorExpanded) Icons.Outlined.FullscreenExit else Icons.Outlined.Code,
                        if (editorExpanded) "Collapse code editor" else "Expand code editor")
                }
                TextButton(onClick = { onSave(snapshot()); status = "Save queued for this conversation" }) { Text("Save") }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = Design.medium), verticalArrangement = Arrangement.spacedBy(Design.small)) {
            if (!focus) Row(horizontalArrangement = Arrangement.spacedBy(Design.small)) {
                OutlinedTextField(title, { title = it.take(80) }, Modifier.weight(1f), label = { Text("File name") }, singleLine = true)
                OutlinedTextField(language, { language = it.take(30) }, Modifier.width(112.dp), label = { Text("Language") }, singleLine = true)
            }
            if (editorExpanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    enabled = undo.isNotEmpty(),
                    onClick = { redo = (redo + code).takeLast(12); code = undo.last(); undo = undo.dropLast(1) }
                ) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo") }
                IconButton(
                    enabled = redo.isNotEmpty(),
                    onClick = { undo = (undo + code).takeLast(12); code = redo.last(); redo = redo.dropLast(1) }
                ) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo") }
                if (CodeRunner.isRunnable(language)) IconButton(onClick = ::runCode, enabled = code.isNotBlank() && !running) {
                    if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PlayArrow, "Run JavaScript on this device")
                }
                if (CodeRunner.isPreviewable(language)) IconButton(onClick = { preview = true }, enabled = code.isNotBlank()) {
                    Icon(Icons.Outlined.Preview, "Preview HTML on this device")
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)); status = "Code copied" }) {
                    Icon(Icons.Outlined.ContentCopy, "Copy canvas")
                }
                Box {
                    IconButton(onClick = { editorMenu = true }) { Icon(Icons.Outlined.MoreHoriz, "More editor actions") }
                    DropdownMenu(expanded = editorMenu, onDismissRequest = { editorMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (replaceOpen) "Hide find / replace" else "Find / replace") },
                            leadingIcon = { Icon(Icons.Outlined.Search, null) },
                            onClick = { editorMenu = false; replaceOpen = !replaceOpen }
                        )
                        DropdownMenuItem(
                            text = { Text("Export file") },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                            onClick = { editorMenu = false; export.launch(title.ifBlank { "code.txt" }) }
                        )
                        DropdownMenuItem(
                            text = { Text("Import file") },
                            leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                            onClick = { editorMenu = false; importFile.launch(arrayOf("*/*")) }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear canvas") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            enabled = code.isNotEmpty(),
                            onClick = { editorMenu = false; clear = true }
                        )
                    }
                }
            }
            }
            if (editorExpanded) {
            if (replaceOpen) {
                val matches = remember(code, query) { if (query.isBlank()) 0 else Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(code).count() }
                OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find in code") },
                    supportingText = { Text("$matches matches · case insensitive") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(replacement, { replacement = it.take(2000) }, Modifier.weight(1f), singleLine = true, label = { Text("Replace with") })
                    TextButton(enabled = matches > 0, onClick = { replaceConfirm = true }) { Text("Replace all") }
                }
            }
            OutlinedTextField(
                value = code,
                onValueChange = { value ->
                    if (value.length <= 120_000) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (undo.isEmpty() || now - lastUndo > 700 || kotlin.math.abs(value.length - code.length) > 1) {
                            undo = (undo + code).takeLast(12)
                            lastUndo = now
                        }
                        redo = emptyList()
                        code = value
                        status = "Autosave enabled"
                    } else status = "Canvas limit is 120,000 characters"
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                visualTransformation = transform,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                shape = Design.card,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                placeholder = { Text("Write code here, or open a code block from a reply.") }
            )
            Text(status ?: "${code.count { it == '\n' } + 1} lines · ${code.length} characters · JavaScript runs on-device · HTML previews in-app",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                CanvasCodePreview(
                    codeLines = canvasCodeLines(code),
                    onExpand = { editorExpanded = true }
                )
            }
            AnimatedVisibility(visible = running || runResult != null) {
                RunConsole(running, runResult, Modifier.fillMaxWidth().heightIn(max = 200.dp)) { runResult = null }
            }
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
    if (preview) HtmlPreviewDialog(code) { preview = false }
}

@Composable
private fun CanvasCodePreview(codeLines: List<String>, onExpand: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Design.medium),
            verticalArrangement = Arrangement.spacedBy(Design.small)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Design.small))
                Text("Code preview", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${codeLines.size} lines", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer {
                Text(
                    codeLines.take(8).joinToString("\n").ifBlank { "Empty canvas" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (codeLines.size > 8) "Showing 8 of ${codeLines.size} lines" else "Preview",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onExpand) {
                    Icon(Icons.Outlined.Fullscreen, contentDescription = null)
                    Spacer(Modifier.width(Design.tiny))
                    Text("Expand editor")
                }
            }
        }
    }
}

