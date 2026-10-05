package com.nshd.geminifreellm.data

import android.content.Context
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatSession
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupImportResult(val sessions: List<ChatSession>, val restoredFiles: Int)

object BackupManager {
    private const val BACKUP_VERSION = 1
    private const val MAX_BACKUP_BYTES = 100L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 25L * 1024L * 1024L
    private const val MAX_ENTRIES = 2048
    private const val MANIFEST = "manifest.json"
    private const val CHATS = "chats.json"

    fun createBackup(context: Context, sessions: List<ChatSession>): File {
        val app = context.applicationContext
        val file = File(app.cacheDir, "freellm-backup-" + System.currentTimeMillis() + ".zip")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            val manifest = JSONObject()
                .put("format", "Gemini-FreeLLM-Android")
                .put("version", BACKUP_VERSION)
                .put("createdAt", System.currentTimeMillis())
                .put("sessionCount", sessions.size)
            putText(zip, MANIFEST, manifest.toString(2))
            putText(zip, CHATS, JSONObject()
                .put("version", 3)
                .put("exportedAt", System.currentTimeMillis())
                .put("sessions", org.json.JSONArray().apply { sessions.forEach { put(it.toJson()) } })
                .toString(2))

            sessions.asSequence()
                .flatMap { it.messages.asSequence() }
                .flatMap { it.attachments.asSequence() }
                .distinctBy { it.id }
                .forEach { attachment ->
                    val source = File(attachment.localPath)
                    if (!source.isFile) return@forEach
                    require(source.length() <= MAX_ENTRY_BYTES) { "Attachment exceeds the backup file limit." }
                    val prefix = if (attachment.kind == Attachment.Kind.GENERATED_IMAGE || attachment.kind == Attachment.Kind.GENERATED_VIDEO) "generated/" else "attachments/"
                    val entry = ZipEntry(prefix + attachment.id + "-" + sanitizeName(attachment.name))
                    zip.putNextEntry(entry)
                    FileInputStream(source).use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_ENTRY_BYTES) { "Attachment exceeds the backup file limit." }
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
        }
        require(file.length() <= MAX_BACKUP_BYTES) { "Backup exceeds the maximum supported size." }
        return file
    }

    fun importBackup(context: Context, file: File): BackupImportResult {
        require(file.isFile) { "Backup file is missing." }
        require(file.length() <= MAX_BACKUP_BYTES) { "Backup exceeds the maximum supported size." }
        val app = context.applicationContext
        val staged = File(app.cacheDir, "backup-import-" + UUID.randomUUID()).apply { mkdirs() }
        val extracted = mutableMapOf<String, File>()
        var entries = 0
        var total = 0L
        var chatsJson: String? = null
        try {
            ZipInputStream(FileInputStream(file).buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ENTRIES) { "Backup contains too many entries." }
                    val name = entry.name.replace('\\', '/')
                    require(!name.startsWith("/") && !name.split('/').any { it == ".." } && !name.contains("\u0000")) {
                        "Backup contains an unsafe path."
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val out = File(staged, name)
                    out.parentFile?.mkdirs()
                    var entryBytes = 0L
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            total += read
                            require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_BACKUP_BYTES) { "Backup expands beyond the safe processing limit." }
                            output.write(buffer, 0, read)
                        }
                    }
                    when {
                        name == MANIFEST -> {
                            val manifest = JSONObject(out.readText(StandardCharsets.UTF_8).take(64_000))
                            require(manifest.optString("format") == "Gemini-FreeLLM-Android") { "Unsupported backup format." }
                            require(manifest.optInt("version", -1) == BACKUP_VERSION) { "Unsupported backup version." }
                        }
                        name == CHATS -> chatsJson = out.readText(StandardCharsets.UTF_8).take(12_000_000)
                        name.startsWith("attachments/") || name.startsWith("generated/") ->
                            extracted[name.substringAfter('/').substringBefore('-')] = out
                    }
                    zip.closeEntry()
                }
            }
            val raw = chatsJson ?: error("Backup is missing chats.json.")
            val root = JSONObject(raw)
            val sessions = mutableListOf<ChatSession>()
            val array = root.optJSONArray("sessions") ?: org.json.JSONArray()
            for (i in 0 until array.length()) {
                val session = ChatSession.fromJson(array.getJSONObject(i))
                val restored = session.copy(messages = session.messages.map { message ->
                    message.copy(attachments = message.attachments.map { attachment ->
                        val stagedFile = extracted[attachment.id]
                        if (stagedFile == null) attachment.copy(localPath = "")
                        else {
                            val destinationDir = if (attachment.kind == Attachment.Kind.GENERATED_IMAGE || attachment.kind == Attachment.Kind.GENERATED_VIDEO) {
                                File(app.filesDir, "generated")
                            } else File(app.filesDir, "attachments")
                            destinationDir.mkdirs()
                            val destination = File(destinationDir, UUID.randomUUID().toString() + "-" + sanitizeName(attachment.name))
                            stagedFile.copyTo(destination, overwrite = false)
                            attachment.copy(localPath = destination.absolutePath, sizeBytes = destination.length())
                        }
                    })
                })
                sessions += restored
            }
            return BackupImportResult(
                sessions,
                sessions.sumOf { it.messages.sumOf { m -> m.attachments.count { a -> a.localPath.isNotBlank() } } }
            )
        } finally {
            staged.deleteRecursively()
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, value: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(value.toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
    }

    private fun sanitizeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "file" }
}
