package com.nshd.geminifreellm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
