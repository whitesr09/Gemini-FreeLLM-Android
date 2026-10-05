package com.nshd.geminifreellm.data

enum class ApiErrorKind {
    BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, TIMEOUT, UNSUPPORTED,
    RATE_LIMITED, SERVER, NETWORK, PARSE, EMPTY, CANCELLED
}

data class ApiError(val kind: ApiErrorKind, val message: String)

object ApiErrorMapper {
    fun fromHttp(code: Int, serverMessage: String? = null): ApiError = when (code) {
        400 -> ApiError(ApiErrorKind.BAD_REQUEST, serverMessage ?: "The request was invalid. Check the selected model and message.")
        401 -> ApiError(ApiErrorKind.UNAUTHORIZED, "API key rejected. Check your key in Settings.")
        403 -> ApiError(ApiErrorKind.FORBIDDEN, "Access forbidden. Check your API key permissions.")
        404 -> ApiError(ApiErrorKind.NOT_FOUND, "API endpoint not found. Check the Base URL.")
        408 -> ApiError(ApiErrorKind.TIMEOUT, "The server timed out. Retry the request.")
        422 -> ApiError(ApiErrorKind.UNSUPPORTED, serverMessage ?: "This request is not supported by the selected route.")
        429 -> ApiError(ApiErrorKind.RATE_LIMITED, "Rate limit reached. Retry later or switch to Auto.")
        in 500..599 -> ApiError(ApiErrorKind.SERVER, "Server error ($code). The server may be waking up; retry shortly.")
        else -> ApiError(ApiErrorKind.SERVER, serverMessage ?: "Request failed ($code).")
    }
}