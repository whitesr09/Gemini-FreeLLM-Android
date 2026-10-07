package com.nshd.geminifreellm.data

import java.io.File
import java.nio.charset.StandardCharsets

/** Bounded text fallback for simple, uncompressed PDFs. This is not an OCR or full PDF engine. */
object PdfTextExtractor {
    private const val MAX_CHARS = 120_000
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun extract(file: File): String {
        require(file.length() <= MAX_BYTES) { "Choose a PDF smaller than 8 MB." }
        val raw = file.readText(StandardCharsets.ISO_8859_1)
        require(raw.startsWith("%PDF-")) { "This file is not a valid PDF." }
        val output = StringBuilder()
        var index = 0
        // Scan literal strings iteratively: recursive regex matching can overflow on large literals.
        while (index < raw.length) {
            if (raw[index] != '(') { index++; continue }
            index++
            var depth = 1
            val literal = StringBuilder()
            while (index < raw.length && depth > 0) {
                val char = raw[index++]
                when (char) {
                    '\\' -> if (index < raw.length) {
                        val escaped = raw[index++]
                        literal.append(when (escaped) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> escaped })
                    }
                    '(' -> { depth++; literal.append(char) }
                    ')' -> { depth--; if (depth > 0) literal.append(char) }
                    else -> literal.append(char)
                }
                require(literal.length <= MAX_CHARS) { "The PDF contains too much text. Attach a shorter excerpt." }
            }
            var operator = index
            while (operator < raw.length && raw[operator].isWhitespace()) operator++
            if (depth == 0 && raw.startsWith("Tj", operator)) {
                output.append(literal).append(' ')
                require(output.length <= MAX_CHARS) { "The PDF contains too much text. Attach a shorter excerpt." }
            }
        }
        // Preserve support for simple hex-encoded text operators without recursive matching.
        if (output.isEmpty()) {
            var cursor = 0
            while (cursor < raw.length) {
                val start = raw.indexOf('<', cursor)
                if (start < 0) break
                val end = raw.indexOf('>', start + 1)
                if (end < 0) break
                cursor = end + 1
                var operator = cursor
                while (operator < raw.length && raw[operator].isWhitespace()) operator++
                if (!raw.startsWith("Tj", operator)) continue
                val hex = raw.substring(start + 1, end).filterNot { it.isWhitespace() }
                if (hex.isEmpty() || hex.any { it.digitToIntOrNull(16) == null }) continue
                require(hex.length <= MAX_CHARS * 4) { "The PDF contains too much text. Attach a shorter excerpt." }
                val padded = if (hex.length % 2 == 0) hex else hex + "0"
                val bytes = ByteArray(padded.length / 2) { padded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val charset = if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) StandardCharsets.UTF_16 else StandardCharsets.UTF_8
                output.append(String(bytes, charset)).append(' ')
                require(output.length <= MAX_CHARS) { "The PDF contains too much text. Attach a shorter excerpt." }
            }
        }
        return output.toString().replace(Regex("\\s+"), " ").trim()
    }
}
