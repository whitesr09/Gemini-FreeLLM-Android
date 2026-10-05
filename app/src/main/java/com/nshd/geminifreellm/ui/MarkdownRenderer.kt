package com.nshd.geminifreellm.ui

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

object MarkdownRenderer {
    private const val MAX_CHARS = 120_000
    private val extensions = listOf(TablesExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).build()

    fun toSafeHtml(markdown: String): String {
        val html = renderer.render(parser.parse(markdown.take(MAX_CHARS)))
        return html.replace(Regex("""href="([^"]*)"""")) { match ->
            val url = match.groupValues[1]
            if (url.startsWith("https://", true) || url.startsWith("http://", true)) match.value else "href=\"#\"""
        }
    }
}
