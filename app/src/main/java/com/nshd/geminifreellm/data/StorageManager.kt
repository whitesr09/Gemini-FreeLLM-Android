package com.nshd.geminifreellm.data

import android.content.Context
import java.io.File

data class StorageUsage(
    val attachments: Long,
    val generatedImages: Long,
    val generatedVideos: Long,
    val cache: Long,
    val temp: Long,
    val total: Long
)

object StorageManager {
    fun usage(context: Context): StorageUsage {
        val app = context.applicationContext
        val attachments = sizeOf(File(app.filesDir, "attachments"))
        val generated = File(app.filesDir, "generated")
        val images = generated.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }?.sumOf { it.length() } ?: 0L
        val videos = generated.listFiles()?.filter { it.isFile && it.extension.lowercase() in setOf("mp4", "webm", "mov") }?.sumOf { it.length() } ?: 0L
        val cache = sizeOf(app.cacheDir)
        val temp = sizeOf(File(app.cacheDir, "tmp")) + sizeOf(File(app.cacheDir, "rendered_pages"))
        return StorageUsage(attachments, images, videos, cache, temp, attachments + images + videos + cache)
    }

    fun clearCache(context: Context) {
        context.applicationContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun sizeOf(file: File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles()?.sumOf(::sizeOf) ?: 0L
        else -> 0L
    }
}
