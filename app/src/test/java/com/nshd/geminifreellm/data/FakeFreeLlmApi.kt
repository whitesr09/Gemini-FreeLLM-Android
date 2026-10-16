package com.nshd.geminifreellm.data

import java.util.concurrent.atomic.AtomicInteger

data class ModelInfo(
    val id: String,
    val label: String,
    val available: Boolean = true,
    val provider: String = "",
    val supportsVision: Boolean = false
)

/**
 * Deterministic fake transport used by JVM tests. It never opens a socket.
 * Error copy comes from [ApiErrorMapper] so tests assert the same strings users see.
 */
class FakeFreeLlmApi(
    private val statusCode: Int = 200,
    private val chunks: List<String> = listOf("hello", " world")
) {
    val calls = AtomicInteger(0)

    fun chat(): Result<List<String>> {
        calls.incrementAndGet()
        return if (statusCode in 200..299) Result.success(chunks) else Result.failure(
            IllegalStateException(ApiErrorMapper.fromHttp(statusCode))
        )
    }

    fun modelCatalog(): List<ModelInfo> = listOf(
        ModelInfo("auto", "Auto", true),
        ModelInfo("fake-vision", "Fake Vision", true, provider = "fake", supportsVision = true)
    )
}
