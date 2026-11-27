package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.nio.charset.Charset

/**
 * Converts the two plain ebook formats the local source accepts into the same page shape the EPUB
 * reader already renders.
 *
 * ### Why these formats
 *
 * FB2 is the dominant Russian ebook format and TXT is what every reader on earth exports. Neither
 * carries its own layout engine, so the honest conversion is to markup: paragraphs, emphasis, verse
 * and headings. Everything a reader can style comes from the host's typography; nothing here
 * invents a page size.
 *
 * ### Why jsoup's XML parser
 *
 * FB2 is XML, and its paragraphs can contain escaped markup and commentary. A hand-rolled tag
 * scanner would have to reproduce entity and CDATA rules; jsoup already does, and it is already a
 * dependency of this module for the EPUB path.
 *
 * Images are deliberately not inlined yet: an FB2's illustrations live in `<binary>` elements and
 * would need a resource server of their own, while the text is what makes the book readable.
 */
object PlainBookConverter {

    /** One natural unit of a book: a chapter for FB2, a page-sized chunk for TXT. */
    data class Section(
        val title: String?,
        val html: String,
        val plainText: String,
    )

    /** Roughly how much text one TXT page holds before the reader pages again. */
    const val TXT_PAGE_CHARS = 12_000

    fun fb2Sections(xml: String): List<Section> {
        val doc = try {
            Jsoup.parse(xml, "", Parser.xmlParser())
        } catch (_: Exception) {
            return emptyList()
        }
        val body = doc.selectFirst("FictionBook > body")
            ?: doc.selectFirst("body")
            ?: return emptyList()
        val sections = body.children()
            .filter { it.tagName().equals("section", ignoreCase = true) }
            .flatMap { flattenSection(it) }
        if (sections.isNotEmpty()) return sections

        val html = renderChildren(body)
        val text = body.text()
        if (text.isBlank()) return emptyList()
        return listOf(Section(title = null, html = document(html), plainText = text))
    }

    /**
     * A section that has its own subsections is a container (a part, a volume); its children are
     * the readable units. Everything else is one page.
     */
    private fun flattenSection(section: Element): List<Section> {
        val children = section.children()
            .filter { it.tagName().equals("section", ignoreCase = true) }
        if (children.isNotEmpty()) return children.flatMap { flattenSection(it) }
        val html = renderChildren(section)
        val text = section.text()
        if (text.isBlank()) return emptyList()
        val title = section.selectFirst("title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        return listOf(Section(title = title, html = document(html), plainText = text))
    }

    fun plainTextSections(text: String, maxChars: Int = TXT_PAGE_CHARS): List<Section> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = normalized
            .split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return emptyList()

        val pages = mutableListOf<Section>()
        val current = StringBuilder()
        fun flush() {
            if (current.isEmpty()) return
            val body = current.toString()
            pages += Section(
                title = null,
                html = document(body),
                plainText = Jsoup.parseBodyFragment(body).text(),
            )
            current.setLength(0)
        }

        for (paragraph in paragraphs) {
            if (current.isNotEmpty() && current.length + paragraph.length + 1 > maxChars) flush()
            if (paragraph.length > maxChars) {
                // A single enormous paragraph (a wall of text with no blank lines) still has to
                // fit: break it at sentence boundaries where possible, at the limit otherwise.
                paragraph.chunkedAtSentences(maxChars).forEach { chunk ->
                    current.append("<p>").append(Entities.escape(chunk)).append("</p>")
                    flush()
                }
                continue
            }
            current.append("<p>").append(Entities.escape(paragraph).replace("\n", "<br/>")).append("</p>")
        }
        flush()
        return pages
    }

    private fun String.chunkedAtSentences(maxChars: Int): List<String> {
        if (length <= maxChars) return listOf(this)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < length) {
            var end = (start + maxChars).coerceAtMost(length)
            if (end < length) {
                val boundary = indexOfAny(charArrayOf('.', '!', '?', '\n'), startIndex = end - maxChars / 4)
                if (boundary in start until end) end = boundary + 1
            }
            chunks += substring(start, end)
            start = end
        }
        return chunks
    }

    // ---------------------------------------------------------------- markup

    private fun renderChildren(element: Element): String {
        val out = StringBuilder()
        element.childNodes().forEach { out.append(renderNode(it)) }
        return out.toString()
    }

    private fun renderNode(node: Node): String = when (node) {
        is TextNode -> Entities.escape(node.text())
        is Element -> renderElement(node)
        else -> ""
    }

    private fun renderElement(element: Element): String {
        val inner = StringBuilder()
        element.childNodes().forEach { inner.append(renderNode(it)) }
        return when (element.tagName().lowercase()) {
            "p" -> "<p>${inner.toString().trim()}</p>"
            "title" -> "<h2>${inner.toString().trim()}</h2>"
            "subtitle" -> "<h2 class=\"subtitle\">${inner.toString().trim()}</h2>"
            "emphasis" -> "<em>${inner.toString().trim()}</em>"
            "strong" -> "<strong>${inner.toString().trim()}</strong>"
            "strikethrough" -> "<s>${inner.toString().trim()}</s>"
            "sub" -> "<sub>${inner.toString().trim()}</sub>"
            "sup" -> "<sup>${inner.toString().trim()}</sup>"
            "code" -> "<code>${inner.toString().trim()}</code>"
            "cite" -> "<cite>${inner.toString().trim()}</cite>"
            "text-author" -> "<p class=\"text-author\">${inner.toString().trim()}</p>"
            "epigraph" -> "<blockquote>${inner.toString().trim()}</blockquote>"
            "annotation" -> "<blockquote class=\"annotation\">${inner.toString().trim()}</blockquote>"
            "poem" -> "<div class=\"poem\">$inner</div>"
            "stanza" -> "<div class=\"stanza\">$inner</div>"
            "v" -> "<p class=\"verse\">${inner.toString().trim()}</p>"
            "empty-line" -> "<br/>"
            "section" -> inner.toString()
            "image" -> ""
            "a" -> {
                val href = element.attr("href")
                if (href.startsWith("http://") || href.startsWith("https://")) {
                    "<a href=\"${Entities.escape(href)}\">${inner.toString().trim()}</a>"
                } else {
                    inner.toString()
                }
            }
            else -> inner.toString()
        }
    }

    /** A complete document, as `TextPage.text` requires: the host does not inject a wrapper. */
    fun document(bodyHtml: String): String = """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8"/>
        <style>
        body { margin: 0; padding: 0; line-height: 1.55; }
        p { margin: 0 0 0.9em 0; text-align: justify; }
        h2 { font-size: 1.15em; margin: 1.4em 0 0.8em 0; }
        blockquote { margin: 0.8em 1.2em; opacity: 0.85; }
        .poem { margin: 0.8em 0 0.8em 1.2em; }
        .verse { margin: 0; text-align: left; }
        .text-author { text-align: right; font-style: italic; }
        .subtitle { text-align: center; font-weight: normal; opacity: 0.8; }
        </style>
        </head>
        <body>$bodyHtml</body>
        </html>
    """.trimIndent()

    /**
     * Decodes a book's bytes using the declaration the format itself carries.
     *
     * FB2 states `encoding` in its XML declaration and TXT usually does not. UTF-8 is the fallback
     * because it is what modern exports use; an unknown declared charset falls back rather than
     * failing, since a readable page with one wrong character beats no book at all.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, Charsets.UTF_16BE)
        }
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
        val declared = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']")
            .find(head)?.groupValues?.getOrNull(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset)
    }
}
