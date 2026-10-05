package com.nshd.geminifreellm.data

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeFreeLlmApi(private val statusCode: Int? = null) {
    val calls = AtomicInteger(0)
    fun chat(): Result<List<String>> {
        calls.incrementAndGet()
        if (statusCode != null) {
            return Result.failure(IllegalStateException(ApiErrorMapper.fromHttp(statusCode).message))
        }
        return Result.success(listOf("hello", " world"))
    }
}

class FakeFreeLlmApiTest {
    @Test fun successfulFakeStreamIsDeterministic() {
        val fake = FakeFreeLlmApi()
        assertEquals(listOf("hello", " world"), fake.chat().getOrThrow())
        assertEquals(1, fake.calls.get())
    }

    @Test fun fakeCanExerciseTypedErrorMapping() {
        val fake = FakeFreeLlmApi(statusCode = 429)
        assertTrue(fake.chat().exceptionOrNull()?.message?.contains("rate", true) == true)
    }
}
