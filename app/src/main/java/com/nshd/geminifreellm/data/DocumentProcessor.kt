package com.nshd.geminifreellm.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.Context
import android.webkit.MimeTypeMap
import com.nshd.geminifreellm.model.Attachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipInputStream

data class PreparedAttachment(
    val attachment: Attachment,
    val extractedText: String? = null
)

object DocumentProcessor {
    private const val MAX_TEXT_CHARS = 120_000
    private const val MAX_PDF_PAGES = 8
    private const val IMAGE_MAX_SIDE = 1400
    private const val IMAGE_QUALITY = 72

    fun copyToAppStorage(context: Context, uri: Uri): PreparedAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: guessMime(uri)
        val name = queryName(context, uri) ?: "attachment"
        val extension = extensionFor(name, mime)
        val safeName = UUID.randomUUID().toString() + if (extension.isBlank()) "" else ".\${extension}"
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(dir, safeName)

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        } ?: return null

        val kind = if (mime.startsWith("image/")) Attachment.Kind.IMAGE else Attachment.Kind.FILE
        val attachment = Attachment(
            id = UUID.randomUUID().toString(),
            name = name,
            mimeType = mime,
            localPath = file.absolutePath,
            sizeBytes = file.length(),
            kind = kind
        )
        return PreparedAttachment(attachment, extractText(context, file, mime, name))
    }

    fun extractText(context: Context, file: File, mime: String, name: String): String? {
        if (mime.startsWith("text/") || isTextExtension(name)) {
            return file.readText(StandardCharsets.UTF_8).take(MAX_TEXT_CHARS)
        }
        if (mime == "application/json" || mime == "application/xml" || mime == "text/html") {
            return file.readText(StandardCharsets.UTF_8).take(MAX_TEXT_CHARS)
        }
        val lower = name.lowercase()
        return when {
            lower.endsWith(".docx") || lower.endsWith(".pptx") || lower.endsWith(".xlsx") ->
                extractOfficeXml(file).take(MAX_TEXT_CHARS)
            else -> null
        }
    }

    fun renderPdfPages(file: File): List<File> {
        if (!file.exists()) return emptyList()
        val result = mutableListOf<File>()
        val dir = File(file.parentFile, "rendered_pages").apply { mkdirs() }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PDF_PAGES)
                for (index in 0 until count) {
                    renderer.openPage(index).use { page ->
                        val scale = IMAGE_MAX_SIDE.toFloat() / maxOf(page.width, page.height).toFloat()
                        val width = maxOf(1, (page.width * scale).toInt())
                        val height = maxOf(1, (page.height * scale).toInt())
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = File(dir, "page_\${UUID.randomUUID()}.jpg")
                        FileOutputStream(out).use { fos ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, fos)
                        }
                        bitmap.recycle()
                        result += out
                    }
                }
            }
        }
        return result
    }

    fun compressImage(file: File): ByteArray {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        var sample = 1
        while (maxOf(options.outWidth / sample, options.outHeight / sample) > IMAGE_MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return file.readBytes()
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun extractOfficeXml(file: File): String {
        val parts = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.lowercase()
                val wanted = path.endsWith(".xml") &&
                    (path.contains("word/") || path.contains("ppt/slides/") || path.contains("xl/sharedstrings") || path.contains("xl/worksheets/"))
                if (!entry.isDirectory && wanted) {
                    val bytes = zip.readBytes()
                    val xml = String(bytes, StandardCharsets.UTF_8)
                    val text = xml
                        .replace(Regex("<w:tab[^>]*/>"), "\t")
                        .replace(Regex("<br[^>]*/?>"), "\n")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (text.isNotBlank()) parts += text
                }
                zip.closeEntry()
            }
        }
        return parts.joinToString("\n").take(MAX_TEXT_CHARS)
    }

    private fun isTextExtension(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(".txt", ".md", ".csv", ".json", ".xml", ".html", ".htm", ".kt", ".java", ".py", ".js", ".ts", ".tsx", ".jsx", ".css", ".scss", ".yaml", ".yml", ".sql", ".c", ".cpp", ".h", ".hpp", ".log")
            .any { lower.endsWith(it) }
    }

    private fun extensionFor(name: String, mime: String): String {
        val fromName = name.substringAfterLast('.', "")
        if (fromName.isNotBlank() && fromName.length <= 8) return fromName
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty()
    }

    private fun guessMime(uri: Uri): String {
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            uri.toString().substringAfterLast('.', "")
        ) ?: "application/octet-stream"
    }

    private fun queryName(context: Context, uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment
    }
}
