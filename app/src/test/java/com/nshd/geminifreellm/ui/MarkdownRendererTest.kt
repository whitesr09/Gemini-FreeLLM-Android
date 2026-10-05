package com.nshd.geminifreellm.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun rendersMarkdownAndSanitizesLinks() {
        val html = MarkdownRenderer.toSafeHtml(
            "# Title\n\n**bold**\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n[bad](javascript:alert(1))"
        )
        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("href=\"#\""))
        assertTrue(!html.contains("javascript:"))
    }
}
