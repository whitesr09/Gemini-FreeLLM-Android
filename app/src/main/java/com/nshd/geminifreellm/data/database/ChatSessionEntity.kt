package com.nshd.geminifreellm.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_sessions",
    indices = [
        androidx.room.Index(value = ["updatedAt"]),
        androidx.room.Index(value = ["starred"])
    ]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val starred: Boolean,
    val model: String = "auto",
    val systemPrompt: String? = null,
    val temporary: Boolean = false,
    val archived: Boolean = false
)