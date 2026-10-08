package com.nshd.geminifreellm.ui

import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun unfinishedStreamingFenceKeepsItsCodeFormatting() {
        val blocks = markdownBlocks("Explanation\n```kotlin\nval a = 1")
        assertEquals(MarkdownBlock("Explanation"), blocks[0])
        assertEquals(MarkdownBlock("val a = 1", "kotlin"), blocks[1])
    }
    @Test fun closedFenceDoesNotSwallowFollowingParagraph() {
        assertEquals(listOf(MarkdownBlock("x", ""), MarkdownBlock("After")), markdownBlocks("```\nx\n```\nAfter"))
    }
    @Test fun tablesParseEscapedPipesAndRejectMalformedRows() {
        val table = markdownTable("| Name | Value |\n| --- | :---: |\n| A\\|B | 1 |")!!
        assertEquals(listOf("Name", "Value"), table.header)
        assertEquals(listOf("A|B", "1"), table.rows.first())
        assertNull(markdownTable("a|b\nnot|separator"))
        assertNull(markdownTable("a|b\n---|---\na|b|c"))
    }
}
