package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean

/** Heuristic ranking, not a claim that catalog membership proves account entitlement. */
object AutoRouter {
    fun candidates(settings: AppSettings, catalogs: Map<Provider, ModelCatalog>, access: Map<String, ModelAccess>,
                   history: List<ChatMessage>, now: Long = System.currentTimeMillis()): List<ProviderProfile> {
        val images = history.any { m -> m.attachments.any { it.isImage } }
        val coding = Regex("\\b(code|coding|debug|function|refactor|program|kotlin|python|javascript)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(history.lastOrNull { it.role == ChatMessage.Role.USER }?.text.orEmpty())
        val ranked = settings.profiles.filter { it.provider !in settings.autoExcluded && (!images || it.vision) && access.none { (key, value) ->
                key.startsWith("${it.provider.name}/") && value.state in listOf(AccessState.AUTH, AccessState.QUOTA, AccessState.RATE_LIMITED) && now - value.checkedAt < cooldown(value.state)
            } }
            .flatMap { profile ->
                (listOf(profile.model) + if (images) emptyList() else catalogs[profile.provider]?.models.orEmpty().map { it.id }).distinct()
                    .map { profile.copy(model = it) }
            }.filter { profile ->
                FreeLlmApiClient.validate(profile) == null &&
                    !Regex("embedding|whisper|tts|dall-e|imagen|veo|moderation|rerank", RegexOption.IGNORE_CASE).containsMatchIn(profile.model) &&
                    access[modelKey(profile.provider, profile.model)]?.let { it.state in listOf(AccessState.UNKNOWN, AccessState.USABLE) || now - it.checkedAt > cooldown(it.state) } != false
            }.sortedByDescending { profile ->
                val key = modelKey(profile.provider, profile.model)
                val model = profile.model.lowercase()
                (if (access[key]?.state == AccessState.USABLE) 100 else 0) +
                    (if (key in settings.favorites) 45 else 0) +
                    (if (profile.provider == settings.selected && profile.model == settings.active.model) 20 else 0) +
                    (if (coding && listOf("code", "sonnet", "deepseek").any { it in model }) 30 else 0) +
                    (if (!coding && listOf("flash", "mini", "small", "instant").any { it in model }) 15 else 0)
            }
        // Try another provider before another model behind the same failing connection.
        val first = ranked.distinctBy { it.provider }
        return first + ranked.filter { it !in first }
    }
    private fun cooldown(state: AccessState) = when (state) {
        AccessState.AUTH, AccessState.QUOTA -> 15 * 60_000L
        AccessState.RATE_LIMITED -> 60_000L
        else -> 30_000L
    }

    suspend fun generate(candidates: List<ProviderProfile>, firstTokenTimeoutMs: Long = 25_000,
                         onAttempt: (ProviderProfile, Int) -> Unit,
                         onFailure: suspend (ProviderProfile, Exception) -> Unit,
                         request: suspend (ProviderProfile, (String) -> Unit) -> String,
                         onText: (String) -> Unit): Pair<ProviderProfile, String> {
        var last: Exception = ApiException("No eligible models. Configure a provider, enable it for Auto, or wait for its cooldown.")
        val blockedProviders = mutableSetOf<Provider>()
        var attempts = 0
        for (profile in candidates) {
            if (profile.provider in blockedProviders) continue
            if (attempts >= 6) break
            if (attempts > 0) delay((attempts * 400L).coerceAtMost(2000))
            onAttempt(profile, ++attempts)
            val started = AtomicBoolean(false)
            try {
                val text = supervisorScope {
                    val first = CompletableDeferred<Unit>()
                    val result = async { request(profile) { partial ->
                        if (partial.isNotEmpty()) { started.set(true); first.complete(Unit) }
                        onText(partial)
                    } }
                    try {
                        val ready = withTimeoutOrNull(firstTokenTimeoutMs) {
                            select<Boolean> { first.onAwait { true }; result.onAwait { true } }
                        }
                        if (ready == null) throw ApiException("No reply within 25 seconds; trying another model.", AccessState.UNAVAILABLE)
                        result.await()
                    } finally { result.cancel() }
                }
                return profile to text
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                onFailure(profile, error)
                if (started.get()) throw error // Preserve a partial reply; never splice two models' answers.
                if ((error as? ApiException)?.access in listOf(AccessState.AUTH, AccessState.QUOTA, AccessState.RATE_LIMITED)) blockedProviders += profile.provider
                last = error
            }
        }
        throw ApiException("Auto could not complete a reply after $attempts attempts. ${last.message}", (last as? ApiException)?.access ?: AccessState.ERROR)
    }
}
