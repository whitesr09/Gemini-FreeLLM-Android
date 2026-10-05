package com.nshd.geminifreellm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMathTest {
    @Test fun cosineSimilarityIsNormalized() {
        assertEquals(1f, VectorMath.cosineSimilarity(listOf(1f, 0f), listOf(1f, 0f)), 0.0001f)
        assertEquals(0f, VectorMath.cosineSimilarity(listOf(1f, 0f), listOf(0f, 1f)), 0.0001f)
    }

    @Test fun topKSortsByScore() {
        val out = VectorMath.topK(
            listOf(1f, 0f),
            listOf("a" to listOf(0f, 1f), "b" to listOf(1f, 0f), "c" to listOf(0.9f, 0.1f)),
            2
        )
        assertEquals(listOf("b", "c"), out.map { it.first })
        assertTrue(out.first().second > out.last().second)
    }
}
