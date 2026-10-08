package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalDataTest {
    @Test fun chatsRoundTripDraftsAttachmentsAndMetadata() {
        val attachment = Attachment(name = "notes.txt", mimeType = "text/plain", size = 12, text = "document text")
        val chats = listOf(Conversation(title = "A chat", draft = "unfinished", pinned = true, archived = true,
            attachments = listOf(attachment), messages = listOf(ChatMessage(text = "answer", role = ChatMessage.Role.ASSISTANT,
                model = "provider/model", interrupted = true, attachments = listOf(attachment)))))
        assertEquals(chats, conversationsFromJson(conversationsToJson(chats)))
    }

    @Test fun settingsRoundTripSeparateProviderKeysAndCapabilities() {
        val settings = AppSettings(profiles = listOf(ProviderProfile(Provider.GEMINI, apiKey = "synthetic-a"),
            ProviderProfile(Provider.CUSTOM, apiKey = "synthetic-b", model = "custom", vision = true, stream = false)), selected = Provider.CUSTOM, theme = "AMOLED")
        assertEquals(settings, settingsFromJson(settingsToJson(settings)))
    }

    @Test fun pdfLiteralEscapesAreDecoded() {
        val file = File.createTempFile("test", ".pdf")
        try { file.writeText("%PDF-1.4\n(Hello\\nworld) Tj"); assertEquals("Hello world", PdfTextExtractor.extract(file)) }
        finally { file.delete() }
    }

    @Test fun officeRejectsZipBombInNonXmlEntry() {
        val file = File.createTempFile("test", ".docx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/media/bomb.bin"))
                val block = ByteArray(1024 * 1024)
                repeat(11) { zip.write(block) }
                zip.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { OfficeDocumentParser.extract(file) }
        } finally { file.delete() }
    }

    @Test fun officeRejectsTraversalEntry() {
        val file = File.createTempFile("test", ".docx")
        try {
            ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../evil.xml")); zip.write("<x/>".toByteArray()); zip.closeEntry() }
            assertThrows(IllegalArgumentException::class.java) { OfficeDocumentParser.extract(file) }
        } finally { file.delete() }
    }
    @Test fun officeReadsNamespacedDocxText() {
        val file = File.createTempFile("test", ".docx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p w:rsidR="123"><w:r><w:t>Hello document</w:t></w:r></w:p></w:body></w:document>""".toByteArray())
                zip.closeEntry()
            }
            assertEquals("Hello document", OfficeDocumentParser.extract(file))
        } finally { file.delete() }
    }

    @Test fun officeResolvesXlsxSharedStrings() {
        val file = File.createTempFile("test", ".xlsx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
                zip.write("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Shared value</t></si></sst>""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row><c t="s"><v>0</v></c></row></sheetData></worksheet>""".toByteArray())
                zip.closeEntry()
            }
            assertTrue(OfficeDocumentParser.extract(file).contains("Shared value"))
        } finally { file.delete() }
    }
    @Test fun pdfHexTextAndLargeLiteralsAreBounded() {
        val file = File.createTempFile("test", ".pdf")
        try {
            file.writeText("%PDF-1.4\n<4869> Tj")
            assertEquals("Hi", PdfTextExtractor.extract(file))
            file.writeText("%PDF-1.4\n(" + "x".repeat(120_001) + ") Tj")
            assertThrows(IllegalArgumentException::class.java) { PdfTextExtractor.extract(file) }
        } finally { file.delete() }
    }
    @Test fun oversizedSpreadsheetIsRejectedInsteadOfSilentlyTruncated() {
        val file = File.createTempFile("test", ".xlsx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
                zip.write(("<worksheet><sheetData>" + "<row><c><v>1</v></c></row>".repeat(401) + "</sheetData></worksheet>").toByteArray())
                zip.closeEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { OfficeDocumentParser.extract(file) }
        } finally { file.delete() }
    }
}
