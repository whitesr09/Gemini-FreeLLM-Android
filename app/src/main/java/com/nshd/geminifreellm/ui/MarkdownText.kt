package com.nshd.geminifreellm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Lightweight markdown renderer for assistant replies: headings, bold, italic,
 * inline code, fenced code blocks, bullet and numbered lists. Dependency-free
 * and defensive — unterminated markers degrade to plain text, never crash.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeSpanStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph ->
                    Text(
                        text = inline(block.text, codeSpanStyle),
                        style = MaterialTheme.typography.bodyLarge
                    )
                is Block.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = inline(block.text, codeSpanStyle),
                        style = style.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                is Block.Code ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                is Block.Bullets ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEach { item ->
                            Row {
                                Text("•  ", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = inline(item, codeSpanStyle),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                is Block.Numbered ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        block.items.forEachIndexed { index, item ->
                            Row {
                                Text("${index + 1}.  ", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = inline(item, codeSpanStyle),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
            }
        }
    }
}

private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Code(val code: String) : Block
    data class Bullets(val items: List<String>) : Block
    data class Numbered(val items: List<String>) : Block
}

private val BulletRegex = Regex("^[-*]\\s+(.*)")
private val NumberedRegex = Regex("^\\d+[.)]\\s+(.*)")

private fun parseBlocks(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val lines = text.lines()
    val paragraph = StringBuilder()
    val bullets = mutableListOf<String>()
    val numbered = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
    }
    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            blocks += Block.Bullets(bullets.toList())
            bullets.clear()
        }
    }
    fun flushNumbered() {
        if (numbered.isNotEmpty()) {
            blocks += Block.Numbered(numbered.toList())
            numbered.clear()
        }
    }
    fun flushLists() {
        flushBullets()
        flushNumbered()
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushParagraph(); flushLists()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks += Block.Code(code.toString().trimEnd('\n'))
            }
            trimmed.startsWith("### ") -> {
                flushParagraph(); flushLists()
                blocks += Block.Heading(3, trimmed.removePrefix("### ").trim())
            }
            trimmed.startsWith("## ") -> {
                flushParagraph(); flushLists()
                blocks += Block.Heading(2, trimmed.removePrefix("## ").trim())
            }
            trimmed.startsWith("# ") -> {
                flushParagraph(); flushLists()
                blocks += Block.Heading(1, trimmed.removePrefix("# ").trim())
            }
            BulletRegex.matches(trimmed) -> {
                flushParagraph(); flushNumbered()
                bullets += BulletRegex.matchEntire(trimmed)!!.groupValues[1].trim()
            }
            NumberedRegex.matches(trimmed) -> {
                flushParagraph(); flushBullets()
                numbered += NumberedRegex.matchEntire(trimmed)!!.groupValues[1].trim()
            }
            trimmed.isBlank() -> {
                flushParagraph(); flushLists()
            }
            else -> {
                flushLists()
                paragraph.appendLine(line)
            }
        }
        i++
    }
    flushParagraph(); flushLists()
    return blocks
}

/** Inline **bold**, *italic* and `code`. Unterminated markers stay literal. */
private fun inline(raw: String, codeStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
    var i = 0
    fun styled(marker: String, style: SpanStyle) {
        val end = raw.indexOf(marker, i + marker.length)
        if (end == -1) {
            // No closing marker — emit the marker literally and move on.
            append(raw.substring(i, i + marker.length))
            i += marker.length
            return
        }
        withStyle(style) { append(raw.substring(i + marker.length, end)) }
        i = end + marker.length
    }
    while (i < raw.length) {
        when {
            raw.startsWith("**", i) -> styled("**", SpanStyle(fontWeight = FontWeight.Bold))
            raw.startsWith("*", i) -> styled("*", SpanStyle(fontStyle = FontStyle.Italic))
            raw.startsWith("`", i) -> styled("`", codeStyle)
            else -> {
                append(raw[i])
                i++
            }
        }
    }
}
