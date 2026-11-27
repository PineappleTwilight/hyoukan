package eu.kanade.tachiyomi.data.novel

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File

/**
 * The pure half of the novel plugin host (#104): header parsing, id derivation and JSON mapping.
 * The JavaScript runtime itself needs a device, but everything that can silently produce an empty
 * catalogue lives here.
 */
@Execution(ExecutionMode.CONCURRENT)
class NovelPluginTest {

    @Test
    fun `a header gives the plugin its identity`() {
        val script = """
            // @id example-novels
            // @name Example Novels
            // @lang pt-BR
            // @version 3
            // @site https://example.com
            module.exports = {};
        """.trimIndent()

        val header = NovelPluginHeader.parse(script)!!

        header.id shouldBe "example-novels"
        header.name shouldBe "Example Novels"
        header.lang shouldBe "pt-br"
        header.version shouldBe "3"
        header.site shouldBe "https://example.com"
    }

    @Test
    fun `a file without an id or name is rejected`() {
        NovelPluginHeader.parse("module.exports = {};") shouldBe null
        NovelPluginHeader.parse("// @id only-an-id\nmodule.exports = {};") shouldBe null
        NovelPluginHeader.parse("// @id bad id!\n// @name Bad\n") shouldBe null
    }

    @Test
    fun `optional fields fall back rather than failing`() {
        val header = NovelPluginHeader.parse("// @id plain\n// @name Plain\n")!!

        header.lang shouldBe "en"
        header.version shouldBe "1"
        header.site shouldBe null
    }

    @Test
    fun `source ids are stable and live outside the extension range`() {
        val a = NovelPlugin("novel-a", "A", "en", "1", null, File("a.js")).sourceId
        val b = NovelPlugin("novel-b", "B", "en", "1", null, File("b.js")).sourceId
        val aAgain = NovelPlugin("novel-a", "Renamed", "en", "2", null, File("renamed.js")).sourceId

        a shouldBe aAgain
        (a != b) shouldBe true
        (a >= NovelPlugin.NOVEL_PLUGIN_SOURCE_ID_BASE) shouldBe true
    }

    @Test
    fun `catalogue arrays map and skip incomplete entries`() {
        val json = """
            [
              {"title": "One", "url": "/one", "cover": "https://c/1", "author": "A"},
              {"title": "No url"},
              {"url": "/no-title"}
            ]
        """.trimIndent()

        val mangas = NovelPluginCodec.mangas(json)

        mangas.size shouldBe 1
        mangas.single().title shouldBe "One"
        mangas.single().url shouldBe "/one"
        mangas.single().author shouldBe "A"
        mangas.single().thumbnail_url shouldBe "https://c/1"
    }

    @Test
    fun `chapter numbers and dates are optional`() {
        val json = """
            [
              {"name": "Chapter 1", "url": "/c1", "chapterNumber": 1.5, "dateUpload": 123},
              {"name": "Extra", "url": "/extra"}
            ]
        """.trimIndent()

        val chapters = NovelPluginCodec.chapters(json)

        chapters.size shouldBe 2
        chapters[0].chapter_number shouldBe 1.5f
        chapters[0].date_upload shouldBe 123L
        chapters[1].chapter_number shouldBe -1f
    }

    @Test
    fun `a chapter's text can be one document or several`() {
        val one = NovelPluginCodec.pages("\"<html>one</html>\"", "/c1")
        val many = NovelPluginCodec.pages("[\"<html>a</html>\",\"<html>b</html>\"]", "/c1")

        one.size shouldBe 1
        one.single().text shouldBe "<html>one</html>"
        many.size shouldBe 2
        many[1].index shouldBe 1
        many[1].url shouldBe "/c1#1"
    }

    @Test
    fun `malformed plugin output produces nothing rather than throwing`() {
        NovelPluginCodec.mangas("not json") shouldBe emptyList()
        NovelPluginCodec.chapters("{}") shouldBe emptyList()
        NovelPluginCodec.pages("42", "/c") shouldBe emptyList()
        NovelPluginCodec.pages(null, "/c") shouldBe emptyList()
    }

    @Test
    fun `a parsed script imports as a plugin pointing at its file`() {
        val repositoryResult = NovelPluginHeader.parse("// @id x\n// @name X\n")
        repositoryResult.shouldBeInstanceOf<NovelPluginHeader.Header>()
    }
}
