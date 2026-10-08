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
import androidx.compose.material.icons.outlined.WrapText
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
import kotlinx.coroutines.delay

internal data class MarkdownBlock(val text: String, val language: String? = null)

/** Unclosed fences are deliberately rendered as code while the response streams. */
internal fun markdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    var code = false
    var language = ""
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotEmpty() || code) blocks += MarkdownBlock(buffer.toString().trimEnd('\n'), if (code) language else null)
        buffer.setLength(0)
    }
    text.lineSequence().forEach { line ->
        if (line.trimStart().startsWith("```")) {
            flush()
            code = !code
            language = if (code) line.trim().removePrefix("```").take(30) else ""
        } else buffer.append(line).append('\n')
    }
    flush()
    return blocks
}

@Composable
fun MessageContent(text: String, onCanvas: (String, String) -> Unit = { _, _ -> }) {
    val blocks = remember(text) { markdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(Design.medium)) {
        blocks.forEach { block ->
            if (block.language != null) CodeBlock(block, onCanvas)
            else SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(Design.small)) {
                    // Paragraph blocks keep long responses inexpensive; no composable per token.
                    block.text.split(Regex("\n\\s*\n")).forEach { paragraph ->
                        val heading = Regex("^(#{1,6})\\s+(.*)", RegexOption.DOT_MATCHES_ALL).matchEntire(paragraph)
                        val table = markdownTable(paragraph)
                        val quote = paragraph.startsWith("> ")
                        val displayed = when {
                            heading != null -> heading.groupValues[2]
                            quote -> paragraph.lineSequence().joinToString("\n") { it.removePrefix("> ") }
                            else -> paragraph.replace(Regex("(?m)^(\\s*)[-*] "), "$1• ")
                        }
                        if (paragraph.trim() in listOf("---", "***", "___")) HorizontalDivider(Modifier.padding(vertical = Design.small))
                        else if (table != null) MarkdownTable(table)
                        else Text(inlineMarkdown(displayed),
                            modifier = if (quote) Modifier.background(MaterialTheme.colorScheme.surfaceContainer, Design.card).padding(Design.medium)
                                else Modifier,
                            style = when {
                                heading != null && heading.groupValues[1].length <= 2 -> MaterialTheme.typography.titleLarge
                                heading != null -> MaterialTheme.typography.titleMedium
                                else -> MaterialTheme.typography.bodyLarge
                            })
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
            val pattern = Regex("\\[([^]\\n]+)]\\((https?://[^\\s)]+)\\)|`([^`\\n]+)`|\\*\\*([^*]+)\\*\\*|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)")
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
    var wrap by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
    Surface(shape = Design.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = Design.medium), verticalAlignment = Alignment.CenterVertically) {
                Text(block.language.orEmpty().ifBlank { "Code" }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { wrap = !wrap }) { Icon(Icons.Outlined.WrapText, if (wrap) "Disable code wrapping" else "Wrap code lines", Modifier.size(20.dp)) }
                IconButton(onClick = { onCanvas(block.text, block.language.orEmpty()) }, enabled = block.text.length <= 120_000) { Icon(Icons.Outlined.Code, "Open code in canvas", Modifier.size(20.dp)) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }) {
                    Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Design.small)); Text(if (copied) "Copied" else "Copy code")
                }
            }
            SelectionContainer {
                Text(block.text, Modifier.fillMaxWidth().then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState())).padding(Design.medium),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), softWrap = wrap)
            }
        }
    }
}


internal data class MarkdownTableData(val header: List<String>, val rows: List<List<String>>)

internal fun markdownTable(text: String): MarkdownTableData? {
    val lines = text.trim().lines()
    if (lines.size < 2) return null
    fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|")
        .split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
    val header = cells(lines.first())
    val separator = cells(lines[1])
    if (header.size !in 2..12 || separator.size != header.size || separator.any { !it.matches(Regex(":?-{3,}:?")) }) return null
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
