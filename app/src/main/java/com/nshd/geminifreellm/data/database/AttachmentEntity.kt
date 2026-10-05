package com.nshd.geminifreellm.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "attachments",
    primaryKeys = ["id"],
    indices = [Index(value = ["messageId"])],
    foreignKeys = [
        ForeignKey(
            entity = ChatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class AttachmentEntity(
    val id: String,
    val messageId: Long,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val sizeBytes: Long,
    val kind: String,
    val createdAt: Long
)