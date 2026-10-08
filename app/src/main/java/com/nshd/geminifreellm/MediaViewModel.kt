package com.nshd.geminifreellm

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.util.AtomicFile
import com.nshd.geminifreellm.data.*
import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Media jobs survive navigation and retain remote IDs for explicit resume after process restart. */
class MediaViewModel(application: Application) : AndroidViewModel(application) {
    data class JobItem(val id: String = newId(), val provider: Provider, val baseUrl: String, val model: String,
        val kind: MediaKind, val status: String = "Starting", val remoteId: String = "", val localPath: String = "",
        val mime: String = "", val progress: Int = 0, val createdAt: Long = System.currentTimeMillis())
    data class State(val jobs: List<JobItem> = emptyList(), val busyId: String? = null, val notice: String? = null, val loading: Boolean = true, val storageBlocked: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val client = MediaApiClient(FreeLlmApiClient())
    private val directory = File(application.filesDir, "generated").apply { mkdirs() }
    private val index = AtomicFile(File(directory, "index.json"))
    private val diagnostics = (application as FreeLlmApplication).diagnostics
    private var job: Job? = null
    private val saveMutex = Mutex()
    init {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                if (!index.baseFile.exists()) return@runCatching emptyList()
                check(index.baseFile.length() < 256_000)
                val array = JSONArray(String(index.readFully(), Charsets.UTF_8))
                (0 until minOf(array.length(), 30)).mapNotNull { i -> array.getJSONObject(i).let {
                    if (!it.optString("id").matches(Regex("[A-Za-z0-9-]{1,100}"))) return@mapNotNull null
                    val local = it.optString("file").takeIf { name -> name.matches(Regex("[A-Za-z0-9-]+\\.(png|mp4)")) }?.let { name -> File(directory, name) }
                    JobItem(it.getString("id"), Provider.valueOf(it.getString("provider")), it.getString("baseUrl"), it.getString("model"), MediaKind.valueOf(it.getString("kind")),
                        if (local?.isFile == true) "Ready" else "Paused — resume or delete", it.optString("remoteId"), local?.takeIf(File::isFile)?.path.orEmpty(),
                        it.optString("mime"), it.optInt("progress"), it.getLong("createdAt"))
                } }
            } }
            mutable.value = result.fold(onSuccess = { jobs -> State(jobs = jobs, loading = false) },
                onFailure = { State(loading = false, storageBlocked = true, notice = "Could not open the media index. Files are preserved; restart to retry.") })
        }
    }
    fun create(profile: ProviderProfile, kind: MediaKind, model: String, prompt: String) {
        if (mutable.value.busyId != null || mutable.value.loading || mutable.value.storageBlocked) return
        if (mutable.value.jobs.size >= 30) { mutable.value = mutable.value.copy(notice = "Delete an older creation before adding another (30 maximum)."); return }
        FreeLlmApiClient.validate(profile.copy(model = model.trim()))?.let { mutable.value = mutable.value.copy(notice = it); return }
        if (prompt.isBlank() || prompt.length > 8000) { mutable.value = mutable.value.copy(notice = "Enter a prompt of 1–8,000 characters."); return }
        val item = JobItem(provider = profile.provider, baseUrl = FreeLlmApiClient.normalizeBaseUrl(profile.baseUrl, profile.provider.protocol), model = model.trim(), kind = kind)
        mutable.value = mutable.value.copy(jobs = listOf(item) + mutable.value.jobs)
        run(item, profile, prompt)
    }
    fun resume(item: JobItem, settings: AppSettings) {
        if (item.remoteId.isBlank() || mutable.value.busyId != null) return
        val profile = settings.profiles.firstOrNull { it.provider == item.provider }
        if (profile == null || FreeLlmApiClient.normalizeBaseUrl(profile.baseUrl, profile.provider.protocol) != item.baseUrl) {
            mutable.value = mutable.value.copy(notice = "Restore this job's original provider connection in Settings before resuming."); return
        }
        run(item, profile, null)
    }
    private fun run(item: JobItem, profile: ProviderProfile, prompt: String?) {
        mutable.value = mutable.value.copy(busyId = item.id, notice = null)
        job = viewModelScope.launch {
            val destination = File(directory, item.id + if (item.kind == MediaKind.IMAGE) ".png" else ".mp4")
            try {
                save()
                if (item.kind == MediaKind.IMAGE) {
                    update(item.id) { it.copy(status = "Creating image") }
                    client.image(profile, item.model, prompt.orEmpty(), destination)
                    val mime = withContext(Dispatchers.IO) {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(destination.path, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ApiException("The provider returned an unreadable image.")
                        bounds.outMimeType?.takeIf { it in listOf("image/png", "image/jpeg", "image/webp") }
                            ?: throw ApiException("The provider returned an unsupported image format.")
                    }
                    update(item.id) { it.copy(status = "Ready", localPath = destination.path, mime = mime, progress = 100) }
                } else {
                    var remote = if (item.remoteId.isNotBlank()) client.video(profile, item.remoteId) else client.createVideo(profile, item.model, prompt.orEmpty())
                    update(item.id) { it.copy(remoteId = remote.id, status = remote.status, progress = remote.progress) }
                    save() // Persist the remote ID before polling so rotation/process death cannot lose it.
                    withTimeout(15 * 60 * 1000L) {
                        while (remote.status !in listOf("completed", "failed")) {
                            delay(5000)
                            remote = client.video(profile, remote.id)
                            update(item.id) { it.copy(status = remote.status, progress = remote.progress) }
                        }
                        if (remote.status == "failed") throw ApiException("The provider could not create this video. Check its media model, policy and account access.")
                        update(item.id) { it.copy(status = "Downloading video") }
                        client.downloadVideo(profile, remote.id, destination)
                        update(item.id) { it.copy(status = "Ready", localPath = destination.path, mime = "video/mp4", progress = 100) }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                update(item.id) { it.copy(status = "Paused — tap resume to check again") }
            } catch (cancelled: CancellationException) {
                if (mutable.value.jobs.firstOrNull { it.id == item.id }?.localPath.isNullOrBlank())
                    withContext(NonCancellable + Dispatchers.IO) { destination.delete() }
                throw cancelled
            }
            catch (error: Exception) {
                destination.delete()
                update(item.id) { it.copy(status = "Failed") }
                mutable.value = mutable.value.copy(notice = (error as? ApiException)?.message ?: "Media creation failed. Check the model and endpoint.")
                withContext(Dispatchers.IO) { diagnostics.record("Media request", profile.provider, error) }
            } finally {
                if (mutable.value.busyId == item.id) mutable.value = mutable.value.copy(busyId = null)
                withContext(NonCancellable) { save() }
            }
        }
    }
    fun stop() {
        val id = mutable.value.busyId ?: return
        job?.cancel()
        update(id) { it.copy(status = "Paused — remote work may continue") }
        // Busy clears only after cancellation/final persistence; a second request cannot race this job.
    }
    fun delete(item: JobItem) {
        if (mutable.value.busyId != null) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { if (item.localPath.isNotEmpty()) File(item.localPath).delete() }
            mutable.value = mutable.value.copy(jobs = mutable.value.jobs.filterNot { it.id == item.id })
            save()
        }
    }
    private fun update(id: String, transform: (JobItem) -> JobItem) { mutable.value = mutable.value.copy(jobs = mutable.value.jobs.map { if (it.id == id) transform(it) else it }) }
    private suspend fun save() = saveMutex.withLock {
        val snapshot = mutable.value.jobs
        val success = withContext(Dispatchers.IO) { runCatching {
            val array = JSONArray(snapshot.map { JSONObject().put("id", it.id).put("provider", it.provider.name).put("baseUrl", it.baseUrl).put("model", it.model)
                .put("kind", it.kind.name).put("remoteId", it.remoteId).put("file", if (it.localPath.isBlank()) "" else File(it.localPath).name)
                .put("mime", it.mime).put("progress", it.progress).put("createdAt", it.createdAt) })
            val stream = index.startWrite()
            try { stream.write(array.toString().toByteArray()); index.finishWrite(stream) } catch (e: Exception) { index.failWrite(stream); throw e }
        }.isSuccess }
        if (!success) mutable.value = mutable.value.copy(notice = "Could not save the media job. Check device storage before leaving this screen.")
    }
}
