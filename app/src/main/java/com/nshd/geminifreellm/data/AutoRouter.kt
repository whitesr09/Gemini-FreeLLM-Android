package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Heuristic ranking, not a claim that catalog membership proves account entitlement. */
object AutoRouter {
    fun models(settings: AppSettings, catalogs: Map<Provider, ModelCatalog>): List<ProviderProfile> =
        settings.profiles.flatMap { profile ->
            (catalogs[profile.provider]?.models.orEmpty().map { it.id } + profile.model)
                .filter { it.isNotBlank() }.distinct().map { profile.copy(model = it) }
        }

    fun isTextModel(model: String) = !Regex("embedding|whisper|tts|dall-e|imagen|veo|moderation|rerank", RegexOption.IGNORE_CASE).containsMatchIn(model)

    fun candidates(settings: AppSettings, catalogs: Map<Provider, ModelCatalog>, access: Map<String, ModelAccess>,
                   history: List<ChatMessage>, now: Long = System.currentTimeMillis()): List<ProviderProfile> {
        val images = history.any { m -> m.attachments.any { it.isImage } }
        val coding = Regex("\\b(code|coding|debug|function|refactor|program|kotlin|python|javascript)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(history.lastOrNull { it.role == ChatMessage.Role.USER }?.text.orEmpty())
        val ranked = models(settings, catalogs).filter { profile ->
            profile.provider !in settings.autoExcluded && (!images || (profile.vision && profile.model == settings.profiles.firstOrNull { it.provider == profile.provider }?.model)) && access.none { (key, value) ->
                key.startsWith("${profile.provider.name}/") && value.state in listOf(AccessState.AUTH, AccessState.QUOTA) && now - value.checkedAt < cooldown(value.state)
            }
            }.filter { profile ->
                FreeLlmApiClient.validate(profile) == null &&
                    isTextModel(profile.model) &&
                    access[modelKey(profile.provider, profile.model)]?.let { it.state in listOf(AccessState.UNKNOWN, AccessState.USABLE) || now - it.checkedAt > cooldown(it.state) } != false
            }.sortedByDescending { profile ->
                val key = modelKey(profile.provider, profile.model)
                val model = profile.model.lowercase()
                (if (access[key]?.state == AccessState.USABLE) 100 else 0) +
                    (if (key in settings.favorites) 45 else 0) +
                    (if (profile.provider == settings.selected && profile.model == settings.active.model) 20 else 0) +
                    (if (coding && listOf("code", "sonnet", "deepseek").any { it in model }) 30 else 0) +
                    (if (!coding && listOf("flash", "mini", "small", "instant").any { it in model }) 15 else 0) -
                    ((access[key]?.latencyMs ?: 0) / 250).coerceAtMost(30).toInt()
            }
        // Try another provider before another model behind the same failing connection.
        val first = ranked.distinctBy { it.provider }
        return first + ranked.filter { it !in first }
    }
    fun cooldown(state: AccessState) = when (state) {
        AccessState.AUTH, AccessState.QUOTA -> 15 * 60_000L
        AccessState.RATE_LIMITED -> 60_000L
        else -> 30_000L
    }

    /** Outcome of one parallel availability probe. */
    data class ProbeResult(val profile: ProviderProfile, val state: AccessState, val latencyMs: Long, val error: Exception? = null)

    /**
     * Probes candidates concurrently. A probe that times out marks the model USABLE
     * with a high latency (slow but alive) instead of failed — a late first token is
     * not a broken model. Only hard errors (auth, quota, rejection, rate-limit,
     * network) mark it down.
     */
    suspend fun probe(
        candidates: List<ProviderProfile>,
        probeTimeoutMs: Long = 20_000,
        onProbe: (ProviderProfile) -> Unit = {},
        request: suspend (ProviderProfile, (String) -> Unit) -> String,
    ): List<ProbeResult> = coroutineScope {
        candidates.map { profile ->
            async {
                onProbe(profile)
                val started = System.currentTimeMillis()
                try {
                    val result = withTimeoutOrNull(probeTimeoutMs) { request(profile) {} }
                        ?: return@async ProbeResult(profile, AccessState.USABLE, probeTimeoutMs)
                    if (result.isBlank()) throw ApiException("The model returned no text.")
                    ProbeResult(profile, AccessState.USABLE, (System.currentTimeMillis() - started).coerceAtLeast(1))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    ProbeResult(profile, (e as? ApiException)?.access ?: AccessState.ERROR, 0, e)
                }
            }
        }.awaitAll()
    }

    private class StallException : RuntimeException("Stalled")
    private data class RacerEnd(val text: String?, val error: Exception?)

    private sealed interface AttemptOutcome {
        data class Won(val profile: ProviderProfile, val text: String, val slowLoser: ProviderProfile?, val loserLatencyMs: Long) : AttemptOutcome
        data class Failed(val errors: List<Pair<ProviderProfile, Exception>>) : AttemptOutcome
        data class Partial(val profile: ProviderProfile, val error: Exception, val partial: String) : AttemptOutcome
        data class Slow(val profiles: List<ProviderProfile>) : AttemptOutcome
    }

    /**
     * Hedged failover across ranked candidates.
     *
     * - The primary racer gets [hedgeDelayMs] to produce a first token; if it is
     *   quiet, a backup candidate starts racing it and the first token wins. A slow
     *   model is never marked broken — the loser is recorded as slow (deprioritized
     *   but still eligible) via [onSlow].
     * - Hard errors (auth, quota, rejection, rate-limit, network, 404) fail over
     *   to the next candidate immediately, with no delay. AUTH/QUOTA also blocks
     *   the rest of that provider for this call.
     * - A winner that stalls mid-stream ([stallTimeoutMs] without data) or errors
     *   after partial output reports [onPartialFailure] so the caller can continue
     *   the reply seamlessly.
     */
    suspend fun generate(
        candidates: List<ProviderProfile>,
        firstTokenTimeoutMs: Long = 45_000,
        hedgeDelayMs: Long = 6_000,
        stallTimeoutMs: Long = 45_000,
        maxAttempts: Int = 6,
        onAttempt: (ProviderProfile, Int) -> Unit,
        onFailure: suspend (ProviderProfile, Exception) -> Unit,
        onSlow: suspend (ProviderProfile, Long) -> Unit = { _, _ -> },
        request: suspend (ProviderProfile, (String) -> Unit) -> String,
        onText: (String) -> Unit,
        onPartialFailure: (suspend (String) -> Unit)? = null,
    ): Pair<ProviderProfile, String> {
        var last: Exception = ApiException("No eligible models. Configure a provider, enable it for Auto, or wait for its cooldown.")
        val blockedProviders = mutableSetOf<Provider>()
        var attempts = 0
        var slowRounds = 0
        var index = 0
        fun nextIndex(from: Int) = (from until candidates.size).firstOrNull { candidates[it].provider !in blockedProviders } ?: -1
        while (attempts < maxAttempts) {
            val primaryIdx = nextIndex(index)
            if (primaryIdx < 0) break
            val backupIdx = nextIndex(primaryIdx + 1)
            val racers = listOf(candidates[primaryIdx]) + (if (backupIdx >= 0) listOf(candidates[backupIdx]) else emptyList())
            onAttempt(candidates[primaryIdx], ++attempts)
            when (val outcome = attempt(racers, firstTokenTimeoutMs, hedgeDelayMs, stallTimeoutMs, blockedProviders, onText, request)) {
                is AttemptOutcome.Won -> {
                    if (outcome.slowLoser != null) onSlow(outcome.slowLoser, outcome.loserLatencyMs)
                    return outcome.profile to outcome.text
                }
                is AttemptOutcome.Failed -> {
                    outcome.errors.forEach { (profile, error) -> onFailure(profile, error) }
                    index = (outcome.errors.map { candidates.indexOf(it.first) }.maxOrNull() ?: primaryIdx) + 1
                    last = outcome.errors.first().second
                }
                is AttemptOutcome.Partial -> {
                    onFailure(outcome.profile, outcome.error)
                    last = outcome.error
                    if (outcome.partial.isNotEmpty() && onPartialFailure != null) {
                        onPartialFailure(outcome.partial)
                        index = candidates.indexOf(outcome.profile) + 1
                    } else throw outcome.error
                }
                is AttemptOutcome.Slow -> {
                    outcome.profiles.forEach { onSlow(it, firstTokenTimeoutMs) }
                    if (++slowRounds >= 2) throw ApiException(
                        "The available models are responding too slowly right now. Try again in a moment.", AccessState.UNAVAILABLE)
                    index = candidates.indexOf(outcome.profiles.last()) + 1
                    last = ApiException("No model replied within ${maxOf(1, firstTokenTimeoutMs / 1000)} seconds.", AccessState.UNAVAILABLE)
                    delay(500)
                }
            }
        }
        throw ApiException("Auto could not complete a reply after $attempts attempts. ${last.message}", (last as? ApiException)?.access ?: AccessState.ERROR)
    }

    private suspend fun attempt(
        profiles: List<ProviderProfile>,
        firstTokenTimeoutMs: Long,
        hedgeDelayMs: Long,
        stallTimeoutMs: Long,
        blockedProviders: MutableSet<Provider>,
        onWinnerChunk: (String) -> Unit,
        request: suspend (ProviderProfile, (String) -> Unit) -> String,
    ): AttemptOutcome = supervisorScope {
        val partials = ConcurrentHashMap<ProviderProfile, String>()
        val winner = AtomicReference<ProviderProfile?>(null)
        val lastChunkAt = AtomicLong(System.currentTimeMillis())
        val attemptStart = System.currentTimeMillis()

        fun startRacer(profile: ProviderProfile): Deferred<RacerEnd> = async {
            var emitted = false
            try {
                val text = request(profile) { chunk ->
                    if (chunk.isNotEmpty()) {
                        emitted = true
                        partials[profile] = chunk
                        lastChunkAt.set(System.currentTimeMillis())
                        winner.compareAndSet(null, profile)
                        if (winner.get() == profile) onWinnerChunk(chunk)
                    }
                }
                if (!emitted && text.isNotBlank()) {
                    // Non-streaming-style request that returned without emitting.
                    partials[profile] = text
                    if (winner.compareAndSet(null, profile)) onWinnerChunk(text)
                }
                RacerEnd(text, null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { RacerEnd(null, e) }
        }

        val racers = LinkedHashMap<ProviderProfile, Deferred<RacerEnd>>()
        racers[profiles[0]] = startRacer(profiles[0])
        val errors = mutableListOf<Pair<ProviderProfile, Exception>>()
        var hedgeStarted = profiles.size < 2
        val effectiveHedgeDelay = if (hedgeDelayMs < firstTokenTimeoutMs) hedgeDelayMs else 0

        fun dropRacer(profile: ProviderProfile, error: Exception) {
            racers.remove(profile)?.cancel()
            errors += profile to error
            if ((error as? ApiException)?.access in listOf(AccessState.AUTH, AccessState.QUOTA)) {
                blockedProviders += profile.provider
                racers.keys.filter { it.provider == profile.provider }.toList().forEach { dropRacer(it, error) }
            }
        }

        try {
            while (true) {
                // Reap finished racers; the winner (if any) is handled by the streaming phase.
                for ((profile, job) in racers.toList()) {
                    if (profile == winner.get() || !job.isCompleted) continue
                    val end = try { job.await() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { RacerEnd(null, e) }
                    if (end.error != null) dropRacer(profile, end.error)
                    else if (end.text.isNullOrBlank()) dropRacer(profile, ApiException("The model returned no usable reply.", AccessState.ERROR))
                    else racers.remove(profile)?.cancel() // clean loser that finished without racing
                }
                if (racers.isEmpty()) {
                    return@supervisorScope if (errors.isNotEmpty()) AttemptOutcome.Failed(errors)
                    else AttemptOutcome.Slow(profiles)
                }
                val w = winner.get()
                if (w != null && w in racers) break
                val now = System.currentTimeMillis()
                if (!hedgeStarted && now - attemptStart >= effectiveHedgeDelay) {
                    hedgeStarted = true
                    val hedge = profiles.getOrNull(1)
                    if (hedge != null && hedge.provider !in blockedProviders && hedge !in racers) {
                        racers[hedge] = startRacer(hedge)
                    }
                }
                if (now - attemptStart >= firstTokenTimeoutMs) {
                    val slow = racers.keys.toList()
                    racers.values.forEach { it.cancel() }
                    return@supervisorScope AttemptOutcome.Slow(slow)
                }
                delay(50)
            }

            // A first token arrived: stream the winner, cancel the loser.
            val w = winner.get()!!
            racers.filterKeys { it != w }.values.forEach { it.cancel() }
            val job = racers[w]!!
            lastChunkAt.set(System.currentTimeMillis())
            val watchdog = async {
                while (true) {
                    delay(minOf(5_000L, stallTimeoutMs / 2))
                    if (System.currentTimeMillis() - lastChunkAt.get() > stallTimeoutMs) throw StallException()
                }
            }
            try {
                val end = select<RacerEnd> {
                    job.onAwait { it }
                    watchdog.onAwait { throw StallException() }
                }
                if (end.error != null) AttemptOutcome.Partial(w, end.error, partials[w].orEmpty())
                else {
                    val loser = if (hedgeStarted && profiles.size > 1) profiles.firstOrNull { it != w } else null
                    AttemptOutcome.Won(w, end.text.orEmpty(), loser, System.currentTimeMillis() - attemptStart)
                }
            } catch (e: StallException) {
                job.cancel()
                AttemptOutcome.Partial(w, ApiException("The reply stalled mid-stream. Continuing with another model.", AccessState.ERROR),
                    partials[w].orEmpty())
            } finally {
                watchdog.cancel()
            }
        } finally {
            racers.values.forEach { it.cancel() }
        }
    }
}
