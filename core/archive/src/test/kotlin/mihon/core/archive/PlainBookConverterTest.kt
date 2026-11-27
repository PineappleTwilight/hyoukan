package mihon.core.archive

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.charset.Charset

/**
 * FB2/TXT conversion (#104).
 *
 * Both formats reach the same reader as EPUB, so the conversion has to produce complete, escaped
 * HTML documents and a plain-text index that search can use. The failure that matters is not a
 * crash: it is a book that opens with its paragraphs run together, or one whose markup is served
 * as text.
 */
@Execution(ExecutionMode.CONCURRENT)
class PlainBookConverterTest {

    private val fb2 = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
          <body>
            <title><p>Book title</p></title>
            <section>
              <title><p>Chapter one</p></title>
              <p>First paragraph with <emphasis>emphasis</emphasis> and &amp; an entity.</p>
              <p>Second paragraph.</p>
            </section>
            <section>
              <title><p>Chapter two</p></title>
              <section>
                <title><p>Nested scene</p></title>
                <p>Deep text.</p>
              </section>
            </section>
          </body>
        </FictionBook>
    """.trimIndent()

    @Test
    fun `fb2 sections become titled pages`() {
        val sections = PlainBookConverter.fb2Sections(fb2)

        sections shouldHaveSize 2
        sections[0].title shouldBe "Chapter one"
        sections[1].title shouldBe "Nested scene"
    }

    @Test
    fun `fb2 pages are complete html and keep emphasis`() {
        val page = PlainBookConverter.fb2Sections(fb2).first().html

        page shouldContain "<!DOCTYPE html>"
        page shouldContain "<em>emphasis</em>"
        page shouldContain "First paragraph"
    }

    @Test
    fun `fb2 plain text is searchable without markup`() {
        val text = PlainBookConverter.fb2Sections(fb2).first().plainText

        text shouldContain "First paragraph with emphasis and & an entity."
        text shouldNotContain "<p>"
    }

    @Test
    fun `a body without sections still yields one page`() {
        val xml = """
            <FictionBook><body><p>Only text.</p></body></FictionBook>
        """.trimIndent()

        val sections = PlainBookConverter.fb2Sections(xml)

        sections shouldHaveSize 1
        sections.single().html shouldContain "Only text."
    }

    @Test
    fun `plain text is escaped rather than rendered`() {
        val sections = PlainBookConverter.plainTextSections("<script>alert(1)</script> & more")

        sections.single().html shouldContain "&lt;script&gt;"
        sections.single().html shouldNotContain "<script>"
    }

    @Test
    fun `txt pages break at the character budget`() {
        val paragraph = "A sentence that is long enough to matter. ".repeat(200)
        val sections = PlainBookConverter.plainTextSections(paragraph, maxChars = 2_000)

        (sections.size > 1) shouldBe true
        (sections.all { it.plainText.length <= 2_000 }) shouldBe true
    }

    @Test
    fun `blank paragraphs do not create empty pages`() {
        PlainBookConverter.plainTextSections("\n\n\n\n").size shouldBe 0
    }

    @Test
    fun `decode follows a declared legacy encoding`() {
        val bytes = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><p>Привет</p>"
            .toByteArray(Charset.forName("windows-1251"))

        PlainBookConverter.decode(bytes) shouldContain "Привет"
    }

    @Test
    fun `decode strips a utf-8 bom`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "plain".toByteArray()

        PlainBookConverter.decode(bytes) shouldBe "plain"
    }
}
