package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.CONTINUATION_INSTRUCTION
import com.nshd.geminifreellm.continuationPlan
import com.nshd.geminifreellm.model.*
import org.json.JSONArray
import org.json.JSONObject
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

    @Test fun finishReasonRoundTrips() {
        val chats = listOf(Conversation(title = "Limits", messages = listOf(
            ChatMessage(text = "partial code", role = ChatMessage.Role.ASSISTANT,
                interrupted = true, finishReason = "length"),
            ChatMessage(text = "full answer", role = ChatMessage.Role.ASSISTANT, finishReason = "STOP"))))
        assertEquals(chats, conversationsFromJson(conversationsToJson(chats)))
    }

    @Test fun historyWrittenBeforeFinishReasonStillLoads() {
        val chats = listOf(Conversation(title = "Old", messages = listOf(
            ChatMessage(text = "answer", role = ChatMessage.Role.ASSISTANT, interrupted = true))))
        val json = conversationsToJson(chats)
        // Simulate a pre-upgrade history file: the field was never written.
        json.getJSONArray("chats").getJSONObject(0).getJSONArray("messages").getJSONObject(0).remove("finishReason")
        val restored = conversationsFromJson(json)
        assertEquals(chats, restored)
        assertNull(restored.single().messages.single().finishReason)
    }

    @Test fun tokenLimitReplyIsContinuableAndPlanResumesTheSameMessage() {
        val user = ChatMessage(text = "Write the app", role = ChatMessage.Role.USER)
        val partial = ChatMessage(text = "fun main() {", role = ChatMessage.Role.ASSISTANT,
            interrupted = true, finishReason = "length")
        assertTrue(partial.isContinuable)
        val (display, request) = continuationPlan(listOf(user, partial), partial.id)!!
        assertEquals(listOf(user, partial), display)
        // The partial appears exactly once, as the last assistant turn — the
        // continuation appends to it instead of duplicating its prefix — and
        // the only added turn is the continue instruction.
        assertEquals(1, request.count { it.id == partial.id })
        assertEquals(partial.text, request.last { it.role == ChatMessage.Role.ASSISTANT }.text)
        assertEquals(ChatMessage.Role.USER, request.last().role)
        assertEquals(CONTINUATION_INSTRUCTION, request.last().text)
    }

    @Test fun completedAndUserStoppedRepliesAreNotContinuable() {
        val done = ChatMessage(text = "All of it", role = ChatMessage.Role.ASSISTANT,
            interrupted = false, finishReason = "stop")
        val stopped = ChatMessage(text = "Half of it", role = ChatMessage.Role.ASSISTANT,
            interrupted = true, finishReason = FINISH_REASON_USER_CANCELLED)
        assertFalse(done.isContinuable)
        assertFalse(stopped.isContinuable)
        assertNull(continuationPlan(listOf(done), done.id))
        assertNull(continuationPlan(listOf(stopped), stopped.id))
    }

    @Test fun interruptedStreamWithoutFinishReasonIsContinuable() {
        val partial = ChatMessage(text = "Half an answer", role = ChatMessage.Role.ASSISTANT, interrupted = true)
        assertTrue(partial.isContinuable)
        assertNotNull(continuationPlan(listOf(partial), partial.id))
    }

    @Test fun continuationPlanDropsTrailingErrorsAndRejectsNewerTurns() {
        val user = ChatMessage(text = "Question", role = ChatMessage.Role.USER)
        val partial = ChatMessage(text = "half", role = ChatMessage.Role.ASSISTANT,
            interrupted = true, finishReason = "MAX_TOKENS")
        val error = ChatMessage(text = "Could not complete this reply.", role = ChatMessage.Role.ERROR)
        val (display, _) = continuationPlan(listOf(user, partial, error), partial.id)!!
        assertEquals(listOf(user, partial), display)
        // A newer user turn after the partial blocks continuation of the old reply.
        assertNull(continuationPlan(listOf(user, partial,
            ChatMessage(text = "Next question", role = ChatMessage.Role.USER)), partial.id))
        // An unknown id cannot be continued.
        assertNull(continuationPlan(listOf(user, partial), "missing"))
    }

    @Test fun settingsRoundTripSeparateProviderKeysAndCapabilities() {
        val settings = AppSettings(profiles = listOf(ProviderProfile(Provider.GEMINI, apiKey = "synthetic-a"),
            ProviderProfile(Provider.CUSTOM, apiKey = "synthetic-b", model = "custom", vision = true, stream = false)), selected = Provider.CUSTOM, theme = "AMOLED")
        assertEquals(settings, settingsFromJson(settingsToJson(settings)))
    }

    @Test fun multipleCompatibleAgentsRoundTripAndKeepTheActiveEndpoint() {
        val first = ProviderProfile(
            provider = Provider.CUSTOM, baseUrl = "https://gateway-one.example/v1",
            apiKey = "synthetic-one", model = "model-one", id = "gateway-one", name = "Gateway One"
        )
        val second = ProviderProfile(
            provider = Provider.CUSTOM, baseUrl = "https://gateway-two.example/v1",
            apiKey = "synthetic-two", model = "model-two", id = "gateway-two", name = "Gateway Two",
            vision = true, stream = false
        )
        val settings = AppSettings(
            profiles = listOf(ProviderProfile(Provider.FREELLM), first, second),
            selected = Provider.CUSTOM, activeProfileId = second.id, theme = "VIOLET"
        )

        val restored = settingsFromJson(settingsToJson(settings))
        assertEquals(settings, restored)
        assertEquals(3, restored.profiles.size)
        assertEquals(second.id, restored.active.id)
        assertEquals("Gateway Two", restored.active.name)
        assertEquals("https://gateway-two.example/v1", restored.active.baseUrl)
        assertTrue(restored.active.vision)
        assertFalse(restored.active.stream)
    }

    @Test fun selectingSavedCompatibleAgentPreservesEveryConnection() {
        val first = ProviderProfile(Provider.CUSTOM, "https://one.example/v1", "key-one", "model-one", id = "one", name = "One")
        val second = ProviderProfile(Provider.CUSTOM, "https://two.example/v1", "key-two", "model-two", id = "two", name = "Two")
        val settings = AppSettings(profiles = listOf(first, second), selected = Provider.CUSTOM,
            activeProfileId = first.id, autoRouting = true)

        val selected = settings.withActiveProfile(second.id)!!
        assertEquals(listOf(first, second), selected.profiles)
        assertEquals(second.id, selected.activeProfileId)
        assertEquals(second, selected.active)
        assertEquals("key-one", selected.profiles.first { it.id == first.id }.apiKey)
        assertFalse(selected.autoRouting)
        assertNull(settings.withActiveProfile("missing"))
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
    @Test fun oversizedNonTextPdfLiteralDoesNotRejectLaterReadableText() {
        val file = File.createTempFile("test", ".pdf")
        try {
            file.writeText("%PDF-1.4\\n(" + "x".repeat(120_001) + ") Q\\n(hello) Tj")
            assertEquals("hello", PdfTextExtractor.extract(file))
        } finally { file.delete() }
    }

    @Test fun malformedJsonArrayEntriesAreSkippedWithoutLosingValidObjects() {
        val profile = JSONObject().put("provider", "GEMINI")
            .put("baseUrl", "https://generativelanguage.googleapis.com/v1beta")
            .put("apiKey", "synthetic").put("model", "gemini-test")
            .put("stream", true).put("vision", false).put("fastReplies", false)
        val settings = settingsFromJson(settingsToJson(AppSettings())
            .put("profiles", JSONArray().put("not-an-object").put(profile)))
        assertEquals(listOf(Provider.GEMINI), settings.profiles.map { it.provider })

        val validChat = conversationsToJson(listOf(Conversation(title = "survivor")))
            .getJSONArray("chats").getJSONObject(0)
        val chats = conversationsFromJson(JSONObject().put("chats", JSONArray().put("invalid").put(validChat)))
        assertEquals(listOf("survivor"), chats.map { it.title })
    }

}
