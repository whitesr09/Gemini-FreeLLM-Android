package com.nshd.geminifreellm.data

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.nshd.geminifreellm.model.ChatMessage
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val extension: String, val mime: String) {
    TEXT("txt", "text/plain"),
    MARKDOWN("md", "text/markdown"),
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    JSON("json", "application/json"),
    HTML("html", "text/html")
}

object DocumentExporter {
    fun renderText(message: ChatMessage, format: ExportFormat): File {
        val file = File.createTempFile("ai_export_", ".${format.extension}")
        when (format) {
            ExportFormat.TEXT, ExportFormat.MARKDOWN -> file.writeText(message.text, StandardCharsets.UTF_8)
            ExportFormat.PDF -> writePdf(file, message.text)
            ExportFormat.DOCX -> writeDocx(file, message.text)
            ExportFormat.JSON -> file.writeText(org.json.JSONObject().put("id", message.id).put("role", message.role.name).put("timestamp", message.timestamp).put("text", message.text).put("parentMessageId", message.parentMessageId).toString(2), StandardCharsets.UTF_8)
            ExportFormat.HTML -> file.writeText("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>FreeLLM AI export</title></head><body><article><pre>" + escapeHtml(message.text) + "</pre></article></body></html>", StandardCharsets.UTF_8)
        }
        return file
    }

    private fun writePdf(file: File, text: String) {
        val document = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 42f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        var pageIndex = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create())
        var canvas = page.canvas
        var y = margin

        fun newPage() {
            document.finishPage(page)
            pageIndex++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create())
            canvas = page.canvas
            y = margin
        }

        wrapText(text, paint, pageWidth - margin * 2).forEach { line ->
            if (y > pageHeight - margin) newPage()
            canvas.drawText(line, margin, y, paint)
            y += 18f
        }

        document.finishPage(page)
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
    }

    private fun wrapText(text: String, paint: Paint, width: Float): List<String> {
        val output = mutableListOf<String>()
        text.split("\n").forEach { raw ->
            if (raw.isBlank()) {
                output += ""
                return@forEach
            }
            var current = raw
            while (paint.measureText(current) > width) {
                var cut = current.length
                while (cut > 1 && paint.measureText(current.substring(0, cut)) > width) cut--
                val space = current.lastIndexOf(' ', cut - 1)
                if (space > 0) cut = space
                output += current.substring(0, cut).trimEnd()
                current = current.substring(cut).trimStart()
            }
            output += current
        }
        return output
    }

    private fun writeDocx(file: File, text: String) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/document.xml"))
            val body = text.split("\n").joinToString("") { line ->
                "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>"
            }
            val xml = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
            zip.write(xml.toByteArray())
            zip.closeEntry()
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\\\"", "&quot;")

    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
