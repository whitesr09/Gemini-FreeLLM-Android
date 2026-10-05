package com.nshd.geminifreellm.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
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
    private const val MAX_INPUT_FILE_BYTES = 25L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 256
    private const val MAX_ZIP_ENTRY_BYTES = 10L * 1024 * 1024
    private const val MAX_ZIP_TOTAL_BYTES = 30L * 1024 * 1024
    private const val MAX_PDF_PAGES = 8
    private const val IMAGE_MAX_SIDE = 1400
    private const val IMAGE_QUALITY = 72

    fun copyToAppStorage(context: Context, uri: Uri): PreparedAttachment? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: guessMime(uri)
        val name = queryName(context, uri) ?: "attachment"
        val extension = extensionFor(name, mime)
        val safeName = UUID.randomUUID().toString() + if (extension.isBlank()) "" else ".${extension}"
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(dir, safeName)
        val advertisedSize = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.length ?: -1L }.getOrDefault(-1L)
        if (advertisedSize > MAX_INPUT_FILE_BYTES) {
            throw IllegalArgumentException("File is too large. Maximum supported size is 25 MB.")
        }

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_INPUT_FILE_BYTES) {
                        file.delete()
                        throw IllegalArgumentException("File is too large. Maximum supported size is 25 MB.")
                    }
                    output.write(buffer, 0, read)
                }
            }
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

    fun renderPdfPages(context: Context, file: File): List<File> {
        if (!file.exists()) return emptyList()
        val result = mutableListOf<File>()
        val dir = File(context.cacheDir, "rendered_pages").apply { mkdirs() }
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
                        val out = File(dir, "page_${UUID.randomUUID()}.jpg")
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
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
        if (bitmap == null) {
            require(file.length() <= MAX_INPUT_FILE_BYTES) { "Image is too large or unsupported." }
            return file.inputStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_INPUT_FILE_BYTES) throw IllegalArgumentException("Image is too large.")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }
        val oriented = applyExifOrientation(file, bitmap)
        if (oriented !== bitmap) bitmap.recycle()
        val out = ByteArrayOutputStream()
        oriented.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out)
        oriented.recycle()
        return out.toByteArray()
    }

    private fun applyExifOrientation(file: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrElse { bitmap }
    }

    private fun extractOfficeXml(file: File): String {
        val parts = mutableListOf<String>()
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ZIP_ENTRIES) { "Office document contains too many ZIP entries." }
                val rawPath = entry.name.replace('\\', '/')
                require(
                    !rawPath.startsWith("/") &&
                        !rawPath.split('/').any { it == ".." } &&
                        !rawPath.contains("\\u0000")
                ) { "Unsafe ZIP entry path." }

                val path = rawPath.lowercase()
                val wanted = path.endsWith(".xml") &&
                    (path.startsWith("word/") ||
                        path.startsWith("ppt/slides/") ||
                        path == "xl/sharedstrings.xml" ||
                        path.startsWith("xl/worksheets/"))

                if (!entry.isDirectory && wanted) {
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        require(entryBytes <= MAX_ZIP_ENTRY_BYTES) { "ZIP entry is too large." }
                        require(totalBytes <= MAX_ZIP_TOTAL_BYTES) { "Office document expands beyond the safe processing limit." }
                        out.write(buffer, 0, read)
                    }
                    val xml = out.toString(StandardCharsets.UTF_8.name())
                    val text = xml
                        .replace(Regex("<w:tab[^>]*/>"), "\t")
                        .replace(Regex("<br[^>]*/?>"), "\n")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&apos;", "'")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (text.isNotBlank()) parts += text
                } else {
                    // Consume non-target entries without buffering them.
                    val buffer = ByteArray(8 * 1024)
                    while (zip.read(buffer) >= 0) Unit
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
