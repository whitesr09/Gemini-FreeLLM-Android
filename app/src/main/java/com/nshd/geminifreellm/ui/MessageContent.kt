package com.nshd.geminifreellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nshd.geminifreellm.data.CodeRunner
import com.nshd.geminifreellm.data.JsRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class MarkdownBlock(val text: String, val language: String? = null)

/** Formats every logical source line, including the empty line after a trailing newline. */
internal fun numberedCodeLines(lines: List<String>): List<String> {
    val width = maxOf(3, lines.size.toString().length)
    return lines.mapIndexed { index, line ->
        (index + 1).toString().padStart(width, ' ') + " │ " + line
    }
}

private val MARKDOWN_FENCE_OPEN = Regex("^(`{3,}|~{3,})(.*)$")
private val MARKDOWN_HEADING = Regex("^(#{1,6})\\s+.*$")
private val MARKDOWN_HEADING_CONTENT = Regex("^(#{1,6})\\s+(.*)")
private val MARKDOWN_TABLE_SEPARATOR = Regex(":?-{3,}:?")
private val MARKDOWN_LIST_MARKER = Regex("(?m)^(\\s*)[-*] ")
private val MARKDOWN_PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
private val MARKDOWN_INLINE_PATTERN = Regex("\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)|`([^`\\n]+)`|\\*\\*([^*]+)\\*\\*|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")
private val MARKDOWN_TABLE_CELLS = Regex("(?<!\\\\)\\|")

/** Unclosed fences are deliberately rendered as code while the response streams. */
internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    var code = false
    var language = ""
    var fenceChar: Char? = null
    var fenceLength = 0
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotEmpty() || code) blocks += MarkdownBlock(buffer.toString().trimEnd('\n'), if (code) language else null)
        buffer.setLength(0)
    }
    text.lineSequence().forEach { line ->
        val trimmed = line.trimStart()
        if (!code) {
            val opening = MARKDOWN_FENCE_OPEN.matchEntire(trimmed)
            if (opening != null) {
                flush()
                code = true
                fenceChar = opening.groupValues[1].first()
                fenceLength = opening.groupValues[1].length
                language = opening.groupValues[2].trim().take(30)
            } else {
                buffer.append(line).append('\n')
            }
        } else {
            val marker = fenceChar
            val markerCount = if (marker == null) 0 else trimmed.takeWhile { it == marker }.length
            val isClosing = marker != null && markerCount >= fenceLength &&
                trimmed.drop(markerCount).isBlank()
            if (isClosing) {
                flush()
                code = false
                language = ""
                fenceChar = null
                fenceLength = 0
            } else {
                buffer.append(line).append('\n')
            }
        }
    }
    flush()
    return blocks
}

internal fun splitHeadingAndBody(paragraph: String): List<String> {
    val firstLine = paragraph.substringBefore('\n')
    return if ('\n' in paragraph && MARKDOWN_HEADING.matches(firstLine))
        listOf(firstLine, paragraph.substringAfter('\n'))
    else listOf(paragraph)
}

@Composable
fun MessageContent(text: String, onCanvas: (String, String) -> Unit = { _, _ -> }) {
    val blocks = remember(text) { markdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
        blocks.forEachIndexed { index, block ->
            key(index) {
                if (block.language != null) {
                    CodeBlock(block, onCanvas)
                } else {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(Design.small)) {
                            // Paragraph blocks keep long responses inexpensive; no composable per token.
                            block.text.split(MARKDOWN_PARAGRAPH_BREAK).flatMap(::splitHeadingAndBody).forEach { paragraph ->
                                val heading = MARKDOWN_HEADING_CONTENT.matchEntire(paragraph)
                                val table = markdownTable(paragraph)
                                val quote = paragraph.startsWith("> ")
                                val displayed = when {
                                    heading != null -> heading.groupValues[2]
                                    quote -> paragraph.lineSequence().joinToString("\n") { it.removePrefix("> ") }
                                    else -> paragraph.replace(MARKDOWN_LIST_MARKER, "$1• ")
                                }
                                if (paragraph.trim() in listOf("---", "***", "___")) {
                                    HorizontalDivider(Modifier.padding(vertical = Design.small))
                                } else if (table != null) {
                                    MarkdownTable(table)
                                } else {
                                    Text(
                                        inlineMarkdown(displayed),
                                        modifier = if (quote) Modifier
                                            .background(MaterialTheme.colorScheme.surfaceContainer, Design.card)
                                            .padding(Design.medium) else Modifier,
                                        style = when {
                                            heading != null && heading.groupValues[1].length <= 2 -> MaterialTheme.typography.titleLarge
                                            heading != null -> MaterialTheme.typography.titleMedium
                                            else -> MaterialTheme.typography.bodyLarge
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun inlineMarkdown(value: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(value, colors) {
        buildAnnotatedString {
            val pattern = MARKDOWN_INLINE_PATTERN
            var cursor = 0
            pattern.findAll(value).forEach { match ->
                append(value.substring(cursor, match.range.first))
                when {
                    match.groupValues[1].isNotEmpty() -> withLink(LinkAnnotation.Url(match.groupValues[2],
                        TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)))) { append(match.groupValues[1]) }
                    match.groupValues[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceContainerHigh)) { append(match.groupValues[3]) }
                    match.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[4]) }
                    else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(match.groupValues[5]) }
                }
                cursor = match.range.last + 1
            }
            append(value.substring(cursor))
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun CodeBlock(block: MarkdownBlock, onCanvas: (String, String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val allLines = remember(block.text) { canvasCodeLines(block.text) }
    var expanded by remember { mutableStateOf(allLines.size <= 8) }
    var userChangedExpanded by remember { mutableStateOf(false) }
    var autoCollapsed by remember { mutableStateOf(allLines.size > 8) }
    LaunchedEffect(allLines.size) {
        // During streaming, collapse once when a short snippet grows into a long one.
        // Do not fight the user's expand/collapse choice after their first tap.
        if (!userChangedExpanded && !autoCollapsed && allLines.size > 8) {
            expanded = false
            autoCollapsed = true
        }
    }
    val visibleText = remember(block.text, expanded) { if (expanded) block.text else allLines.take(8).joinToString("\n") }
    val visibleLines = remember(visibleText) { canvasCodeLines(visibleText) }
    var copied by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var runResult by remember { mutableStateOf<JsRunResult?>(null) }
    var preview by remember { mutableStateOf(false) }
    var moreActions by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    val language = block.language.orEmpty()
    fun run() {
        running = true; runResult = null
        val snapshot = block.text
        scope.launch(Dispatchers.Default) {
            val result = CodeRunner.runJs(snapshot)
            withContext(Dispatchers.Main) { running = false; runResult = result }
        }
    }
    Surface(shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Design.tiny),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    language.ifBlank { "Code" },
                    Modifier.weight(1f).padding(start = Design.small),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (allLines.size > 8) TextButton(
                    onClick = { userChangedExpanded = true; expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = Design.tiny)
                ) {
                    Text(if (expanded) "Collapse" else "Expand · ${allLines.size}")
                }
                if (CodeRunner.isRunnable(language)) IconButton(onClick = ::run, enabled = !running) {
                    if (running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.PlayArrow, "Run JavaScript on this device")
                }
                if (CodeRunner.isPreviewable(language)) IconButton(onClick = { preview = true }) {
                    Icon(Icons.Outlined.Preview, "Preview HTML on this device")
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }) {
                    Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        if (copied) "Code copied" else "Copy full code", Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = { moreActions = true }) {
                        Icon(Icons.Outlined.MoreHoriz, "More code actions")
                    }
                    DropdownMenu(expanded = moreActions, onDismissRequest = { moreActions = false }) {
                        DropdownMenuItem(
                            text = { Text("Open in code canvas") },
                            leadingIcon = { Icon(Icons.Outlined.Code, null) },
                            enabled = block.text.length <= 120_000,
                            onClick = { moreActions = false; onCanvas(block.text, language) }
                        )
                    }
                }
            }
            if (running || runResult != null) RunConsole(running, runResult, Modifier.fillMaxWidth().padding(horizontal = Design.medium)) { runResult = null }
            val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            val gutterStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            // Render gutter and source in one text layout. Separate columns drift when a
            // final newline or font scaling changes the visual line count.
            val numberedLines = remember(visibleLines) { numberedCodeLines(visibleLines) }
            val numberWidth = maxOf(3, visibleLines.size.toString().length)
            val numberedCode = remember(numberedLines, gutterColor, numberWidth) {
                buildAnnotatedString {
                    numberedLines.forEachIndexed { index, line ->
                        val start = length
                        append(line)
                        addStyle(SpanStyle(color = gutterColor), start, start + numberWidth + 3)
                        if (index != numberedLines.lastIndex) append('\n')
                    }
                }
            }
            SelectionContainer(Modifier.fillMaxWidth()) {
                Text(
                    numberedCode,
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Design.medium, vertical = Design.small),
                    style = gutterStyle,
                    softWrap = false
                )
            }
            if (allLines.size > 8) Text(
                if (expanded) "${allLines.size} lines · full code" else "Showing first 8 of ${allLines.size} lines",
                Modifier.padding(start = Design.medium, end = Design.medium, bottom = Design.small),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (preview) HtmlPreviewDialog(block.text) { preview = false }
}


internal data class MarkdownTableData(val header: List<String>, val rows: List<List<String>>)

internal fun markdownTable(text: String): MarkdownTableData? {
    val lines = text.trim().lines()
    if (lines.size < 2) return null
    fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|")
        .split(MARKDOWN_TABLE_CELLS).map { it.trim().replace("\\|", "|") }
    val header = cells(lines.first())
    val separator = cells(lines[1])
    if (header.size !in 2..12 || separator.size != header.size || separator.any { !MARKDOWN_TABLE_SEPARATOR.matches(it) }) return null
    if (lines.size > 202) return null
    val rows = lines.drop(2).map(::cells)
    if (rows.any { it.size != header.size }) return null
    return MarkdownTableData(header, rows)
}

@Composable
private fun MarkdownTable(table: MarkdownTableData) {
    Surface(shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                table.header.forEach { Text(inlineMarkdown(it), Modifier.width(160.dp).padding(Design.medium), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) }
            }
            table.rows.forEachIndexed { index, row ->
                Row(Modifier.background(if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer)) {
                    row.forEach { Text(inlineMarkdown(it), Modifier.width(160.dp).padding(Design.medium), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}
