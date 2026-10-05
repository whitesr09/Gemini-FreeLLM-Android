package com.nshd.geminifreellm.data.database

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "document_chunks",
    primaryKeys = ["id"],
    indices = [Index(value = ["sourcePath"]), Index(value = ["sourceChecksum"])]
)
data class DocumentChunkEntity(
    val id: String,
    val sourcePath: String,
    val sourceChecksum: String,
    val chunkIndex: Int,
    val content: String,
    val embedding: String,
    val createdAt: Long
)
