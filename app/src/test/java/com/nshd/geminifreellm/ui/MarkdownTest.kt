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
    @Test fun shorterFenceInsideLongFenceDoesNotCloseCodeBlock() {
        val blocks = markdownBlocks("````kotlin\nval marker = \"```\"\nval a = 1\n````\nAfter")
        assertEquals(MarkdownBlock("val marker = \"```\"\nval a = 1", "kotlin"), blocks[0])
        assertEquals(MarkdownBlock("After"), blocks[1])
    }
    @Test fun tildeFencesAreRecognized() {
        assertEquals(listOf(MarkdownBlock("val a = 1", "kotlin"), MarkdownBlock("After")),
            markdownBlocks("~~~kotlin\nval a = 1\n~~~\nAfter"))
    }
    @Test fun multipleThinkBlocksAreRemovedFromVisibleReply() {
        assertEquals(Triple("Hello world", "plan\ncheck", false),
            splitThink("Hello <think>plan</think> world <think>check</think>"))
    }
    @Test fun unfinishedThinkBlockKeepsOnlyItsContentInReasoning() {
        assertEquals(Triple("Visible text", "still thinking", true),
            splitThink("Visible text<think>still thinking"))
    }

    @Test fun thinkRemovalPreservesWhitespaceOutsideRemovedBlocks() {
        val source = "    indented code\\n<think>plan</think>\\n    more code"
        assertEquals("    indented code\\n\\n    more code", splitThink(source).first)
    }

    @Test fun headingStyleStopsAtTheHeadingLine() {
        assertEquals(listOf("## Heading", "Body text on the next line"),
            splitHeadingAndBody("## Heading\nBody text on the next line"))
        assertEquals(listOf("ordinary paragraph"), splitHeadingAndBody("ordinary paragraph"))
    }

}
