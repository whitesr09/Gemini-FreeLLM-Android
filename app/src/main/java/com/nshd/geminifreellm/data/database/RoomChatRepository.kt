package com.nshd.geminifreellm.data.database

import android.content.Context
import androidx.room.withTransaction
import com.nshd.geminifreellm.data.AppDiagnostics
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ChatSession
import com.nshd.geminifreellm.model.ResponseMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class CleanupResult(
    val deletedFiles: Int,
    val reclaimedBytes: Long,
    val missingReferences: Int = 0
)

data class StorageSnapshot(
    val databaseBytes: Long,
    val attachmentBytes: Long,
    val generatedBytes: Long,
    val cacheBytes: Long,
    val documentIndexBytes: Long,
    val documentChunkCount: Long,
    val missingReferences: Int,
    val sessionCount: Long
)

class RoomChatRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.get(appContext)
    private val dao = database.chatDao()
    private val legacyPrefs = appContext.getSharedPreferences("chat_store_v2", Context.MODE_PRIVATE)
    private val metaPrefs = appContext.getSharedPreferences("room_chat_meta", Context.MODE_PRIVATE)

    fun observeSessions(): Flow<List<ChatSession>> =
        dao.observeSessions().map { entities ->
            entities.map { entity ->
                entity.toDomain(dao.getMessages(entity.id), dao.getAttachments(entity.id))
            }
        }

    suspend fun loadSessions(): List<ChatSession> {
        migrateLegacyIfNeeded()
        return dao.getSessions().map { entity ->
            entity.toDomain(dao.getMessages(entity.id), dao.getAttachments(entity.id))
        }
    }

    suspend fun saveSession(session: ChatSession, model: String = "auto") {
        if (session.temporary) return
        database.withTransaction {
            dao.upsertSession(
                ChatSessionEntity(
                    id = session.id,
                    title = session.title,
                    createdAt = session.createdAt,
                    updatedAt = session.updatedAt,
                    starred = session.starred,
                    model = model,
                    systemPrompt = session.systemPrompt,
                    temporary = false,
                    archived = session.archived
                )
            )
            dao.deleteAttachmentsForSession(session.id)
            dao.deleteMessagesForSession(session.id)
            dao.upsertMessages(session.messages.map { message ->
                ChatMessageEntity(
                    id = message.id,
                    sessionId = session.id,
                    role = message.role.name,
                    content = message.text,
                    timestamp = message.timestamp,
                    status = if (message.role == ChatMessage.Role.ERROR) "error" else "complete",
                    model = message.metadata?.model ?: model,
                    provider = message.metadata?.provider,
                    latencyMs = message.metadata?.latencyMs,
                    requestId = message.metadata?.requestId,
                    errorCode = null,
                    parentMessageId = message.parentMessageId
                )
            })
            dao.upsertAttachments(session.messages.flatMap { message ->
                message.attachments.map { attachment ->
                    AttachmentEntity(
                        id = attachment.id,
                        messageId = message.id,
                        name = attachment.name,
                        mimeType = attachment.mimeType,
                        localPath = attachment.localPath,
                        sizeBytes = attachment.sizeBytes,
                        kind = attachment.kind.name,
                        createdAt = attachment.createdAt
                    )
                }
            })
        }
    }

    suspend fun deleteSessions(ids: Set<String>) {
        if (ids.isNotEmpty()) dao.deleteSessions(ids.toList())
    }

    suspend fun clearAll() = dao.clearAll()

    suspend fun storageSnapshot(): StorageSnapshot {
        val rows = dao.getAllAttachments()
        return StorageSnapshot(
            databaseBytes = appContext.getDatabasePath("freellm.db").length(),
            attachmentBytes = dao.attachmentBytes(),
            generatedBytes = directoryBytes(File(appContext.filesDir, "generated")),
            cacheBytes = directoryBytes(appContext.cacheDir),
            documentIndexBytes = dao.documentChunksBytes(),
            documentChunkCount = dao.documentChunkCount(),
            missingReferences = rows.count { it.localPath.isBlank() || !File(it.localPath).isFile },
            sessionCount = dao.sessionCount()
        )
    }

    suspend fun consistencyCheck(): CleanupResult {
        val missing = dao.getAllAttachments().count { it.localPath.isBlank() || !File(it.localPath).isFile }
        return CleanupResult(0, 0, missing)
    }

    suspend fun cleanupOrphans(): CleanupResult {
        val referenced = dao.getAllAttachmentPaths()
            .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
            .toHashSet()
        var deleted = 0
        var bytes = 0L
        listOf(File(appContext.filesDir, "attachments"), File(appContext.filesDir, "generated"))
            .forEach { root ->
                root.listFiles()?.forEach { file ->
                    if (!file.isFile) return@forEach
                    val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
                    if (canonical !in referenced) {
                        val size = file.length()
                        if (file.delete()) { deleted++; bytes += size }
                    }
                }
            }
        return CleanupResult(deleted, bytes, consistencyCheck().missingReferences)
    }

    fun currentId(): String? = metaPrefs.getString("current_id", null)

    fun saveCurrentId(id: String) = metaPrefs.edit().putString("current_id", id).apply()

    fun newSession(temporary: Boolean = false): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = if (temporary) "Temporary chat" else "New chat",
            createdAt = now,
            updatedAt = now,
            temporary = temporary
        )
    }

    fun exportJson(sessions: List<ChatSession>): String =
        JSONObject()
            .put("version", 4)
            .put("exportedAt", System.currentTimeMillis())
            .put("sessions", JSONArray().apply {
                sessions.filterNot { it.temporary }.forEach { put(it.toJson()) }
            })
            .toString(2)

    fun importJson(raw: String): List<ChatSession> {
        val root = JSONObject(raw)
        val version = root.optInt("version", 1)
        require(version in 1..4) { "Unsupported backup version: " + version }
        val array = root.optJSONArray("sessions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                runCatching { ChatSession.fromJson(obj).takeIf { it.id.isNotBlank() && !it.temporary } }
                    .getOrNull()?.let(::add)
            }
        }
    }

    suspend fun importSessions(sessions: List<ChatSession>, model: String = "auto") {
        sessions.filterNot { it.temporary }.forEach { saveSession(it, model) }
    }

    private suspend fun migrateLegacyIfNeeded() {
        if (metaPrefs.getBoolean("legacy_migration_complete", false)) return
        val raw = legacyPrefs.getString("sessions", null)
        if (raw.isNullOrBlank()) {
            metaPrefs.edit().putBoolean("legacy_migration_complete", true).apply()
            return
        }

        val migrated = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    runCatching { ChatSession.fromJson(obj).takeIf { it.id.isNotBlank() } }
                        .getOrNull()?.let(::add)
                }
            }
        }.getOrElse {
            AppDiagnostics.recordEvent(appContext, "legacy_migration_parse_failed")
            return
        }

        runCatching {
            database.withTransaction {
                migrated.forEach { session ->
                    dao.upsertSession(
                        ChatSessionEntity(
                            id = session.id,
                            title = session.title,
                            createdAt = session.createdAt,
                            updatedAt = session.updatedAt,
                            starred = session.starred,
                            model = "auto",
                            systemPrompt = session.systemPrompt,
                            temporary = session.temporary,
                            archived = session.archived
                        )
                    )
                    dao.upsertMessages(session.messages.map { message ->
                        ChatMessageEntity(
                            id = message.id,
                            sessionId = session.id,
                            role = message.role.name,
                            content = message.text,
                            timestamp = message.timestamp,
                            status = if (message.role == ChatMessage.Role.ERROR) "error" else "complete",
                            model = message.metadata?.model ?: "auto",
                            provider = message.metadata?.provider,
                            latencyMs = message.metadata?.latencyMs,
                            requestId = message.metadata?.requestId,
                            parentMessageId = message.parentMessageId
                        )
                    })
                    dao.upsertAttachments(session.messages.flatMap { message ->
                        message.attachments.map { attachment ->
                            AttachmentEntity(
                                id = attachment.id,
                                messageId = message.id,
                                name = attachment.name,
                                mimeType = attachment.mimeType,
                                localPath = attachment.localPath,
                                sizeBytes = attachment.sizeBytes,
                                kind = attachment.kind.name,
                                createdAt = attachment.createdAt
                            )
                        }
                    })
                }
            }
            legacyPrefs.getString("current_id", null)?.let(::saveCurrentId)
            legacyPrefs.edit().remove("sessions").remove("current_id").apply()
            metaPrefs.edit().putBoolean("legacy_migration_complete", true).apply()
            AppDiagnostics.recordEvent(appContext, "legacy_migration_completed")
        }.onFailure {
            AppDiagnostics.recordEvent(appContext, "legacy_migration_failed")
        }
    }

    private fun ChatSessionEntity.toDomain(
        messages: List<ChatMessageEntity>,
        attachments: List<AttachmentEntity>
    ): ChatSession {
        val byMessage = attachments.groupBy { it.messageId }
        return ChatSession(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            starred = starred,
            temporary = temporary,
            archived = archived,
            systemPrompt = systemPrompt,
            messages = messages.map { message ->
                ChatMessage(
                    id = message.id,
                    text = message.content,
                    role = runCatching { ChatMessage.Role.valueOf(message.role) }
                        .getOrDefault(ChatMessage.Role.ASSISTANT),
                    timestamp = message.timestamp,
                    attachments = byMessage[message.id].orEmpty().map { attachment ->
                        Attachment(
                            id = attachment.id,
                            name = attachment.name,
                            mimeType = attachment.mimeType,
                            localPath = attachment.localPath,
                            sizeBytes = attachment.sizeBytes,
                            kind = runCatching { Attachment.Kind.valueOf(attachment.kind) }
                                .getOrDefault(Attachment.Kind.FILE),
                            createdAt = attachment.createdAt
                        )
                    },
                    parentMessageId = message.parentMessageId,
                    metadata = ResponseMetadata(
                        model = message.model.takeIf { it.isNotBlank() },
                        provider = message.provider,
                        requestId = message.requestId,
                        latencyMs = message.latencyMs
                    ).takeIf { it.model != null || it.provider != null || it.requestId != null || it.latencyMs != null }
                )
            }
        )
    }

    private fun directoryBytes(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isDirectory) directoryBytes(it) else it.length() } ?: 0L
}
