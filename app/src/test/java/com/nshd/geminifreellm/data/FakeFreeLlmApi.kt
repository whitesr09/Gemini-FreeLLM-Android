package com.nshd.geminifreellm.data

import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic fake transport used by JVM tests. It never opens a socket.
 */
class FakeFreeLlmApi(
    private val statusCode: Int = 200,
    private val chunks: List<String> = listOf("hello", " world")
) {
    val calls = AtomicInteger(0)

    fun chat(): Result<List<String>> {
        calls.incrementAndGet()
        return if (statusCode in 200..299) Result.success(chunks) else Result.failure(
            IllegalStateException(ApiErrorMapper.fromHttp(statusCode, "fake error").message)
        )
    }

    fun modelCatalog(): List<ModelInfo> = listOf(
        ModelInfo("auto", "Auto", true),
        ModelInfo("fake-vision", "Fake Vision", true, provider = "fake", supportsVision = true)
    )
}
