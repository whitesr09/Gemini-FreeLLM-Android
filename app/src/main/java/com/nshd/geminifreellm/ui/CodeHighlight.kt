package com.nshd.geminifreellm.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle

/** Token categories produced by the hand-rolled code highlighter. */
internal enum class CodeSpanKind { KEYWORD, STRING, COMMENT, NUMBER }

/** A half-open [start, end) character range inside one source line. */
internal data class CodeSpan(val start: Int, val end: Int, val kind: CodeSpanKind)

/** Theme-resolved colors for highlighted code, applied by the code block renderer. */
internal data class CodePalette(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color
) {
    fun style(kind: CodeSpanKind): SpanStyle = when (kind) {
        CodeSpanKind.KEYWORD -> SpanStyle(color = keyword)
        CodeSpanKind.STRING -> SpanStyle(color = string)
        CodeSpanKind.COMMENT -> SpanStyle(color = comment, fontStyle = FontStyle.Italic)
        CodeSpanKind.NUMBER -> SpanStyle(color = number)
    }
}

private enum class CodeFamily { C_LIKE, PYTHON, SHELL, HTML, JSON, PLAIN }

private fun keywordsOf(words: String): Set<String> = words.split(' ').toSet()

private val KOTLIN_KEYWORDS = keywordsOf(
    "abstract actual annotation as break by catch class companion const constructor continue " +
        "crossinline data do else enum expect external false final finally for fun get if import in " +
        "infix init inline inner interface internal is it lateinit lazy null object open operator out " +
        "override package private protected public reified return sealed set suspend tailrec this " +
        "throw true try typealias val var vararg when while"
)
private val JAVA_KEYWORDS = keywordsOf(
    "abstract assert boolean break byte case catch char class const continue default do double else " +
        "enum exports extends final finally float for goto if implements import instanceof int " +
        "interface long module native new null package permits private protected public record " +
        "requires return sealed short static strictfp super switch synchronized this throw throws " +
        "transient true false try var void volatile while yield"
)
private val PYTHON_KEYWORDS = keywordsOf(
    "False None True and as assert async await break class continue def del elif else except " +
        "finally for from global if import in is lambda nonlocal not or pass raise return try " +
        "while with yield"
)
private val JS_KEYWORDS = keywordsOf(
    "async await break case catch class const continue debugger default delete do else export " +
        "extends false finally for function if import in instanceof let new null of return static " +
        "super switch this throw true try typeof undefined var void while with yield get set"
)
private val TS_EXTRA_KEYWORDS = keywordsOf(
    "abstract any boolean declare enum implements interface keyof namespace never number private " +
        "protected public readonly string type unknown"
)
private val SHELL_KEYWORDS = keywordsOf(
    "case declare do done echo elif else esac exit export false fi for function if in local " +
        "readonly return set shift source then true typeset unset until while"
)
private val C_KEYWORDS = keywordsOf(
    "auto bool break case char const continue default do double else enum extern false float for " +
        "goto if inline int long null register restrict return short signed sizeof static struct " +
        "switch true typedef union unsigned void volatile while"
)
private val JSON_LITERALS = keywordsOf("true false null")

private fun codeFamily(language: String): CodeFamily = when (language.trim().lowercase()) {
    "kotlin", "kt", "java", "javascript", "js", "typescript", "ts", "jsx", "tsx", "mjs", "cjs",
    "c", "h", "cpp", "c++", "cc", "hpp", "cs", "csharp", "go", "rust", "rs", "swift", "dart",
    "scala", "php", "groovy" -> CodeFamily.C_LIKE
    "python", "py", "python3" -> CodeFamily.PYTHON
    "bash", "sh", "shell", "zsh", "ksh" -> CodeFamily.SHELL
    "html", "xml", "xhtml", "svg", "htm" -> CodeFamily.HTML
    "json", "jsonc", "json5" -> CodeFamily.JSON
    else -> CodeFamily.PLAIN
}

private fun codeKeywords(language: String): Set<String> = when (language.trim().lowercase()) {
    "kotlin", "kt" -> KOTLIN_KEYWORDS
    "java" -> JAVA_KEYWORDS
    "python", "py", "python3" -> PYTHON_KEYWORDS
    "javascript", "js", "jsx", "mjs", "cjs" -> JS_KEYWORDS
    "typescript", "ts", "tsx" -> JS_KEYWORDS + TS_EXTRA_KEYWORDS
    "bash", "sh", "shell", "zsh", "ksh" -> SHELL_KEYWORDS
    "json", "jsonc", "json5" -> JSON_LITERALS
    else -> C_KEYWORDS
}

private fun isIdentifierStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'
private fun isIdentifierPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

/**
 * Highlights [lines] for [language], one span list per line, in line-local coordinates.
 * Deliberately a small hand-rolled scanner (no new dependency): keywords, strings,
 * comments and numbers for the common languages; unknown languages return no spans
 * and render plain. Multi-line state (block comments, triple-quoted strings, JS
 * template literals) is carried across lines, and unterminated constructs simply
 * run to the end of the line, so half-streamed code can never crash it.
 */
internal fun highlightCodeSpans(lines: List<String>, language: String): List<List<CodeSpan>> {
    val family = codeFamily(language)
    if (family == CodeFamily.PLAIN) return lines.map { emptyList() }
    val highlighter = LineHighlighter(family, codeKeywords(language), language.trim().lowercase())
    return lines.map { highlighter.highlight(it) }
}

private class LineHighlighter(
    private val family: CodeFamily,
    private val keywords: Set<String>,
    private val language: String
) {
    private var inBlockComment = false
    private var inHtmlComment = false

    /** Delimiter that closes the current multi-line string: `"""`, `'''` or a backtick. */
    private var openString: String? = null

    private val blockComments = family == CodeFamily.C_LIKE
    private val kotlinRawStrings = language == "kotlin" || language == "kt"
    private val jsTemplates = language in setOf("javascript", "js", "jsx", "mjs", "cjs", "typescript", "ts", "tsx")
    private val stringQuotes: Set<Char> = when (family) {
        CodeFamily.PYTHON, CodeFamily.SHELL -> setOf('"', '\'')
        CodeFamily.C_LIKE -> if (jsTemplates) setOf('"', '\'', '`') else setOf('"', '\'')
        else -> emptySet()
    }

    fun highlight(line: String): List<CodeSpan> = when (family) {
        CodeFamily.HTML -> highlightHtml(line)
        CodeFamily.JSON -> highlightJson(line)
        else -> highlightCode(line)
    }

    private fun lineCommentAt(line: String, i: Int): Boolean = when (family) {
        CodeFamily.C_LIKE -> line.startsWith("//", i)
        CodeFamily.PYTHON -> line[i] == '#'
        CodeFamily.SHELL -> line[i] == '#' && (i == 0 || line[i - 1].isWhitespace())
        else -> false
    }

    private fun tripleDelimiterAt(line: String, i: Int): String? = when {
        family == CodeFamily.PYTHON && line.startsWith("\"\"\"", i) -> "\"\"\""
        family == CodeFamily.PYTHON && line.startsWith("'''", i) -> "'''"
        family == CodeFamily.C_LIKE && kotlinRawStrings && line.startsWith("\"\"\"", i) -> "\"\"\""
        else -> null
    }

    private fun highlightCode(line: String): List<CodeSpan> {
        val spans = mutableListOf<CodeSpan>()
        val n = line.length
        fun add(start: Int, end: Int, kind: CodeSpanKind) {
            if (end > start) spans += CodeSpan(start, end, kind)
        }
        var i = 0
        while (i < n) {
            if (inBlockComment) {
                val close = line.indexOf("*/", i)
                if (close < 0) {
                    add(i, n, CodeSpanKind.COMMENT); i = n
                } else {
                    add(i, close + 2, CodeSpanKind.COMMENT); inBlockComment = false; i = close + 2
                }
                continue
            }
            val open = openString
            if (open != null) {
                val close = line.indexOf(open, i)
                if (close < 0) {
                    add(i, n, CodeSpanKind.STRING); i = n
                } else {
                    add(i, close + open.length, CodeSpanKind.STRING); openString = null; i = close + open.length
                }
                continue
            }
            if (lineCommentAt(line, i)) {
                add(i, n, CodeSpanKind.COMMENT); i = n; continue
            }
            if (blockComments && line.startsWith("/*", i)) {
                val close = line.indexOf("*/", i + 2)
                if (close < 0) {
                    add(i, n, CodeSpanKind.COMMENT); inBlockComment = true; i = n
                } else {
                    add(i, close + 2, CodeSpanKind.COMMENT); i = close + 2
                }
                continue
            }
            val triple = tripleDelimiterAt(line, i)
            if (triple != null) {
                val close = line.indexOf(triple, i + 3)
                if (close < 0) {
                    add(i, n, CodeSpanKind.STRING); openString = triple; i = n
                } else {
                    add(i, close + 3, CodeSpanKind.STRING); i = close + 3
                }
                continue
            }
            val c = line[i]
            if (c in stringQuotes) {
                val escapes = !(family == CodeFamily.SHELL && c == '\'')
                var j = i + 1
                while (j < n) {
                    if (escapes && line[j] == '\\') {
                        j += 2; continue
                    }
                    if (line[j] == c) break
                    j++
                }
                val end = if (j < n) j + 1 else n
                add(i, end, CodeSpanKind.STRING)
                if (j >= n && c == '`') openString = "`"
                i = end
                continue
            }
            if (c.isDigit() && (i == 0 || !isIdentifierPart(line[i - 1]))) {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                add(i, j, CodeSpanKind.NUMBER)
                i = j
                continue
            }
            if (isIdentifierStart(c)) {
                var j = i + 1
                while (j < n && isIdentifierPart(line[j])) j++
                if (line.substring(i, j) in keywords) add(i, j, CodeSpanKind.KEYWORD)
                i = j
                continue
            }
            i++
        }
        return spans
    }

    private fun highlightJson(line: String): List<CodeSpan> {
        val spans = mutableListOf<CodeSpan>()
        val n = line.length
        var i = 0
        while (i < n) {
            val c = line[i]
            if (c == '/' && i + 1 < n && line[i + 1] == '/') {
                spans += CodeSpan(i, n, CodeSpanKind.COMMENT); break
            }
            if (c == '"') {
                var j = i + 1
                while (j < n) {
                    if (line[j] == '\\') {
                        j += 2; continue
                    }
                    if (line[j] == '"') break
                    j++
                }
                val end = if (j < n) j + 1 else n
                var k = end
                while (k < n && line[k] == ' ') k++
                val kind = if (k < n && line[k] == ':') CodeSpanKind.KEYWORD else CodeSpanKind.STRING
                spans += CodeSpan(i, end, kind)
                i = end
                continue
            }
            if (c.isDigit() || (c == '-' && i + 1 < n && line[i + 1].isDigit())) {
                var j = i + 1
                while (j < n && (line[j].isDigit() || line[j] in ".eE+-")) j++
                spans += CodeSpan(i, j, CodeSpanKind.NUMBER)
                i = j
                continue
            }
            if (isIdentifierStart(c)) {
                var j = i + 1
                while (j < n && isIdentifierPart(line[j])) j++
                if (line.substring(i, j) in keywords) spans += CodeSpan(i, j, CodeSpanKind.KEYWORD)
                i = j
                continue
            }
            i++
        }
        return spans
    }

    private fun highlightHtml(line: String): List<CodeSpan> {
        val spans = mutableListOf<CodeSpan>()
        val n = line.length
        var i = 0
        while (i < n) {
            if (inHtmlComment) {
                val close = line.indexOf("-->", i)
                if (close < 0) {
                    spans += CodeSpan(i, n, CodeSpanKind.COMMENT); i = n
                } else {
                    spans += CodeSpan(i, close + 3, CodeSpanKind.COMMENT); inHtmlComment = false; i = close + 3
                }
                continue
            }
            if (line.startsWith("<!--", i)) {
                val close = line.indexOf("-->", i + 4)
                if (close < 0) {
                    spans += CodeSpan(i, n, CodeSpanKind.COMMENT); inHtmlComment = true; i = n
                } else {
                    spans += CodeSpan(i, close + 3, CodeSpanKind.COMMENT); i = close + 3
                }
                continue
            }
            if (line[i] == '<') {
                var j = i + 1
                if (j < n && line[j] == '/') j++
                val nameStart = j
                while (j < n && (line[j].isLetterOrDigit() || line[j] in "-_:.!")) j++
                if (j > nameStart) spans += CodeSpan(nameStart, j, CodeSpanKind.KEYWORD)
                while (j < n && line[j] != '>') {
                    val q = line[j]
                    if (q == '"' || q == '\'') {
                        var k = j + 1
                        while (k < n && line[k] != q) k++
                        val end = if (k < n) k + 1 else n
                        spans += CodeSpan(j, end, CodeSpanKind.STRING)
                        j = end
                    } else j++
                }
                i = if (j < n) j + 1 else n
                continue
            }
            i++
        }
        return spans
    }
}
