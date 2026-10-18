package com.nshd.geminifreellm.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

object OfficeDocumentParser {
    private const val MAX_ENTRIES = 256
    private const val MAX_ENTRY_BYTES = 10L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 30L * 1024L * 1024L
    private const val MAX_OUTPUT_CHARS = 120_000

    fun extract(file: File): String {
        val name = file.name.lowercase()
        val text = when {
            name.endsWith(".docx") -> extractDocx(file)
            name.endsWith(".pptx") -> extractPptx(file)
            name.endsWith(".xlsx") -> extractXlsx(file)
            else -> ""
        }
        require(text.length <= MAX_OUTPUT_CHARS) { "The document contains too much text. Attach a shorter excerpt." }
        return text
    }

    private fun extractDocx(file: File): String {
        val output = StringBuilder()
        readXmlEntries(file) { path, xml ->
            if (path == "word/document.xml") {
                parseXml(xml, output, "DOCX")
            }
        }
        return output.toString().trim()
    }

    private fun extractPptx(file: File): String {
        val slides = mutableListOf<Pair<Int, String>>()
        readXmlEntries(file) { path, xml ->
            val match = Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(path)
            if (match != null) {
                val index = match.groupValues[1].toIntOrNull() ?: 0
                val sb = StringBuilder()
                parseXml(xml, sb, "PPTX")
                slides += index to sb.toString().trim()
            }
        }
        return slides.sortedBy { it.first }.joinToString("\n\n") { "Slide " + it.first + "\n" + it.second }
    }

    private fun extractXlsx(file: File): String {
        var sharedStrings = emptyList<String>()
        val sheets = mutableListOf<Pair<String, ByteArray>>()
        readXmlEntries(file) { path, xml ->
            when {
                path == "xl/sharedstrings.xml" -> sharedStrings = parseSharedStrings(xml)
                path.startsWith("xl/worksheets/") && path.endsWith(".xml") -> {
                    sheets += path.substringAfterLast('/') to xml
                }
            }
        }
        return sheets.sortedBy { it.first }.joinToString("\n\n") { (name, xml) ->
            val rows = parseWorksheet(xml, sharedStrings)
            name + "\n" + rows.joinToString("\n")
        }
    }

    private fun parseXml(xml: ByteArray, output: StringBuilder, type: String) {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        var event = parser.eventType
        var currentParagraph = StringBuilder()
        var inText = false
        var heading = false
        var row = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p" -> {
                        currentParagraph = StringBuilder()
                        heading = false
                    }
                    "pStyle" -> heading = type == "DOCX" &&
                        parser.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "val")?.startsWith("Heading", true) == true
                    "t" -> inText = true
                    "tab" -> currentParagraph.append("\t")
                    "br", "cr" -> currentParagraph.append("\n")
                    "tr" -> row = StringBuilder()
                    "tc" -> if (row.isNotEmpty()) row.append("\t")
                }
                XmlPullParser.TEXT -> if (inText) currentParagraph.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "p" -> {
                        val value = currentParagraph.toString().trim()
                        if (value.isNotBlank()) {
                            if (heading) output.append("## ")
                            output.append(value).append('\n')
                        }
                    }
                    "tc" -> if (row.isNotEmpty() && !row.endsWith("\t")) row.append('\t')
                    "tr" -> if (row.isNotBlank()) output.append(row.toString().trimEnd('\t')).append('\n')
                }
            }
            event = parser.next()
        }
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val strings = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        var event = parser.eventType
        var inText = false
        var current = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current = StringBuilder()
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> strings += current.toString()
                }
            }
            event = parser.next()
        }
        return strings
    }

    private fun parseWorksheet(xml: ByteArray, sharedStrings: List<String>): List<String> {
        val rows = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        var event = parser.eventType
        var currentRow = StringBuilder()
        var currentType = ""
        var currentValue = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> currentRow = StringBuilder()
                    "c" -> currentType = parser.getAttributeValue(null, "t").orEmpty()
                    "v", "t", "is" -> currentValue = StringBuilder()
                }
                XmlPullParser.TEXT -> if (currentValue.length < 16_384) currentValue.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> {
                        if (currentValue.isNotEmpty()) {
                            val raw = currentValue.toString()
                            val value = if (currentType == "s") {
                                raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: raw
                            } else raw
                            currentRow.append(value).append('\t')
                        }
                    }
                    "c" -> currentType = ""
                    "row" -> if (currentRow.isNotBlank()) rows += currentRow.toString().trimEnd('\t')
                }
            }
            event = parser.next()
        }
        require(rows.size <= 400) { "The spreadsheet has more than 400 rows. Attach a smaller sheet or a CSV excerpt." }
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
                require(
                    !path.startsWith("/") &&
                        !path.split('/').any { it == ".." } &&
                        !path.contains('\u0000')
                ) { "Unsafe archive entry path." }
                if (!entry.isDirectory && path.endsWith(".xml", true)) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var count = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        count += read
                        total += read
                        require(count <= MAX_ENTRY_BYTES) { "Office XML entry is too large." }
                        require(total <= MAX_TOTAL_BYTES) { "Office archive expands beyond the safe limit." }
                        output.write(buffer, 0, read)
                    }
                    val xml = output.toByteArray()
                    require(!String(xml, StandardCharsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) { "Office XML contains an unsupported document type." }
                    block(path.lowercase(), xml)
                } else {
                    val buffer = ByteArray(8 * 1024)
                    var count = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        count += read
                        total += read
                        require(count <= MAX_ENTRY_BYTES && total <= MAX_TOTAL_BYTES) { "Office archive expands beyond the safe limit." }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
