package mihon.feature.libraryimport.data

import eu.kanade.tachiyomi.data.track.core.TrackerId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class MangabakaLibraryImporterTest {

    private val importer = MangabakaLibraryImporter()

    @Test
    fun `reads the library API shape with entry and series`() {
        val entries = importer.parse(LIBRARY_EXPORT)

        entries.size shouldBe 2
        val first = entries[0]
        first.id shouldBe "101"
        first.title shouldBe "The English Title"
        first.author shouldBe "Author One, Author Two"
        first.artist shouldBe "Artist One"
        first.description shouldBe "A description."
        first.coverUrl shouldBe "https://cdn.example/large.jpg"
        first.tags shouldBe listOf("Action", "Drama")
        first.status shouldBe "releasing"
        first.type shouldBe "manga"
        first.totalChapters shouldBe 72L
        first.progressChapter shouldBe 12.0
        first.progressVolume shouldBe 1.0
        first.rating shouldBe 85.0
        first.note shouldBe "a note"
        first.startDate shouldBe "2024-01-02"
        first.finishDate shouldBe null
    }

    @Test
    fun `the series id becomes a MangaBaka tracking link`() {
        val link = importer.parse(LIBRARY_EXPORT)[0].trackingLinks.single()

        link.trackerId shouldBe TrackerId.MANGABAKA
        link.remoteId shouldBe 101L
        link.remoteUrl shouldBe "https://mangabaka.org/101"
        link.status shouldBe "reading"
        link.score shouldBe 85.0
        link.progressChapter shouldBe 12.0
        link.startDate shouldBe "2024-01-02"
        link.isPrivate shouldBe true
    }

    @Test
    fun `title selection prefers english then native`() {
        val entries = importer.parse(LIBRARY_EXPORT)

        entries[0].title shouldBe "The English Title"
        // No english title in the second entry: the japanese one wins over the korean one.
        entries[1].title shouldBe "Japanese Title"
    }

    @Test
    fun `a bare array of entries is accepted`() {
        val entries = importer.parse(
            """
            [
              { "id": 5, "title": "Flat Array", "status": "completed", "type": "manga",
                "total_chapters": "10", "state": "completed", "progress_chapter": "10" }
            ]
            """.trimIndent(),
        )

        entries.single().title shouldBe "Flat Array"
        entries.single().id shouldBe "5"
        entries.single().totalChapters shouldBe 10L
        entries.single().progressChapter shouldBe 10.0
        entries.single().status shouldBe "completed"
    }

    @Test
    fun `a flat entry mixes the library row and the series`() {
        val entries = importer.parse(
            """
            { "data": [
              { "series_id": 900, "state": "plan_to_read", "progress_chapter": 0,
                "canonical_url": "https://example/series/900",
                "cover": { "raw": { "url": "https://cdn.example/900.jpg" } },
                "authors": ["Solo Author"], "status": "completed", "type": "manga",
                "titles": [{ "title": "Flat Mix", "language": "en", "traits": [] }],
                "total_chapters": 3 }
            ] }
            """.trimIndent(),
        )

        val entry = entries.single()
        entry.id shouldBe "900"
        entry.title shouldBe "Flat Mix"
        entry.author shouldBe "Solo Author"
        entry.coverUrl shouldBe "https://cdn.example/900.jpg"
        entry.seriesUrl shouldBe "https://example/series/900"
        entry.trackingLinks.single().remoteUrl shouldBe "https://example/series/900"
        entry.trackingLinks.single().status shouldBe "plan_to_read"
    }

    @Test
    fun `entries without a usable title are dropped`() {
        val entries = importer.parse(
            """
            { "data": [
              { "state": "reading" },
              { "series": { "id": 1, "titles": [{ "title": "Kept", "language": "en", "traits": [] }],
                "status": "releasing", "type": "manga" },
                "entry": { "state": "reading" } }
            ] }
            """.trimIndent(),
        )

        entries.map { it.title } shouldBe listOf("Kept")
    }

    @Test
    fun `duplicate series ids collapse to one entry`() {
        val entries = importer.parse(
            """
            { "data": [
              { "series": { "id": 7, "titles": [{ "title": "Once", "language": "en", "traits": [] }],
                "status": "releasing", "type": "manga" }, "entry": { "state": "reading" } },
              { "series": { "id": 7, "titles": [{ "title": "Once", "language": "en", "traits": [] }],
                "status": "releasing", "type": "manga" }, "entry": { "state": "completed" } }
            ] }
            """.trimIndent(),
        )

        entries.size shouldBe 1
        entries.single().trackingLinks.single().status shouldBe "reading"
    }

    @Test
    fun `an empty export is an empty list, not an error`() {
        importer.parse("""{ "data": [] }""").shouldBeEmpty()
        importer.parse("[]").shouldBeEmpty()
    }

    @Test
    fun `a document that is not an export is rejected`() {
        shouldThrow<Exception> { importer.parse("""{ "unrelated": true }""") }
        shouldThrow<Exception> { importer.parse("not json") }
    }

    @Test
    fun `file names identify the export`() {
        importer.matchesFileName("mangabaka_library.json") shouldBe true
        importer.matchesFileName("MangaBaka-export.JSON") shouldBe true
        importer.matchesFileName("manga_baka_export.json") shouldBe true
        importer.matchesFileName("backup.csv") shouldBe false
        importer.matchesFileName(null) shouldBe false
    }

    @Test
    fun `the adapter is registered and reachable by id`() {
        val adapters = LibraryImportAdapters(importer)

        adapters.byId(MangabakaLibraryImporter.ID).shouldNotBeNull()
        adapters.all.size shouldBe 1
    }

    private companion object {
        val LIBRARY_EXPORT = """
            {
              "data": [
                {
                  "entry": {
                    "state": "reading",
                    "progress_chapter": 12,
                    "progress_volume": 1,
                    "rating": 85,
                    "note": "a note",
                    "is_private": true,
                    "start_date": "2024-01-02",
                    "series_id": 101
                  },
                  "series": {
                    "id": 101,
                    "canonical_url": "https://mangabaka.org/101",
                    "cover": {
                      "raw": { "url": "https://cdn.example/large.jpg" },
                      "x150": { "x1": "https://cdn.example/small.jpg" }
                    },
                    "authors": ["Author One", "Author Two"],
                    "artists": ["Artist One"],
                    "tags": ["Action", "Drama"],
                    "description": "A description.",
                    "status": "releasing",
                    "type": "manga",
                    "total_chapters": "72",
                    "titles": [
                      { "title": "Native Title", "language": "ja", "traits": ["native"] },
                      { "title": "The English Title", "language": "en", "traits": ["official"], "is_primary": true },
                      { "title": "Romaji Title", "language": "ja-Latn", "traits": [] }
                    ]
                  }
                },
                {
                  "entry": { "state": "completed", "progress_chapter": 40, "series_id": 202 },
                  "series": {
                    "id": 202,
                    "canonical_url": "https://mangabaka.org/202",
                    "cover": { "x250": { "x1": "https://cdn.example/202.jpg" } },
                    "authors": null,
                    "artists": null,
                    "tags": null,
                    "description": null,
                    "status": "completed",
                    "type": "novel",
                    "total_chapters": null,
                    "titles": [
                      { "title": "Korean Title", "language": "ko", "traits": [] },
                      { "title": "Japanese Title", "language": "ja", "traits": [] }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
