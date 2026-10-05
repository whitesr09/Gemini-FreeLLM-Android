package com.nshd.geminifreellm.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY archived ASC, starred DESC, updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions ORDER BY archived ASC, starred DESC, updatedAt DESC")
    suspend fun getSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM attachments WHERE messageId IN (SELECT id FROM chat_messages WHERE sessionId = :sessionId) ORDER BY createdAt ASC")
    suspend fun getAttachments(sessionId: String): List<AttachmentEntity>

    @Query("SELECT COUNT(*) FROM chat_sessions")
    suspend fun sessionCount(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes),0) FROM attachments")
    suspend fun attachmentBytes(): Long

    @Query("SELECT * FROM attachments")
    suspend fun getAllAttachments(): List<AttachmentEntity>

    @Query("SELECT localPath FROM attachments")
    suspend fun getAllAttachmentPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<ChatMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM chat_sessions WHERE id IN (:sessionIds)")
    suspend fun deleteSessions(sessionIds: List<String>)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM attachments WHERE messageId IN (SELECT id FROM chat_messages WHERE sessionId = :sessionId)")
    suspend fun deleteAttachmentsForSession(sessionId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun clearAll()

    @Query("SELECT * FROM document_chunks")
    suspend fun getAllDocumentChunks(): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE sourcePath = :path")
    suspend fun getDocumentChunksByPath(path: String): List<DocumentChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocumentChunks(chunks: List<DocumentChunkEntity>)

    @Query("DELETE FROM document_chunks WHERE sourcePath = :path")
    suspend fun deleteDocumentChunksByPath(path: String)

    @Query("DELETE FROM document_chunks")
    suspend fun clearDocumentChunks()

    @Query("SELECT COUNT(*) FROM document_chunks")
    suspend fun documentChunkCount(): Long

    @Query("SELECT COALESCE(SUM(LENGTH(content) + LENGTH(embedding)),0) FROM document_chunks")
    suspend fun documentChunksBytes(): Long
}
