package com.nshd.geminifreellm.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** User-facing backup failures; the message is safe to show. */
class BackupException(message: String) : Exception(message)

/**
 * Local backup: zips the encrypted credentials, chat history and attachments
 * into a single file the user keeps (e.g. in Downloads), and restores it.
 *
 * Note: providers.enc is encrypted with a device-bound Android Keystore key,
 * so API keys only decrypt on the device that created the backup. Chats,
 * settings and attachments restore anywhere; keys must be re-entered on a
 * new device.
 */
object BackupManager {
    const val MIME_TYPE = "application/zip"
    private const val MANIFEST = "manifest.json"
    private const val CREDENTIALS = "providers.enc"
    private const val HISTORY = "conversations.json"
    private const val ATTACHMENTS = "attachments"
    private const val BACKUP_VERSION = 1

    fun suggestedName(): String =
        "FreeLLM-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"

    /** Writes credentials, history and attachments into [uri]. */
    suspend fun export(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(JSONObject().put("version", BACKUP_VERSION).put("app", context.packageName)
                    .put("createdAt", System.currentTimeMillis()).toString().toByteArray())
                zip.closeEntry()
                zip.addFile(File(filesDir, CREDENTIALS), CREDENTIALS)
                zip.addFile(File(filesDir, HISTORY), HISTORY)
                File(filesDir, ATTACHMENTS).listFiles()?.forEach { file ->
                    if (file.isFile) zip.addFile(file, "$ATTACHMENTS/${file.name}")
                }
            }
        } ?: throw BackupException("Could not open the destination.")
    }

    private fun ZipOutputStream.addFile(file: File, entryName: String) {
        if (!file.exists()) return
        putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }

    /** Validates a backup and restores it over the current data. */
    suspend fun import(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "backup-restore").apply { deleteRecursively(); mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var sawManifest = false
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        // Zip-slip guard: entries must stay inside the temp dir.
                        if (name.contains("..") || name.startsWith("/")) throw BackupException("Unsafe entry in backup: $name")
                        val out = File(tmp, name)
                        if (entry.isDirectory) out.mkdirs()
                        else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                            if (name == MANIFEST) sawManifest = true
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (!sawManifest) throw BackupException("This file is not a FreeLLM backup.")
                }
            } ?: throw BackupException("Could not open the backup file.")
            // Validate before touching live data.
            if (JSONObject(File(tmp, MANIFEST).readText()).optInt("version", -1) != BACKUP_VERSION)
                throw BackupException("Unsupported backup version.")
            if (!File(tmp, HISTORY).exists()) throw BackupException("Backup is missing chat history.")
            JSONObject(File(tmp, HISTORY).readText()).getJSONArray("chats")
            File(tmp, CREDENTIALS).takeIf { it.exists() }?.let {
                JSONObject(it.readText()).let { envelope -> envelope.getString("iv"); envelope.getString("data") }
            }
            // Restore.
            val filesDir = context.filesDir
            File(tmp, CREDENTIALS).takeIf { it.exists() }?.copyTo(File(filesDir, CREDENTIALS), overwrite = true)
            File(tmp, HISTORY).copyTo(File(filesDir, HISTORY), overwrite = true)
            val attachments = File(filesDir, ATTACHMENTS).apply { deleteRecursively(); mkdirs() }
            File(tmp, ATTACHMENTS).listFiles()?.forEach { it.copyTo(File(attachments, it.name)) }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
