package com.nshd.geminifreellm.data

object DocumentSearch {
    fun lexicalScores(query: String, contents: List<String>, limit: Int = 6): List<Pair<Int, Float>> {
        val terms = query.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 3 }.distinct().take(24)
        if (terms.isEmpty()) return emptyList()
        return contents.mapIndexed { index, content ->
            val lower = content.lowercase()
            var score = 0
            terms.forEach { term ->
                var start = 0
                while (true) {
                    val at = lower.indexOf(term, start)
                    if (at < 0) break
                    score++
                    start = at + term.length
                    if (score >= 100) break
                }
            }
            index to score.toFloat()
        }.filter { it.second > 0f }.sortedByDescending { it.second }
            .take(limit.coerceIn(1, 12))
    }
}
