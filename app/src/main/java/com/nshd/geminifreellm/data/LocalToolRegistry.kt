package com.nshd.geminifreellm.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

data class ToolExecution(val name: String, val arguments: String, val result: String)

object LocalToolRegistry {
    fun execute(name: String, arguments: String): ToolExecution = when (name.lowercase(Locale.US)) {
        "calculator" -> ToolExecution(name, arguments, calculate(extract(arguments, "expression") ?: "0"))
        "unit_conversion" -> {
            val value = extract(arguments, "value")?.toDoubleOrNull() ?: 0.0
            ToolExecution(name, arguments, convertUnit(value, extract(arguments, "from").orEmpty(), extract(arguments, "to").orEmpty()))
        }
        "current_datetime" -> ToolExecution(name, arguments, SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
        else -> ToolExecution(name, arguments, "Unsupported local tool.")
    }

    fun definitionsJson(): List<Map<String, Any>> = listOf(
        mapOf("type" to "function", "function" to mapOf(
            "name" to "calculator",
            "description" to "Calculate basic arithmetic. Never execute code.",
            "parameters" to mapOf("type" to "object", "properties" to mapOf("expression" to mapOf("type" to "string")), "required" to listOf("expression"))
        )),
        mapOf("type" to "function", "function" to mapOf(
            "name" to "unit_conversion",
            "description" to "Convert common length, mass, and temperature units.",
            "parameters" to mapOf("type" to "object", "properties" to mapOf("value" to mapOf("type" to "number"), "from" to mapOf("type" to "string"), "to" to mapOf("type" to "string")), "required" to listOf("value", "from", "to"))
        )),
        mapOf("type" to "function", "function" to mapOf(
            "name" to "current_datetime",
            "description" to "Return the current UTC date/time.",
            "parameters" to mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        ))
    )

    private fun extract(json: String, key: String): String? =
        Regex("""(?i)"${Regex.escape(key)}"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
            ?: Regex("""(?i)"${Regex.escape(key)}"\s*:\s*(-?(?:\d+(?:\.\d+)?|\.\d+))""").find(json)?.groupValues?.get(1)

    private fun calculate(expression: String): String {
        val safe = expression.replace("×", "*").replace("÷", "/").trim()
        if (!Regex("""^[0-9+\-*/().\s]+$""").matches(safe)) return "Only basic arithmetic is supported."
        val value = runCatching { round(eval(safe) * 1_000_000.0) / 1_000_000.0 }.getOrElse { Double.NaN }
        return if (value.isNaN() || value.isInfinite()) "Invalid expression." else value.toString()
    }

    private fun eval(input: String): Double {
        class Parser(private val source: String) {
            private val chars = source.replace(" ", "").toCharArray()
            private var pos = 0

            fun parse(): Double {
                val value = expression()
                require(pos == chars.size) { "Unexpected token" }
                return value
            }

            private fun expression(): Double {
                var value = term()
                while (pos < chars.size && (chars[pos] == '+' || chars[pos] == '-')) {
                    value = if (chars[pos++] == '+') value + term() else value - term()
                }
                return value
            }

            private fun term(): Double {
                var value = factor()
                while (pos < chars.size && (chars[pos] == '*' || chars[pos] == '/')) {
                    value = if (chars[pos++] == '*') value * factor() else value / factor()
                }
                return value
            }

            private fun factor(): Double {
                require(pos < chars.size) { "Missing number" }
                if (chars[pos] == '+') {
                    pos++
                    return factor()
                }
                if (chars[pos] == '-') {
                    pos++
                    return -factor()
                }
                if (chars[pos] == '(') {
                    pos++
                    val value = expression()
                    require(pos < chars.size && chars[pos] == ')') { "Unbalanced parentheses" }
                    pos++
                    return value
                }
                val start = pos
                while (pos < chars.size && (chars[pos].isDigit() || chars[pos] == '.')) pos++
                require(pos > start) { "Missing number" }
                return String(chars, start, pos - start).toDouble()
            }
        }
        return Parser(input).parse()
    }

    private fun convertUnit(value: Double, fromRaw: String, toRaw: String): String {
        val from = fromRaw.lowercase(Locale.US).replace("°", "")
        val to = toRaw.lowercase(Locale.US).replace("°", "")
        if (from == to) return value.toString()
        val length = mapOf("m" to 1.0, "cm" to 0.01, "mm" to 0.001, "km" to 1000.0, "in" to 0.0254, "ft" to 0.3048, "mi" to 1609.344)
        val mass = mapOf("kg" to 1.0, "g" to 0.001, "mg" to 0.000001, "lb" to 0.45359237, "oz" to 0.028349523125)
        return when {
            from in length && to in length -> (value * length.getValue(from) / length.getValue(to)).toString()
            from in mass && to in mass -> (value * mass.getValue(from) / mass.getValue(to)).toString()
            from == "c" && to == "f" -> (value * 9 / 5 + 32).toString()
            from == "f" && to == "c" -> ((value - 32) * 5 / 9).toString()
            from == "c" && to == "k" -> (value + 273.15).toString()
            from == "k" && to == "c" -> (value - 273.15).toString()
            else -> "Unsupported unit conversion."
        }
    }
}
