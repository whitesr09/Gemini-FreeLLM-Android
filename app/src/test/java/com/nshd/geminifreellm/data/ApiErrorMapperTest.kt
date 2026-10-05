package com.nshd.geminifreellm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiErrorMapperTest {
    @Test fun mapsUnauthorized() {
        val error = ApiErrorMapper.fromHttp(401)
        assertEquals(ApiErrorKind.UNAUTHORIZED, error.kind)
    }

    @Test fun mapsRateLimit() {
        val error = ApiErrorMapper.fromHttp(429)
        assertEquals(ApiErrorKind.RATE_LIMITED, error.kind)
    }

    @Test fun preservesServerMessageForBadRequest() {
        val error = ApiErrorMapper.fromHttp(400, "bad model")
        assertEquals("bad model", error.message)
    }

    @Test fun mapsServerRange() {
        val error = ApiErrorMapper.fromHttp(503)
        assertEquals(ApiErrorKind.SERVER, error.kind)
    }
}