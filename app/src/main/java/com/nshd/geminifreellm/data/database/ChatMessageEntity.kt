package com.nshd.geminifreellm.data.database

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["id"],
    indices = [
        Index(value = ["sessionId", "timestamp"]),
        Index(value = ["sessionId"])
    ],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class ChatMessageEntity(
    val id: Long,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val status: String = "complete",
    val model: String = "auto",
    val provider: String? = null,
    val latencyMs: Long? = null,
    val requestId: String? = null,
    val errorCode: String? = null,
    val parentMessageId: Long? = null
)