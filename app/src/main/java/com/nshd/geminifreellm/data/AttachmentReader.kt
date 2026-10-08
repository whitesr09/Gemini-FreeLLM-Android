package com.nshd.geminifreellm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

object AttachmentReader {
    private const val MAX_BYTES = 8 * 1024 * 1024
    suspend fun read(context: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.take(160) ?: "Attachment"
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri).orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Choose a file smaller than 8 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("This file could not be opened.")
        require(bytes.isNotEmpty()) { "This file is empty." }
        if (mime.startsWith("image/")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image format cannot be opened." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error("This image could not be decoded.")
            val folder = File(context.filesDir, "attachments").apply { mkdirs() }
            val file = File(folder, "${newId()}.jpg")
            try {
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
                coroutineContext.ensureActive()
                Attachment(name = name, mimeType = "image/jpeg", size = file.length(), localPath = file.path)
            } catch (error: Exception) { file.delete(); throw error }
            finally { bitmap.recycle() }
        } else {
            val text = when {
                extension in setOf("docx", "pptx", "xlsx", "pdf") -> {
                    val file = File.createTempFile("extract-", ".$extension", context.cacheDir)
                    try {
                        file.writeBytes(bytes)
                        if (extension == "pdf") PdfTextExtractor.extract(file) else OfficeDocumentParser.extract(file)
                    } finally { file.delete() }
                }
                mime.startsWith("text/") || extension in setOf("txt", "md", "csv", "json", "kt", "py", "java", "js", "html", "xml", "log") -> {
                    require(bytes.none { it == 0.toByte() }) { "This file is not supported UTF-8 text." }
                    bytes.toString(Charsets.UTF_8)
                }
                else -> error("Choose an image, text, PDF, DOCX, PPTX, or XLSX file.")
            }
            require(text.isNotBlank()) { "No readable text found. Scanned or compressed PDFs may need conversion to text first." }
            require(text.length <= 120_000) { "The document contains too much text. Attach a shorter excerpt." }
            Attachment(name = name, mimeType = mime.ifBlank { "text/plain" }, size = bytes.size.toLong(), text = text)
        }
    }
}
