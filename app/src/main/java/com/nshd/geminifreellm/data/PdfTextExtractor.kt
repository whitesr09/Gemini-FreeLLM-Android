package com.nshd.geminifreellm.data

import java.io.File
import java.nio.charset.StandardCharsets

object PdfTextExtractor {
    private const val MAX_CHARS = 120_000

    fun extract(file: File): String {
        val bytes = runCatching { file.inputStream().use { it.readNBytes(8 * 1024 * 1024) } }.getOrNull() ?: return ""
        val raw = String(bytes, StandardCharsets.ISO_8859_1)
        val out = StringBuilder()

        Regex("""\((?:\\.|[^()])*\)\s*Tj""").findAll(raw).forEach { match ->
            val token = match.value.substringBeforeLast(")").removePrefix("(")
            out.append(decodeLiteral(token)).append(' ')
            if (out.length >= MAX_CHARS) return@forEach
        }

        if (out.isEmpty()) {
            Regex("""<([0-9A-Fa-f\s]{2,})>\s*Tj""").findAll(raw).forEach { match ->
                out.append(decodeHex(match.groupValues[1])).append(' ')
                if (out.length >= MAX_CHARS) return@forEach
            }
        }

        return out.toString()
            .replace(Regex("\s+"), " ")
            .trim()
            .take(MAX_CHARS)
    }

    private fun decodeLiteral(value: String): String =
        value.replace("""\n""", "
")
            .replace("""\r""", "")
            .replace("""\t""", "	")
            .replace("""\(""", "(")
            .replace("""\)""", ")")
            .replace("""\\ """.trim(), "\\")

    private fun decodeHex(hex: String): String {
        val clean = hex.replace(Regex("\s+"), "")
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }
}
