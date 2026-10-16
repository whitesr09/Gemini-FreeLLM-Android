package com.nshd.geminifreellm.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Single place that turns transport / HTTP failures into chat-friendly copy.
 * The client and the test fakes both read from here so messages stay consistent.
 */
object ApiErrorMapper {
    fun fromHttp(code: Int): String = when (code) {
        401, 403 -> "The API key was rejected. Check your Unified API key."
        404 -> "API endpoint not found. Check the Base URL."
        429 -> "The provider is rate-limited. Try again in a moment."
        in 500..599 -> "Server error ($code). Try again."
        else -> "Request failed ($code)."
    }

    fun fromNetwork(error: IOException): String = when (error) {
        is UnknownHostException -> "Couldn't reach the server. Check the Base URL and your connection."
        is ConnectException -> "Couldn't connect to FreeLLMAPI. Make sure the server is running."
        is SocketTimeoutException -> "The request timed out. The server may be busy — try again."
        else -> "Couldn't reach FreeLLMAPI. Make sure the server is running."
    }

    fun fromUnexpected(message: String?): String =
        message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
