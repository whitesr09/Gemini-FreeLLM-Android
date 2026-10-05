package com.nshd.geminifreellm.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object AppDiagnostics {
    private const val DIR = "diagnostics"
    private const val CRASH_FILE = "last_crash.txt"
    private const val EVENTS_FILE = "events.log"
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(appContext.filesDir, DIR).apply { mkdirs() }
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report = buildString {
                    appendLine("AI crash report")
                    appendLine("time=" + now())
                    appendLine("thread=" + thread.name)
                    appendLine("package=" + appContext.packageName)
                    appendLine()
                    append(sw.toString())
                }
                File(dir, CRASH_FILE).writeText(report)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun recordEvent(context: Context, event: String) {
        runCatching {
            val dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
            val file = File(dir, EVENTS_FILE)
            file.appendText(now() + " | " + event.take(500) + "\n")
            if (file.length() > 64 * 1024L) {
                val lines = file.readLines().takeLast(250)
                file.writeText(lines.joinToString("\n") + "\n")
            }
        }
    }

    fun hasPreviousCrash(context: Context): Boolean =
        File(context.applicationContext.filesDir, DIR, CRASH_FILE).exists()

    fun readPreviousCrash(context: Context): String? =
        runCatching {
            File(context.applicationContext.filesDir, DIR, CRASH_FILE)
                .takeIf { it.exists() }
                ?.readText()
                ?.take(30_000)
        }.getOrNull()

    fun clearPreviousCrash(context: Context) {
        runCatching { File(context.applicationContext.filesDir, DIR, CRASH_FILE).delete() }
    }

    fun selfCheck(context: Context, baseUrl: String, apiKeyPresent: Boolean): List<String> {
        val app = context.applicationContext
        val generated = File(app.filesDir, "generated").apply { mkdirs() }
        val attachments = File(app.filesDir, "attachments").apply { mkdirs() }
        val checks = mutableListOf<String>()
        checks += if (app.filesDir.exists() && app.filesDir.canWrite()) "✓ App storage writable" else "✗ App storage unavailable"
        checks += if (generated.canWrite()) "✓ Generated-media storage ready" else "✗ Generated-media storage unavailable"
        checks += if (attachments.canWrite()) "✓ Attachment storage ready" else "✗ Attachment storage unavailable"
        checks += if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) "✓ API URL valid" else "✗ API URL invalid"
        checks += if (apiKeyPresent) "✓ Unified API key configured" else "• API key not configured"
        val freeMb = app.filesDir.usableSpace / (1024L * 1024L)
        checks += if (freeMb >= 250) "✓ Free storage: " + freeMb + " MB" else "⚠ Low free storage: " + freeMb + " MB"
        checks += if (hasPreviousCrash(app)) "⚠ Previous crash report found" else "✓ No previous crash report"
        return checks
    }

    fun exportReport(context: Context, baseUrl: String, apiKeyPresent: Boolean, chatCount: Int): File {
        val app = context.applicationContext
        val report = JSONObject()
            .put("package", app.packageName)
            .put("generatedAt", now())
            .put("baseUrl", baseUrl)
            .put("apiKeyConfigured", apiKeyPresent)
            .put("chatCount", chatCount)
            .put("freeStorageBytes", app.filesDir.usableSpace)
            .put("previousCrash", readPreviousCrash(app).orEmpty())
            .put(
                "events",
                runCatching { File(app.filesDir, DIR + "/" + EVENTS_FILE).readText().takeLast(32_000) }.getOrDefault("")
            )
            .toString(2)
        val file = File(app.cacheDir, "ai-diagnostics-" + System.currentTimeMillis() + ".json")
        file.writeText(report)
        return file
    }

    fun safeRepair(context: Context) {
        val app = context.applicationContext
        File(app.cacheDir, "tmp").deleteRecursively()
        File(app.filesDir, "generated").apply { mkdirs() }.listFiles()?.forEach { file ->
            if (file.isFile && System.currentTimeMillis() - file.lastModified() > 14L * 24 * 60 * 60 * 1000) file.delete()
        }
        File(app.filesDir, "attachments").apply { mkdirs() }
        recordEvent(app, "safe_repair_completed")
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
}