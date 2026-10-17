package com.nshd.geminifreellm.data

import com.nshd.geminifreellm.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Hedged-failover behavior. Uses runBlocking with real time because the hedge
 * and first-token timeouts are wall-clock driven (runTest's virtual clock
 * would never advance them).
 */
class AutoRouterHedgeTest {
    private val a = ProviderProfile(Provider.OPENAI, apiKey = "synthetic", model = "gpt-mini")
    private val b = ProviderProfile(Provider.GEMINI, apiKey = "synthetic", model = "gemini-flash")

    @Test fun hedgeWinsForSlowPrimaryAndLoserIsMarkedSlowNotFailed() = runBlocking {
        val slowMarked = mutableListOf<ProviderProfile>()
        val failed = mutableListOf<ProviderProfile>()
        val result = AutoRouter.generate(listOf(a, b), hedgeDelayMs = 200, firstTokenTimeoutMs = 5_000,
            onAttempt = { _, _ -> }, onFailure = { p, _ -> failed += p }, onSlow = { p, _ -> slowMarked += p },
            request = { p, emit ->
                if (p == a) { delay(900); emit("slow-token"); "slow-done" }
                else { emit("fast-token"); "fast-done" }
            }, onText = {})
        assertEquals(b, result.first)
        assertEquals("fast-done", result.second)
        assertEquals(listOf(a), slowMarked)
        assertTrue(failed.isEmpty())
    }

    @Test fun allSlowMarksSlowInsteadOfFailedAndStaysBounded() = runBlocking {
        val c = ProviderProfile(Provider.GROQ, apiKey = "s", model = "m1")
        val d = ProviderProfile(Provider.DEEPSEEK, apiKey = "s", model = "m2")
        val slowMarked = mutableListOf<ProviderProfile>()
        val failed = mutableListOf<ProviderProfile>()
        try {
            AutoRouter.generate(listOf(a, b, c, d), hedgeDelayMs = 100, firstTokenTimeoutMs = 400,
                onAttempt = { _, _ -> }, onFailure = { p, _ -> failed += p }, onSlow = { p, _ -> slowMarked += p },
                request = { _, _ -> awaitCancellation() }, onText = {})
            fail("Expected slow-models error")
        } catch (error: ApiException) {
            assertTrue(error.message!!.contains("too slowly"))
        }
        assertEquals(4, slowMarked.size)
        assertTrue(failed.isEmpty())
    }

    @Test fun probeKeepsSlowModelsEligibleAndMarksHardErrors() = runBlocking {
        val c = ProviderProfile(Provider.GROQ, apiKey = "s", model = "m1")
        val results = AutoRouter.probe(listOf(a, b, c), probeTimeoutMs = 600, request = { p, emit ->
            when (p) {
                a -> { emit("OK"); "OK" }
                b -> awaitCancellation()
                else -> throw ApiException("Rate limited", AccessState.RATE_LIMITED)
            }
        })
        val byModel = results.associateBy { it.profile.model }
        assertEquals(AccessState.USABLE, byModel["gpt-mini"]!!.state)
        assertTrue(byModel["gpt-mini"]!!.latencyMs < 600)
        assertEquals(AccessState.USABLE, byModel["gemini-flash"]!!.state)
        assertEquals(600L, byModel["gemini-flash"]!!.latencyMs)
        assertEquals(AccessState.RATE_LIMITED, byModel["m1"]!!.state)
    }

    @Test fun rateLimitFailsOverImmediately() = runBlocking {
        val started = System.currentTimeMillis()
        val result = AutoRouter.generate(listOf(a, b), onAttempt = { _, _ -> }, onFailure = { _, _ -> }, request = { p, emit ->
            if (p == a) throw ApiException("Rate limited", AccessState.RATE_LIMITED)
            emit("ok"); "ok"
        }, onText = {})
        assertEquals(b, result.first)
        assertTrue("failover took ${System.currentTimeMillis() - started}ms", System.currentTimeMillis() - started < 2000)
    }

    @Test fun stalledStreamContinuesOnNextModel() = runBlocking {
        val partials = mutableListOf<String>()
        val result = AutoRouter.generate(listOf(a, b), stallTimeoutMs = 300,
            onAttempt = { _, _ -> }, onFailure = { _, _ -> }, request = { p, emit ->
                if (p == a) { emit("begun"); awaitCancellation() }
                emit("continued"); "continued"
            }, onText = {}, onPartialFailure = { partials += it })
        assertEquals(b, result.first)
        assertEquals(listOf("begun"), partials)
    }
}
