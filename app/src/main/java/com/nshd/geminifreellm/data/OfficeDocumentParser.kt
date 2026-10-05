package com.nshd.geminifreellm.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

object OfficeDocumentParser {
    private const val MAX_ENTRIES = 256
    private const val MAX_ENTRY_BYTES = 10L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 30L * 1024L * 1024L
    private const val MAX_OUTPUT_CHARS = 120_000

    fun extract(file: File): String = when {
        file.name.endsWith(".docx", true) -> extractDocx(file)
        file.name.endsWith(".pptx", true) -> extractPptx(file)
        file.name.endsWith(".xlsx", true) -> extractXlsx(file)
        else -> ""
    }.take(MAX_OUTPUT_CHARS)

    private fun extractDocx(file: File): String {
        val out = StringBuilder()
        readXmlEntries(file) { path, xml -> if (path == "word/document.xml") parseParagraphs(xml, out) }
        return out.toString().trim()
    }

    private fun extractPptx(file: File): String {
        val slides = mutableListOf<Pair<Int, String>>()
        readXmlEntries(file) { path, xml ->
            val m = Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(path)
            if (m != null) slides += (m.groupValues[1].toIntOrNull() ?: 0) to extractTextNodes(xml)
        }
        return slides.sortedBy { it.first }.joinToString("\n\n") { "Slide " + it.first + "\n" + it.second }
    }

    private fun extractXlsx(file: File): String {
        var shared = emptyList<String>()
        val sheets = mutableListOf<Pair<String, ByteArray>>()
        readXmlEntries(file) { path, xml ->
            when {
                path == "xl/sharedstrings.xml" -> shared = parseSharedStrings(xml)
                path.startsWith("xl/worksheets/") && path.endsWith(".xml") ->
                    sheets += path.substringAfterLast('/') to xml
            }
        }
        return sheets.sortedBy { it.first }.joinToString("\n\n") { (name, xml) ->
            name + "\n" + parseWorksheet(xml, shared).joinToString("\n")
        }
    }

    private fun parseParagraphs(xml: ByteArray, out: StringBuilder) {
        val parser = parser(xml)
        var event = parser.eventType
        var current = StringBuilder()
        var inText = false
        while (event != XmlPullParser.END_DOCUMENT && out.length < MAX_OUTPUT_CHARS) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "p" -> current = StringBuilder()
                    "t" -> inText = true
                    "tab" -> current.append('\t')
                    "br", "cr" -> current.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "t" -> inText = false
                    "p" -> if (current.isNotBlank()) out.append(current.toString().trim()).append('\n')
                }
            }
            event = parser.next()
        }
    }

    private fun extractTextNodes(xml: ByteArray): String {
        val out = StringBuilder()
        val parser = parser(xml)
        var event = parser.eventType
        var inText = false
        while (event != XmlPullParser.END_DOCUMENT && out.length < MAX_OUTPUT_CHARS) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name.lowercase() == "t") inText = true
                XmlPullParser.TEXT -> if (inText) out.append(parser.text).append(' ')
                XmlPullParser.END_TAG -> if (parser.name.lowercase() == "t") inText = false
            }
            event = parser.next()
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val result = mutableListOf<String>()
        val parser = parser(xml)
        var event = parser.eventType
        var inText = false
        var current = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name.lowercase() == "t") inText = true
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name.lowercase() == "t") {
                    inText = false
                    result += current.toString()
                    current = StringBuilder()
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun parseWorksheet(xml: ByteArray, shared: List<String>): List<String> {
        val rows = mutableListOf<String>()
        val parser = parser(xml)
        var event = parser.eventType
        var row = StringBuilder()
        var cellType: String? = null
        var value = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT && rows.size < 500) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "row" -> row = StringBuilder()
                    "c" -> cellType = parser.getAttributeValue(null, "t")
                    "v" -> value = StringBuilder()
                }
                XmlPullParser.TEXT -> if (value.length < 16_384) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "v" -> {
                        val raw = value.toString()
                        val resolved = if (cellType == "s") raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: raw else raw
                        if (resolved.isNotBlank()) row.append(resolved).append('\t')
                    }
                    "c" -> cellType = null
                    "row" -> if (row.isNotBlank()) rows += row.toString().trimEnd('\t')
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun readXmlEntries(file: File, block: (String, ByteArray) -> Unit) {
        var entries = 0
        var total = 0L
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "Office archive contains too many entries." }
                val path = entry.name.replace('\\', '/')
                require(path.isNotBlank() && !path.startsWith("/") && !path.split('/').any { it == ".." } && !path.contains('\u0000'))
                if (!entry.isDirectory && path.endsWith(".xml", true)) {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var count = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        count += read
                        total += read
                        require(count <= MAX_ENTRY_BYTES) { "Office XML entry is too large." }
                        require(total <= MAX_TOTAL_BYTES) { "Office archive exceeds safe expanded size." }
                        out.write(buffer, 0, read)
                    }
                    block(path.lowercase(), out.toByteArray())
                }
                zip.closeEntry()
            }
        }
    }

    private fun parser(xml: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
}
