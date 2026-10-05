package com.nshd.geminifreellm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest{
    @Test fun parsesCoreBlocks(){
        val fence="\u0060\u0060\u0060"
        val blocks=MarkdownParser.parse("# Title\n\n- one\n- two\n\n> quote\n\n"+fence+"kotlin\nval x = 1\n"+fence+"\n\n|A|B|\n|---|---|\n|1|2|")
        assertEquals(5,blocks.size);assertTrue(blocks[0] is MarkdownBlock.Heading);assertTrue(blocks[1] is MarkdownBlock.Bullet);assertTrue(blocks[3] is MarkdownBlock.Code);assertTrue(blocks[4] is MarkdownBlock.Table)
    }
}
