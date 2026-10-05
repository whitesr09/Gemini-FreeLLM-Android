package com.nshd.geminifreellm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorMapperTest {
    @Test fun mapsUnauthorized() {
        val error = ApiErrorMapper.fromHttp(401)
        assertEquals(ApiErrorKind.UNAUTHORIZED, error.kind)
        assertFalse(error.retryable)
        assertEquals(401, error.httpStatus)
    }

    @Test fun mapsRateLimitAsRetryable() {
        val error = ApiErrorMapper.fromHttp(429)
        assertEquals(ApiErrorKind.RATE_LIMITED, error.kind)
        assertTrue(error.retryable)
    }

    @Test fun preservesServerMessageForBadRequest() {
        val error = ApiErrorMapper.fromHttp(400, "bad model")
        assertEquals("bad model", error.message)
        assertEquals("bad_request", error.debugCode)
    }

    @Test fun mapsServerRangeAsRetryable() {
        val error = ApiErrorMapper.fromHttp(503)
        assertEquals(ApiErrorKind.SERVER, error.kind)
        assertTrue(error.retryable)
    }
}
