package com.nshd.geminifreellm.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CanvasOffsetMappingTest {
    @Test fun codeLinesRetainTrailingBlankLines() {
        assertEquals(listOf(""), canvasCodeLines(""))
        assertEquals(listOf("one", ""), canvasCodeLines("one\n"))
        assertEquals(listOf("one", "two", ""), canvasCodeLines("one\ntwo\n"))
        assertEquals(listOf("one", "", "three"), canvasCodeLines("one\n\nthree"))
    }

    @Test fun trailingNewlineGetsItsOwnGutterLine() {
        val source = "one\ntwo\n"
        val mapping = CanvasOffsetMapping(source, 7)

        assertEquals(3, canvasCodeLines(source).size)
        assertEquals(29, mapping.originalToTransformed(source.length))
        assertEquals(source.length, mapping.transformedToOriginal(29))
    }

    @Test fun mappingAccountsForEachLinesOwnGutter() {
        val mapping = CanvasOffsetMapping("ab\ncd", 7)

        assertEquals(7, mapping.originalToTransformed(0))
        assertEquals(9, mapping.originalToTransformed(2))
        assertEquals(17, mapping.originalToTransformed(3))

        assertEquals(0, mapping.transformedToOriginal(7))
        assertEquals(2, mapping.transformedToOriginal(9))
        assertEquals(3, mapping.transformedToOriginal(10))
        assertEquals(3, mapping.transformedToOriginal(16))
        assertEquals(3, mapping.transformedToOriginal(17))
    }

    @Test fun emptyDocumentOffsetsRemainBounded() {
        val mapping = CanvasOffsetMapping("", 7)
        assertEquals(7, mapping.originalToTransformed(0))
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(0, mapping.transformedToOriginal(7))
    }
}
