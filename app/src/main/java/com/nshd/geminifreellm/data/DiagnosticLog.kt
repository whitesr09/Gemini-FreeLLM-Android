package com.nshd.geminifreellm.data

import android.content.Context
import android.util.AtomicFile
import com.nshd.geminifreellm.BuildConfig
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only structured classifications and app stack locations enter this store. No raw exception messages. */
data class DiagnosticEvent(val time: Long, val category: String, val provider: String, val summary: String, val details: String)

class DiagnosticLog(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "diagnostics.json"))
    private val mutable = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events = mutable.asStateFlow()

    @Synchronized fun load() {
        if (file.baseFile.length() > 256_000) { file.delete(); return }
        val old = runCatching {
            val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
            (0 until array.length()).map { index -> array.getJSONObject(index).let {
                DiagnosticEvent(it.getLong("time"), it.getString("category"), it.getString("provider"), it.getString("summary"), it.getString("details"))
            } }
        }.getOrDefault(emptyList())
        mutable.value = (mutable.value + old).distinct().filter { System.currentTimeMillis() - it.time < 7 * 86_400_000L }.take(100)
        if (mutable.value != old) save()
    }

    @Synchronized fun record(category: String, provider: Provider? = null, error: Throwable? = null) {
        val api = error as? ApiException
        val summary = when {
            category == "Crash" -> "The app stopped unexpectedly. Restart, then share this report with the developer if it repeats."
            api?.access == AccessState.QUOTA -> "Provider quota or balance exhausted. Check your provider dashboard."
            api?.statusCode != null -> FreeLlmApiClient.httpError(api.statusCode)
            category == "Storage" -> "Local data could not be saved or opened. Check free storage and restart."
            category == "Attachment" -> "An attachment could not be prepared. Check the format and size."
            else -> "The request could not finish. Check connection, endpoint and model access; then retry."
        }
        val event = DiagnosticEvent(System.currentTimeMillis(), category, provider?.label.orEmpty(), summary, safeTrace(error))
        mutable.value = (listOf(event) + mutable.value).filter { event.time - it.time < 7 * 86_400_000L }.take(100)
        save()
    }

    @Synchronized fun clear() { mutable.value = emptyList(); file.delete() }
    private fun save() {
        runCatching {
            val stream = file.startWrite()
            try {
                val json = JSONArray(mutable.value.map { JSONObject().put("time", it.time).put("category", it.category)
                    .put("provider", it.provider).put("summary", it.summary).put("details", it.details) })
                stream.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream)
            } catch (error: Exception) { file.failWrite(stream) }
        }
    }

    fun report(): String = "FreeLLM AI ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.SDK_INT}\n" +
        events.value.take(20).joinToString("\n\n") { "${java.util.Date(it.time)} · ${it.category} · ${it.provider}\n${it.summary}\n${it.details}" }

    companion object {
        internal fun safeTrace(error: Throwable?): String = if (error == null) "" else buildString {
            append(error.javaClass.simpleName.take(80))
            error.stackTrace.filter { it.className.startsWith("com.nshd.geminifreellm.") }.take(8).forEach {
                append("\n${it.className}.${it.methodName}:${it.lineNumber}")
            }
        }
    }
}
