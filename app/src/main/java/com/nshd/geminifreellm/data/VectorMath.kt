package com.nshd.geminifreellm.data

import kotlin.math.sqrt

object VectorMath {
    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var aNorm = 0.0
        var bNorm = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            aNorm += x * x
            bNorm += y * y
        }
        if (aNorm == 0.0 || bNorm == 0.0) return 0f
        return (dot / (sqrt(aNorm) * sqrt(bNorm))).toFloat()
    }

    fun topK(query: List<Float>, vectors: List<Pair<String, List<Float>>>, k: Int): List<Pair<String, Float>> =
        vectors.map { (id, vector) -> id to cosineSimilarity(query, vector) }
            .sortedByDescending { it.second }
            .take(k.coerceAtLeast(0))
}
