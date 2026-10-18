package com.nshd.geminifreellm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasOffsetMappingTest {
    @Test fun mappingAccountsForEachLinesOwnGutter() {
        val mapping = CanvasOffsetMapping("ab\ncd", 7)

        assertEquals(7, mapping.originalToTransformed(0))
        assertEquals(9, mapping.originalToTransformed(2))
        assertEquals(17, mapping.originalToTransformed(3))

        assertEquals(0, mapping.transformedToOriginal(7))
        assertEquals(2, mapping.transformedToOriginal(9))
        assertEquals(3, mapping.transformedToOriginal(10))
        assertEquals(3, mapping.transformedToOriginal(17))
    }

    @Test fun emptyDocumentOffsetsRemainBounded() {
        val mapping = CanvasOffsetMapping("", 7)
        assertEquals(7, mapping.originalToTransformed(0))
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(0, mapping.transformedToOriginal(7))
    }
}
