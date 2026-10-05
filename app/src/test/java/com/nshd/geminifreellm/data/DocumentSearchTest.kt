package com.nshd.geminifreellm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSearchTest {
    @Test
    fun ranksRelevantChunks() {
        val result = DocumentSearch.lexicalScores(
            "room migration",
            listOf("unrelated", "Room migration adds a column", "migration")
        )
        assertEquals(1, result.first().first)
        assertTrue(result.first().second > result[1].second)
    }
}
