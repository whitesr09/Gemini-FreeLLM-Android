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
                val historyFile = File(filesDir, HISTORY)
                // Snapshot once and always write the required entry; don't silently skip it
                // if the live file disappears between an existence check and the ZIP copy.
                val historyBytes = if (historyFile.isFile) historyFile.readBytes()
                    else conversationsToJson(emptyList()).put("active", "").toString().toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(HISTORY))
                zip.write(historyBytes)
                zip.closeEntry()
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

    private const val MAX_ENTRIES = 256
    private const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 16L * 1024

    /** Only paths emitted by this app's exporter are accepted. */
    private fun isAllowedEntry(name: String): Boolean =
        name == MANIFEST || name == HISTORY || name == CREDENTIALS ||
            (name.startsWith("$ATTACHMENTS/") &&
                name.removePrefix("$ATTACHMENTS/").let { it.isNotBlank() && '/' !in it && '\\' !in it && it != "." && it != ".." })

    /** Validates a backup and restores it over the current data. */
    suspend fun import(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "backup-restore").apply { deleteRecursively(); mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var sawManifest = false
                    var entryCount = 0
                    var totalBytes = 0L
                    val seenEntries = mutableSetOf<String>()
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (++entryCount > MAX_ENTRIES) throw BackupException("Backup contains too many files.")
                        val name = entry.name
                        if (!seenEntries.add(name)) throw BackupException("Backup contains duplicate file entries.")
                        if (entry.isDirectory || !isAllowedEntry(name)) {
                            throw BackupException("Backup contains an unsupported or unsafe path.")
                        }
                        val limit = if (name == MANIFEST) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES
                        val out = File(tmp, name)
                        out.parentFile?.mkdirs()
                        var entryBytes = 0L
                        out.outputStream().buffered().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                totalBytes += count
                                if (entryBytes > limit || totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                    throw BackupException("Backup exceeds the safe extraction size limit.")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        if (name == MANIFEST) sawManifest = true
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (!sawManifest) throw BackupException("This file is not a FreeLLM backup.")
                }
            } ?: throw BackupException("Could not open the backup file.")

            // Validate every staged file before touching live data.
            if (JSONObject(File(tmp, MANIFEST).readText()).optInt("version", -1) != BACKUP_VERSION)
                throw BackupException("Unsupported backup version.")
            val stagedHistory = File(tmp, HISTORY)
            if (!stagedHistory.isFile) throw BackupException("Backup is missing chat history.")
            JSONObject(stagedHistory.readText()).getJSONArray("chats")
            File(tmp, CREDENTIALS).takeIf { it.exists() }?.let {
                JSONObject(it.readText()).let { envelope -> envelope.getString("iv"); envelope.getString("data") }
            }
            val stagedAttachments = File(tmp, ATTACHMENTS)
            if (stagedAttachments.exists() && !stagedAttachments.isDirectory) {
                throw BackupException("Backup attachments are invalid.")
            }

            // Stage replacement files beside the live files so rename operations stay on
            // the same filesystem. Keep rollback copies until every replacement succeeds.
            val filesDir = context.filesDir
            val history = File(filesDir, HISTORY)
            val credentials = File(filesDir, CREDENTIALS)
            val attachments = File(filesDir, ATTACHMENTS)
            val transaction = File(context.cacheDir, "backup-restore-transaction").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val stagedHistoryLive = File(filesDir, "$HISTORY.restore-new")
                stagedHistory.copyTo(stagedHistoryLive, overwrite = true)
                val stagedCredentials = File(tmp, CREDENTIALS)
                val credentialsLiveNew = File(filesDir, "$CREDENTIALS.restore-new")
                if (stagedCredentials.exists()) stagedCredentials.copyTo(credentialsLiveNew, overwrite = true)

                val newAttachments = File(filesDir, "$ATTACHMENTS.restore-new").apply {
                    deleteRecursively()
                    mkdirs()
                }
                stagedAttachments.listFiles()?.forEach { file ->
                    if (!file.isFile) throw BackupException("Backup contains an invalid attachment.")
                    file.copyTo(File(newAttachments, file.name), overwrite = true)
                }

                val oldHistory = File(transaction, HISTORY)
                val oldCredentials = File(transaction, CREDENTIALS)
                val oldAttachments = File(transaction, ATTACHMENTS)
                if (history.exists()) history.copyTo(oldHistory, overwrite = true)
                if (credentials.exists()) credentials.copyTo(oldCredentials, overwrite = true)
                if (attachments.exists()) attachments.copyRecursively(oldAttachments, overwrite = true)

                try {
                    if (!stagedHistoryLive.renameTo(history)) throw BackupException("Could not install restored chat history.")
                    if (stagedCredentials.exists() &&
                        !credentialsLiveNew.renameTo(credentials)) throw BackupException("Could not install restored credentials.")
                    if (attachments.exists() && !attachments.deleteRecursively()) throw BackupException("Could not replace attachments.")
                    if (!newAttachments.renameTo(attachments)) throw BackupException("Could not install restored attachments.")
                } catch (failure: Exception) {
                    history.delete()
                    oldHistory.takeIf { it.exists() }?.copyTo(history, overwrite = true)
                    credentials.delete()
                    oldCredentials.takeIf { it.exists() }?.copyTo(credentials, overwrite = true)
                    attachments.deleteRecursively()
                    oldAttachments.takeIf { it.exists() }?.copyRecursively(attachments, overwrite = true)
                    throw failure
                }
            } finally {
                transaction.deleteRecursively()
                File(filesDir, "$HISTORY.restore-new").delete()
                File(filesDir, "$CREDENTIALS.restore-new").delete()
                File(filesDir, "$ATTACHMENTS.restore-new").deleteRecursively()
            }
        } finally {
            tmp.deleteRecursively()
        }
    }
}
