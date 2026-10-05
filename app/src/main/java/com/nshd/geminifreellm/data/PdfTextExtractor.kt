package com.nshd.geminifreellm.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

object PdfTextExtractor {
    private const val MAX_READ_BYTES = 8 * 1024 * 1024
    private const val MAX_CHARS = 120_000

    fun extract(file: File): String {
        val bytes = runCatching {
            file.inputStream().buffered().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0
                while (total < MAX_READ_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_READ_BYTES - total))
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return ""

        val raw = String(bytes, StandardCharsets.ISO_8859_1)
        val out = StringBuilder()
        Regex("\\((?:\\\\.|[^()])*\\)\\s*Tj").findAll(raw).forEach { match ->
            val token = match.value.substringBeforeLast(")").removePrefix("(")
            val decoded = decodeLiteral(token)
            if (decoded.isNotBlank()) out.append(decoded).append(' ')
            if (out.length >= MAX_CHARS) return@forEach
        }
        if (out.isBlank()) {
            Regex("<([0-9A-Fa-f\\s]{2,})>\\s*Tj").findAll(raw).forEach { match ->
                out.append(decodeHex(match.groupValues[1])).append(' ')
                if (out.length >= MAX_CHARS) return@forEach
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim().take(MAX_CHARS)
    }

    private fun decodeLiteral(value: String): String =
        value.replace("\\\\n", "\n")
            .replace("\\\\r", "\r")
            .replace("\\\\t", "\t")
            .replace("\\\\(", "(")
            .replace("\\\\)", ")")
            .replace("\\\\\\", "\\")

    private fun decodeHex(hex: String): String {
        val clean = hex.replace(Regex("\\s+"), "")
        if (clean.length < 2) return ""
        val even = if (clean.length % 2 == 0) clean else clean.dropLast(1)
        val bytes = ByteArray(even.length / 2)
        for (i in bytes.indices) {
            bytes[i] = even.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
