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
        assertEquals(3, mapping.transformedToOriginal(16))
        assertEquals(3, mapping.transformedToOriginal(17))
    }

    @Test fun everyGutterOffsetSnapsToItsLogicalLineStart() {
        val source = "first\nsecond\n"
        val prefix = 7
        val mapping = CanvasOffsetMapping(source, prefix)

        // Second and third prefixes are not editable characters. Clicking anywhere
        // inside either prefix maps to the beginning of that source line.
        val secondPrefixStart = 6 + prefix
        for (offset in secondPrefixStart until secondPrefixStart + prefix) {
            assertEquals("offset $offset", 6, mapping.transformedToOriginal(offset))
        }
        val thirdLineStart = source.length
        val thirdPrefixStart = thirdLineStart + 2 * prefix
        for (offset in thirdPrefixStart until thirdPrefixStart + prefix) {
            assertEquals("offset $offset", thirdLineStart, mapping.transformedToOriginal(offset))
        }
        assertEquals(thirdLineStart + 3 * prefix, mapping.originalToTransformed(thirdLineStart))
    }

    @Test fun emptyDocumentOffsetsRemainBounded() {
        val mapping = CanvasOffsetMapping("", 7)
        assertEquals(7, mapping.originalToTransformed(0))
        assertEquals(0, mapping.transformedToOriginal(0))
        assertEquals(0, mapping.transformedToOriginal(7))
    }
}
