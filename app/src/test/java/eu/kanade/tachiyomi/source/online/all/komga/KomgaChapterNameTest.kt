package eu.kanade.tachiyomi.source.online.all.komga

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * The Komga chapter-title template, ported from the Keiyoushi extension.
 *
 * The rules that matter to a reader are the two the "remove the number prefix" request needs:
 * `{title}` alone must not leave a leading separator, and a name that comes out blank or equal to
 * the series title must not disappear from the chapter list. The rest is the extension's own
 * substitution grammar, asserted so a hand-rolled replacement cannot quietly drift from it.
 */
@Execution(ExecutionMode.CONCURRENT)
class KomgaChapterNameTest {

    private val values = mapOf(
        "title" to "The Long Away",
        "seriesTitle" to "The Long Series",
        "number" to "3",
        "createdDate" to "2024-02-01T10:00:00Z",
        "releaseDate" to "2024-01-15",
        "size" to "42.1 MB",
        "sizeBytes" to "44166231",
    )

    @Test
    fun `the default format is the number prefix and the title`() {
        KomgaChapterNameTemplate.DEFAULT shouldBe "{number} - {title}"
        KomgaChapterNameTemplate.render(KomgaChapterNameTemplate.DEFAULT, values) shouldBe "3 - The Long Away"
    }

    @Test
    fun `dropping the number placeholder removes the prefix`() {
        KomgaChapterNameTemplate.render("{title}", values) shouldBe "The Long Away"
        KomgaChapterNameTemplate.render("{title} ({size})", values) shouldBe "The Long Away (42.1 MB)"
    }

    @Test
    fun `every documented placeholder is substituted`() {
        val rendered = KomgaChapterNameTemplate.render(
            "{seriesTitle} #{number} {title} {createdDate} {releaseDate} {size}/{sizeBytes}",
            values,
        )
        rendered shouldBe
            "The Long Series #3 The Long Away 2024-02-01T10:00:00Z 2024-01-15 42.1 MB/44166231"
    }

    @Test
    fun `an escaped brace is printed literally and not substituted`() {
        KomgaChapterNameTemplate.render("${'$'}{title}", values) shouldBe "{title}"
        KomgaChapterNameTemplate.render("A ${'$'}{b} C", values) shouldBe "A {b} C"
    }

    @Test
    fun `text that is not a placeholder is left alone`() {
        KomgaChapterNameTemplate.render("Ch. {number} - {title} [x]", values) shouldBe
            "Ch. 3 - The Long Away [x]"
        // An opening brace with no closing one is literal text, not a broken placeholder.
        KomgaChapterNameTemplate.render("{title} {", values) shouldBe "The Long Away {"
    }

    @Test
    fun `an unknown placeholder makes the format invalid`() {
        KomgaChapterNameTemplate.isRenderable("{tile} - {number}") shouldBe false
        KomgaChapterNameTemplate.isRenderable("{title} - {number}") shouldBe true
    }

    @Test
    fun `an escaped unknown brace stays valid`() {
        KomgaChapterNameTemplate.isRenderable("${'$'}{tile} - {title}") shouldBe true
    }

    @Test
    fun `a blank format is not renderable`() {
        KomgaChapterNameTemplate.isRenderable("") shouldBe false
        KomgaChapterNameTemplate.isRenderable("   ") shouldBe false
    }

    @Test
    fun `an empty template renders empty rather than throwing`() {
        KomgaChapterNameTemplate.render("", values) shouldBe ""
    }

    @Test
    fun `a placeholder the caller did not fill stays visible`() {
        // A hand-edited preference can name a placeholder the caller did not supply; showing the
        // brace text is more honest than silently printing nothing.
        KomgaChapterNameTemplate.render("{title} {releaseDate}", mapOf("title" to "A")).shouldContain("{releaseDate}")
    }
}
