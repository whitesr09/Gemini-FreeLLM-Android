package com.nshd.geminifreellm.data

import android.content.Context
import androidx.room.withTransaction
import com.nshd.geminifreellm.data.database.AppDatabase
import com.nshd.geminifreellm.data.database.DocumentChunkEntity
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class RetrievedChunk(
    val sourcePath: String,
    val content: String,
    val score: Float
)

class DocumentIndexRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.get(appContext)
    private val dao = database.chatDao()

    suspend fun indexFile(file: File, apiClient: FreeLlmApiClient, baseUrl: String, apiKey: String, embeddingModel: String): Result<Int> {
        return runCatching {
            require(file.isFile) { "Document does not exist." }
            val text = DocumentProcessor.extractText(appContext, file, "application/octet-stream", file.name)
                ?.take(300_000)
                ?.trim()
                .orEmpty()
            require(text.isNotBlank()) { "No indexable text was extracted from this document." }
            val checksum = sha256(file)
            val existing = dao.getDocumentChunksByPath(file.absolutePath)
            if (existing.isNotEmpty() && existing.first().sourceChecksum == checksum) return@runCatching existing.size

            val chunks = chunk(text)
            val entities = chunks.mapIndexed { index, chunk ->
                val vector = apiClient.createEmbedding(baseUrl, apiKey, embeddingModel, chunk).getOrThrow()
                DocumentChunkEntity(
                    id = UUID.randomUUID().toString(),
                    sourcePath = file.absolutePath,
                    sourceChecksum = checksum,
                    chunkIndex = index,
                    content = chunk,
                    embedding = vector.joinToString(","),
                    createdAt = System.currentTimeMillis()
                )
            }

            database.withTransaction {
                dao.deleteDocumentChunksByPath(file.absolutePath)
                dao.upsertDocumentChunks(entities)
            }
            entities.size
        }
    }

    suspend fun search(queryVector: List<Float>, limit: Int = 6): List<RetrievedChunk> {
        val rows = dao.getAllDocumentChunks()
        return VectorMath.topK(
            queryVector,
            rows.map { it.id to parseVector(it.embedding) },
            limit
        ).mapNotNull { (id, score) ->
            rows.firstOrNull { it.id == id }?.let { RetrievedChunk(it.sourcePath, it.content, score) }
        }
    }

    suspend fun clear() = dao.clearDocumentChunks()

    suspend fun sizeBytes(): Long = dao.documentChunksBytes()

    suspend fun chunkCount(): Long = dao.documentChunkCount()

    private fun parseVector(value: String): List<Float> =
        value.split(',').mapNotNull { it.toFloatOrNull() }

    private fun chunk(text: String): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n"))
        val chunks = mutableListOf<String>()
        val buffer = StringBuilder()
        for (paragraph in paragraphs) {
            val normalized = paragraph.trim()
            if (normalized.isBlank()) continue
            if (buffer.length + normalized.length + 2 > 2_800 && buffer.isNotEmpty()) {
                chunks += buffer.toString()
                buffer.setLength(0)
            }
            if (normalized.length <= 3_500) {
                if (buffer.isNotEmpty()) buffer.append("\n\n")
                buffer.append(normalized)
            } else {
                var start = 0
                while (start < normalized.length) {
                    val end = (start + 3_000).coerceAtMost(normalized.length)
                    chunks += normalized.substring(start, end)
                    start = end
                }
            }
        }
        if (buffer.isNotEmpty()) chunks += buffer.toString()
        return chunks.take(128)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
