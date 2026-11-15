package com.nshd.geminifreellm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
private val MARKDOWN_PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
private val MARKDOWN_INLINE_PATTERN = Regex("\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)|`([^`\\n]+)`|\\*\\*([^*]+)\\*\\*|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|~~([^~\\n]+)~~|<u>([^<\\n]+)</u>")
private val MARKDOWN_TABLE_CELLS = Regex("(?<!\\\\)\\|")
private val MARKDOWN_LIST_ITEM = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
private val MARKDOWN_CHECKLIST_ITEM = Regex("^\\[([ xX])\\]\\s+(.*)$")
private val MARKDOWN_HORIZONTAL_RULES = setOf("---", "***", "___")

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

/** One list entry with its nesting [level] (0 = outermost) already resolved. */
internal data class MarkdownListEntry(
    val level: Int,
    val ordered: Boolean,
    val marker: String,
    val checked: Boolean?,
    val text: String
)

/** A renderable unit of prose: headings, quotes, rules, tables, lists and plain paragraphs. */
internal sealed interface ProseUnit {
    data class Paragraph(val text: String) : ProseUnit
    data class Heading(val level: Int, val text: String) : ProseUnit
    data class Quote(val text: String) : ProseUnit
    data object Rule : ProseUnit
    data class Table(val data: MarkdownTableData) : ProseUnit
    data class ListBlock(val items: List<MarkdownListEntry>) : ProseUnit
}

private data class RawListEntry(
    val indent: Int,
    val ordered: Boolean,
    val marker: String,
    val checked: Boolean?,
    var text: String
)

private fun matchListItem(line: String): RawListEntry? {
    val match = MARKDOWN_LIST_ITEM.matchEntire(line) ?: return null
    val markerToken = match.groupValues[2]
    val ordered = markerToken.first().isDigit()
    var content = match.groupValues[3]
    var checked: Boolean? = null
    if (!ordered) {
        val checklist = MARKDOWN_CHECKLIST_ITEM.matchEntire(content)
        if (checklist != null) {
            checked = checklist.groupValues[1] != " "
            content = checklist.groupValues[2]
        }
    }
    return RawListEntry(
        indent = match.groupValues[1].length,
        ordered = ordered,
        marker = markerToken,
        checked = checked,
        text = content
    )
}

/**
 * Splits one prose block into typed units. Line-oriented and total: any input —
 * including half-streamed Markdown (a header row without its separator, a checklist
 * item whose bracket has not arrived yet) — degrades to plain text instead of
 * failing, mirroring the unclosed-fence contract of [markdownBlocks].
 */
internal fun proseUnits(text: String): List<ProseUnit> {
    val units = mutableListOf<ProseUnit>()
    text.split(MARKDOWN_PARAGRAPH_BREAK).forEach { chunk ->
        if (chunk.isBlank()) return@forEach
        if (chunk.trim() in MARKDOWN_HORIZONTAL_RULES) {
            units += ProseUnit.Rule
            return@forEach
        }
        markdownTable(chunk)?.let {
            units += ProseUnit.Table(it)
            return@forEach
        }
        val lines = chunk.lines()
        var index = 0
        val textRun = StringBuilder()
        fun flushText() {
            val content = textRun.toString().trim('\n')
            if (content.isNotBlank()) units += ProseUnit.Paragraph(content)
            textRun.setLength(0)
        }
        while (index < lines.size) {
            val line = lines[index]
            val heading = MARKDOWN_HEADING_CONTENT.matchEntire(line)
            when {
                heading != null -> {
                    flushText()
                    units += ProseUnit.Heading(heading.groupValues[1].length, heading.groupValues[2])
                    index++
                }
                line.trimStart().startsWith(">") -> {
                    flushText()
                    val quoteLines = mutableListOf<String>()
                    while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                        quoteLines += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                        index++
                    }
                    units += ProseUnit.Quote(quoteLines.joinToString("\n"))
                }
                matchListItem(line) != null -> {
                    flushText()
                    val raw = mutableListOf<RawListEntry>()
                    while (index < lines.size) {
                        val current = lines[index]
                        val item = matchListItem(current)
                        if (item != null) {
                            raw += item
                            index++
                        } else {
                            val indent = current.takeWhile { it == ' ' || it == '\t' }.length
                            val last = raw.lastOrNull()
                            if (last != null && current.isNotBlank() && indent > last.indent) {
                                last.text += "\n" + current.trim()
                                index++
                            } else break
                        }
                    }
                    val indents = raw.map { it.indent }.distinct().sorted()
                    units += ProseUnit.ListBlock(raw.map { entry ->
                        MarkdownListEntry(
                            level = minOf(indents.indexOf(entry.indent), 4),
                            ordered = entry.ordered,
                            marker = entry.marker,
                            checked = entry.checked,
                            text = entry.text
                        )
                    })
                }
                textRun.isEmpty() && line.contains('|') -> {
                    val table = markdownTable(lines.drop(index).joinToString("\n"))
                    if (table != null) {
                        units += ProseUnit.Table(table)
                        index = lines.size
                    } else {
                        textRun.append(line).append('\n')
                        index++
                    }
                }
                else -> {
                    textRun.append(line).append('\n')
                    index++
                }
            }
        }
        flushText()
    }
    return units
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
                    val units = remember(block.text) { proseUnits(block.text) }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
                            // Prose units keep long responses inexpensive; no composable per token.
                            units.forEachIndexed { unitIndex, unit ->
                                ProseUnitContent(unit, first = unitIndex == 0)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 28.sp)
    3 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

@Composable
private fun ProseUnitContent(unit: ProseUnit, first: Boolean) {
    when (unit) {
        ProseUnit.Rule -> HorizontalDivider(Modifier.padding(vertical = Design.small))
        is ProseUnit.Table -> MarkdownTable(unit.data)
        is ProseUnit.Heading -> Text(
            inlineMarkdown(unit.text),
            style = headingStyle(unit.level),
            modifier = if (first) Modifier else Modifier.padding(top = Design.small)
        )
        is ProseUnit.Quote -> Text(
            inlineMarkdown(unit.text),
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, Design.card)
                .padding(Design.medium),
            style = MaterialTheme.typography.bodyLarge
        )
        is ProseUnit.ListBlock -> MarkdownList(unit.items)
        is ProseUnit.Paragraph -> Text(
            inlineMarkdown(unit.text),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun MarkdownList(items: List<MarkdownListEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        items.forEach { item ->
            Row(Modifier.padding(start = (item.level * 18).dp)) {
                when {
                    item.checked != null -> Icon(
                        imageVector = if (item.checked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                        contentDescription = if (item.checked) "Completed item" else "Open item",
                        modifier = Modifier.padding(top = 3.dp, end = 6.dp).size(20.dp),
                        tint = if (item.checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    item.ordered -> Text(
                        item.marker,
                        Modifier.width(28.dp).padding(end = 6.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End
                    )
                    else -> Text(
                        when (item.level) {
                            0 -> "•"
                            1 -> "◦"
                            else -> "▪"
                        },
                        Modifier.width(20.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
                Text(inlineMarkdown(item.text), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
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
                    match.groupValues[6].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(match.groupValues[6]) }
                    match.groupValues[7].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(match.groupValues[7]) }
                    else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(match.groupValues[5]) }
                }
                cursor = match.range.last + 1
            }
            append(value.substring(cursor))
        }
    }
}

/** Syntax colors resolved from the active scheme so code reads in light and dark themes. */
@Composable
private fun rememberCodePalette(): CodePalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        val dark = scheme.background.luminance() < 0.5f
        CodePalette(
            keyword = scheme.tertiary,
            string = if (dark) Color(0xFF9CCC8F) else Color(0xFF2E7D32),
            comment = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            number = if (dark) Color(0xFFF2B879) else Color(0xFFB25F00)
        )
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
            val palette = rememberCodePalette()
            val lineSpans = remember(visibleLines, language) { highlightCodeSpans(visibleLines, language) }
            val numberedCode = remember(numberedLines, gutterColor, numberWidth, lineSpans, palette) {
                buildAnnotatedString {
                    numberedLines.forEachIndexed { index, line ->
                        val start = length
                        append(line)
                        addStyle(SpanStyle(color = gutterColor), start, start + numberWidth + 3)
                        val codeStart = start + numberWidth + 3
                        lineSpans.getOrNull(index)?.forEach { span ->
                            addStyle(palette.style(span.kind), codeStart + span.start, codeStart + span.end)
                        }
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


internal enum class MarkdownColumnAlign { LEFT, CENTER, RIGHT }

internal data class MarkdownTableData(
    val header: List<String>,
    val rows: List<List<String>>,
    val alignments: List<MarkdownColumnAlign> = emptyList()
)

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
    val alignments = separator.map { cell ->
        when {
            cell.startsWith(":") && cell.endsWith(":") -> MarkdownColumnAlign.CENTER
            cell.endsWith(":") -> MarkdownColumnAlign.RIGHT
            else -> MarkdownColumnAlign.LEFT
        }
    }
    return MarkdownTableData(header, rows, alignments)
}

private fun tableTextAlign(alignments: List<MarkdownColumnAlign>, column: Int): TextAlign =
    when (alignments.getOrNull(column)) {
        MarkdownColumnAlign.CENTER -> TextAlign.Center
        MarkdownColumnAlign.RIGHT -> TextAlign.End
        else -> TextAlign.Start
    }

@Composable
private fun MarkdownTable(table: MarkdownTableData) {
    Surface(shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                table.header.forEachIndexed { column, cell ->
                    Text(inlineMarkdown(cell), Modifier.width(160.dp).padding(Design.medium),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = tableTextAlign(table.alignments, column))
                }
            }
            table.rows.forEachIndexed { index, row ->
                Row(Modifier.background(if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer)) {
                    row.forEachIndexed { column, cell ->
                        Text(inlineMarkdown(cell), Modifier.width(160.dp).padding(Design.medium),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = tableTextAlign(table.alignments, column))
                    }
                }
            }
        }
    }
}
