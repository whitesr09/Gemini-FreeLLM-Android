package com.nshd.geminifreellm.data.database

import android.content.Context
import androidx.room.withTransaction
import com.nshd.geminifreellm.model.Attachment
import com.nshd.geminifreellm.model.ChatMessage
import com.nshd.geminifreellm.model.ChatSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File

data class CleanupResult(val deletedFiles: Int, val reclaimedBytes: Long)

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
        val sessions = dao.getSessions()
        return sessions.map { entity ->
            entity.toDomain(dao.getMessages(entity.id), dao.getAttachments(entity.id))
        }
    }

    suspend fun saveSession(session: ChatSession, model: String = "auto") {
        database.withTransaction {
            dao.upsertSession(
                ChatSessionEntity(
                    id = session.id,
                    title = session.title,
                    createdAt = session.createdAt,
                    updatedAt = session.updatedAt,
                    starred = session.starred,
                    model = model
                )
            )
            dao.deleteAttachmentsForSession(session.id)
            dao.deleteMessagesForSession(session.id)
            val messages = session.messages.map { message ->
                ChatMessageEntity(
                    id = message.id,
                    sessionId = session.id,
                    role = message.role.name,
                    content = message.text,
                    timestamp = message.timestamp,
                    status = if (message.role == ChatMessage.Role.ERROR) "error" else "complete",
                    model = model,
                    parentMessageId = message.parentMessageId
                )
            }
            dao.upsertMessages(messages)
            dao.upsertAttachments(
                session.messages.flatMap { message ->
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
                }
            )
        }
    }

    suspend fun deleteSessions(ids: Set<String>) {
        if (ids.isNotEmpty()) dao.deleteSessions(ids.toList())
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    suspend fun cleanupOrphans(): CleanupResult {
        val referenced = dao.getAllAttachmentPaths().map { File(it).canonicalPath }.toHashSet()
        val roots = listOf(
            File(appContext.filesDir, "attachments"),
            File(appContext.filesDir, "generated")
        )
        var deleted = 0
        var bytes = 0L
        roots.forEach { root ->
            root.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val canonical = runCatching { file.canonicalPath }.getOrNull()
                    if (canonical != null && canonical !in referenced) {
                        val size = file.length()
                        if (file.delete()) {
                            deleted++
                            bytes += size
                        }
                    }
                }
            }
        }
        return CleanupResult(deleted, bytes)
    }

    fun currentId(): String? = metaPrefs.getString("current_id", null)

    fun saveCurrentId(id: String) {
        metaPrefs.edit().putString("current_id", id).apply()
    }

    fun newSession(): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(UUID.randomUUID().toString(), "New chat", now, now)
    }

    fun exportJson(sessions: List<ChatSession>): String {
        val root = JSONObject()
            .put("version", 3)
            .put("exportedAt", System.currentTimeMillis())
        root.put("sessions", JSONArray().apply {
            sessions.forEach { put(it.toJson()) }
        })
        return root.toString(2)
    }

    fun importJson(raw: String): List<ChatSession> {
        val root = JSONObject(raw)
        val version = root.optInt("version", 1)
        require(version in 1..3) { "Unsupported backup version: $version" }
        val array = root.optJSONArray("sessions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val session = ChatSession.fromJson(obj)
                if (session.id.isNotBlank()) add(session)
            }
        }
    }

    suspend fun importSessions(sessions: List<ChatSession>, model: String = "auto") {
        sessions.forEach { saveSession(it, model) }
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
                    val session = ChatSession.fromJson(obj)
                    if (session.id.isNotBlank()) add(session)
                }
            }
        }.getOrElse { return }

        runCatching {
            database.withTransaction {
                migrated.forEach { session ->
                    dao.upsertSession(
                        ChatSessionEntity(
                            id = session.id,
                            title = session.title,
                            createdAt = session.createdAt,
                            updatedAt = session.updatedAt,
                            starred = session.starred
                        )
                    )
                    val messages = session.messages.map { message ->
                        ChatMessageEntity(
                            id = message.id,
                            sessionId = session.id,
                            role = message.role.name,
                            content = message.text,
                            timestamp = message.timestamp,
                            status = if (message.role == ChatMessage.Role.ERROR) "error" else "complete",
                            parentMessageId = message.parentMessageId
                        )
                    }
                    dao.upsertMessages(messages)
                    dao.upsertAttachments(
                        session.messages.flatMap { message ->
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
                        }
                    )
                }
            }
            legacyPrefs.getString("current_id", null)?.let { saveCurrentId(it) }
            metaPrefs.edit().putBoolean("legacy_migration_complete", true).apply()
            legacyPrefs.edit().remove("sessions").remove("current_id").apply()
        }
    }

    private suspend fun ChatSessionEntity.toDomain(
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
                    parentMessageId = message.parentMessageId
                )
            }
        )
    }
}