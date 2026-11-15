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

    @Test fun headingSplitsFromFollowingBody() {
        val units = proseUnits("## Plan\nBody text")
        assertEquals(ProseUnit.Heading(2, "Plan"), units[0])
        assertEquals(ProseUnit.Paragraph("Body text"), units[1])
    }

    @Test fun orderedListKeepsWrittenNumbers() {
        val list = proseUnits("1. First\n2. Second\n3. Third").single() as ProseUnit.ListBlock
        assertEquals(listOf("1.", "2.", "3."), list.items.map { it.marker })
        assertTrue(list.items.all { it.ordered })
        assertEquals("First", list.items[0].text)
    }

    @Test fun decimalNumbersAreNotListItems() {
        val units = proseUnits("The value 3.14 is pi")
        assertEquals(listOf(ProseUnit.Paragraph("The value 3.14 is pi")), units)
    }

    @Test fun nestedListsGetIncreasingLevels() {
        val list = proseUnits("- Top\n  - Child\n    - Grandchild\n- Back").single() as ProseUnit.ListBlock
        assertEquals(listOf(0, 1, 2, 0), list.items.map { it.level })
        assertTrue(list.items.none { it.ordered })
    }

    @Test fun checklistItemsExposeCheckedState() {
        val list = proseUnits("- [ ] Write tests\n- [x] Ship it\n- Plain").single() as ProseUnit.ListBlock
        assertEquals(false, list.items[0].checked)
        assertEquals("Write tests", list.items[0].text)
        assertEquals(true, list.items[1].checked)
        assertEquals("Ship it", list.items[1].text)
        assertNull(list.items[2].checked)
    }

    @Test fun partialChecklistWhileStreamingStaysAPlainBullet() {
        val list = proseUnits("- [ ] Done soon\n- [x").single() as ProseUnit.ListBlock
        assertEquals(2, list.items.size)
        assertEquals(false, list.items[0].checked)
        assertNull(list.items[1].checked)
        assertEquals("[x", list.items[1].text)
    }

    @Test fun indentedContinuationJoinsPreviousListItem() {
        val list = proseUnits("- First line\n  wrapped continuation\n- Second").single() as ProseUnit.ListBlock
        assertEquals(2, list.items.size)
        assertEquals("First line\nwrapped continuation", list.items[0].text)
    }

    @Test fun incompleteTableWhileStreamingFallsBackToText() {
        assertNull(markdownTable("| Name | Value |\n| ---"))
        val units = proseUnits("| Name | Value |\n| ---")
        assertTrue(units.single() is ProseUnit.Paragraph)
    }

    @Test fun tableColumnAlignmentsAreParsed() {
        val table = markdownTable("| A | B | C |\n| :--- | :---: | ---: |\n| 1 | 2 | 3 |")!!
        assertEquals(
            listOf(MarkdownColumnAlign.LEFT, MarkdownColumnAlign.CENTER, MarkdownColumnAlign.RIGHT),
            table.alignments
        )
    }

    @Test fun tableAfterHeadingStillParses() {
        val units = proseUnits("## Data\n| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(ProseUnit.Heading(2, "Data"), units[0])
        assertTrue(units[1] is ProseUnit.Table)
    }

    @Test fun kotlinHighlightingMarksKeywordsStringsCommentsAndNumbers() {
        val line = "val label = \"count\" // total"
        val line2 = "val total = 42"
        val spans = highlightCodeSpans(listOf(line, line2), "kotlin")
        assertEquals(CodeSpan(0, 3, CodeSpanKind.KEYWORD), spans[0].first())
        assertTrue(spans[0].contains(CodeSpan(line.indexOf("\"count\""), line.indexOf("\"count\"") + 7, CodeSpanKind.STRING)))
        assertTrue(spans[0].contains(CodeSpan(line.indexOf("//"), line.length, CodeSpanKind.COMMENT)))
        assertTrue(spans[1].contains(CodeSpan(line2.indexOf("42"), line2.indexOf("42") + 2, CodeSpanKind.NUMBER)))
    }

    @Test fun blockCommentStateCarriesAcrossLines() {
        val lines = listOf("/* header", "still comment */ val x = 1")
        val spans = highlightCodeSpans(lines, "kotlin")
        assertEquals(listOf(CodeSpan(0, lines[0].length, CodeSpanKind.COMMENT)), spans[0])
        assertEquals(CodeSpan(0, 16, CodeSpanKind.COMMENT), spans[1].first())
        assertTrue(spans[1].contains(CodeSpan(17, 20, CodeSpanKind.KEYWORD)))
    }

    @Test fun pythonTripleQuotedStringCarriesAcrossLines() {
        val lines = listOf("text = \"\"\"first", "second\"\"\"")
        val spans = highlightCodeSpans(lines, "python")
        assertEquals(listOf(CodeSpan(7, lines[0].length, CodeSpanKind.STRING)), spans[0])
        assertEquals(listOf(CodeSpan(0, 9, CodeSpanKind.STRING)), spans[1])
    }

    @Test fun jsonKeysNumbersAndLiteralsAreDistinguished() {
        val line = "{\"name\": \"freellm\", \"count\": 3, \"ok\": true}"
        val spans = highlightCodeSpans(listOf(line), "json").single()
        assertTrue(spans.contains(CodeSpan(1, 7, CodeSpanKind.KEYWORD)))
        assertTrue(spans.contains(CodeSpan(9, 18, CodeSpanKind.STRING)))
        assertTrue(spans.contains(CodeSpan(line.indexOf('3'), line.indexOf('3') + 1, CodeSpanKind.NUMBER)))
        assertTrue(spans.contains(CodeSpan(line.indexOf("true"), line.indexOf("true") + 4, CodeSpanKind.KEYWORD)))
    }

    @Test fun htmlHighlightingMarksTagsAndAttributeValues() {
        val line = "<a href=\"https://example.com\">Link</a>"
        val spans = highlightCodeSpans(listOf(line), "html").single()
        assertTrue(spans.contains(CodeSpan(1, 2, CodeSpanKind.KEYWORD)))
        assertTrue(spans.contains(CodeSpan(line.indexOf("\"https"), line.indexOf("\"https") + 21, CodeSpanKind.STRING)))
    }

    @Test fun unknownLanguageRendersPlain() {
        assertTrue(highlightCodeSpans(listOf("val x = \"s\" // c"), "brainfuck").single().isEmpty())
        assertTrue(highlightCodeSpans(listOf("val x = 1"), "").single().isEmpty())
    }

    @Test fun highlightingHandlesLongBlocksLineByLine() {
        val lines = (1..120).map { "val v$it = $it" }
        val spans = highlightCodeSpans(lines, "kotlin")
        assertEquals(120, spans.size)
        assertTrue(spans.all { it.isNotEmpty() })
    }

    // --- Interactive checklist flipping (fix round 2, Fix 4) ---

    @Test fun toggleChecklistFlipsOpenToDoneAndBack() {
        val source = "- [ ] Write tests\n- [ ] Ship it"
        assertEquals("- [x] Write tests\n- [ ] Ship it", toggleChecklistItem(source, 0))
        assertEquals("- [ ] Write tests\n- [ ] Ship it", toggleChecklistItem("- [x] Write tests\n- [ ] Ship it", 0))
        assertEquals("- [ ] Write tests\n- [x] Ship it", toggleChecklistItem(source, 1))
    }

    @Test fun toggleChecklistHandlesUppercaseXAndStarBullets() {
        assertEquals("- [ ] Done", toggleChecklistItem("- [X] Done", 0))
        assertEquals("* [x] Star", toggleChecklistItem("* [ ] Star", 0))
        assertEquals("+ [x] Plus", toggleChecklistItem("+ [ ] Plus", 0))
    }

    @Test fun toggleChecklistCountsOnlyChecklistItemsInDocumentOrder() {
        val source = "# Plan\n\nSome intro\n- plain bullet\n1. [ ] ordered is not a checklist\n- [ ] first\n  - [ ] nested second"
        assertEquals(source.replace("- [ ] first", "- [x] first"), toggleChecklistItem(source, 0))
        assertEquals(source.replace("- [ ] nested second", "- [x] nested second"), toggleChecklistItem(source, 1))
        assertNull(toggleChecklistItem(source, 2))
    }

    @Test fun toggleChecklistIgnoresFencedCode() {
        val source = "```\n- [ ] not a real item\n```\n- [ ] real item"
        assertEquals("```\n- [ ] not a real item\n```\n- [x] real item", toggleChecklistItem(source, 0))
        assertNull(toggleChecklistItem(source, 1))
    }

    @Test fun toggleChecklistIgnoresTableChunksAndQuotes() {
        val table = "| Task | State |\n| --- | --- |\n| - [ ] cell text | open |\n\n- [ ] real item"
        assertEquals(table.replace("- [ ] real item", "- [x] real item"), toggleChecklistItem(table, 0))
        assertNull(toggleChecklistItem(table, 1))
        assertNull(toggleChecklistItem("> - [ ] quoted, not rendered as a checklist", 0))
    }

    @Test fun toggleChecklistOutOfRangeReturnsNull() {
        assertNull(toggleChecklistItem("", 0))
        assertNull(toggleChecklistItem("no lists here", 0))
        assertNull(toggleChecklistItem("- [ ] only", 1))
        assertNull(toggleChecklistItem("- [ ] only", -1))
    }

    @Test fun toggleChecklistPreservesEverythingElseByteForByte() {
        val source = "Intro with [ ] brackets in prose\n\n- [ ] task one  \n- [x] task two\n\nTrailing paragraph"
        assertEquals("Intro with [ ] brackets in prose\n\n- [x] task one  \n- [x] task two\n\nTrailing paragraph",
            toggleChecklistItem(source, 0))
    }

}
