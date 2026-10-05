package com.nshd.geminifreellm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorMapperTest {
    @Test fun mapsAllImportantStatusClasses() {
        assertEquals(ApiErrorKind.BAD_REQUEST, ApiErrorMapper.fromHttp(400, "bad").kind)
        assertEquals(ApiErrorKind.UNAUTHORIZED, ApiErrorMapper.fromHttp(401, null).kind)
        assertEquals(ApiErrorKind.FORBIDDEN, ApiErrorMapper.fromHttp(403, null).kind)
        assertEquals(ApiErrorKind.NOT_FOUND, ApiErrorMapper.fromHttp(404, null).kind)
        assertEquals(ApiErrorKind.TIMEOUT, ApiErrorMapper.fromHttp(408, null).kind)
        assertEquals(ApiErrorKind.UNPROCESSABLE_ENTITY, ApiErrorMapper.fromHttp(422, null).kind)
        assertEquals(ApiErrorKind.RATE_LIMITED, ApiErrorMapper.fromHttp(429, null).kind)
        assertEquals(ApiErrorKind.SERVER_ERROR, ApiErrorMapper.fromHttp(500, null).kind)
        assertEquals(ApiErrorKind.SERVER_ERROR, ApiErrorMapper.fromHttp(502, null).kind)
        assertTrue(ApiErrorMapper.fromHttp(408, null).retryable)
        assertTrue(ApiErrorMapper.fromHttp(429, null).retryable)
        assertTrue(ApiErrorMapper.fromHttp(503, null).retryable)
        assertFalse(ApiErrorMapper.fromHttp(401, null).retryable)
    }

    @Test fun preservesDeveloperDebugCodeForBadRequest() {
        val error = ApiErrorMapper.fromHttp(422, "Validation failed")
        assertEquals("bad_request_422", error.debugCode)
        assertEquals(422, error.httpStatus)
    }
}
