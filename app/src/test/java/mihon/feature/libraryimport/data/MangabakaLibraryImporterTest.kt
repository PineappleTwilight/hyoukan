package mihon.feature.libraryimport.data

import eu.kanade.tachiyomi.data.track.core.TrackerId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.backup.service.BackupPreferences

@Execution(ExecutionMode.CONCURRENT)
class MangabakaLibraryImporterTest {

    private val importer = MangabakaLibraryImporter()

    /** The importer now reads bytes so the same interface can carry ZIP archives (#106). */
    private fun MangabakaLibraryImporter.parse(text: String) = parse(text.toByteArray())

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
    fun `reads the schema version 2 site export`() {
        val entries = importer.parse(SCHEMA_V2_EXPORT)

        entries.size shouldBe 2
        val first = entries.first { it.title == "The Legendary Hero is an Academy Honors Student" }
        first.id shouldBe "376205"
        first.progressChapter shouldBe 11.0
        first.status shouldBe null
        first.totalChapters shouldBe 0L
        first.seriesUrl shouldBe "https://mangabaka.org/376205"

        val link = first.trackingLinks.single()
        link.trackerId shouldBe TrackerId.MANGABAKA
        link.remoteId shouldBe 376205L
        link.remoteUrl shouldBe "https://mangabaka.org/376205"
        link.status shouldBe "reading"
        link.score shouldBe 100.0
        link.progressChapter shouldBe 11.0
        link.startDate shouldBe "2026-07-04T00:00:00.000Z"
        link.isPrivate shouldBe false
    }

    @Test
    fun `a schema version 2 export keeps entries distinct by their mangabaka id`() {
        // The site export can hold two entries with the same title (different series), and
        // `source.mangabaka` is the only id that tells them apart.
        val entries = importer.parse(SCHEMA_V2_EXPORT)

        entries.map { it.id } shouldBe listOf("376205", "37387")
        entries.map { it.trackingLinks.single().remoteId } shouldBe listOf(376205L, 37387L)
    }

    @Test
    fun `a titles object falls back from primary to romanized`() {
        val entries = importer.parse(
            """
            { "entries": [
              { "entry": { "state": "reading" },
                "source": { "mangabaka": 7 },
                "titles": { "primary": null, "romanized": "Romaji Name", "native": "ネイティブ" } },
              { "entry": { "state": "reading" },
                "source": { "mangabaka": 8 },
                "titles": { "primary": "", "romanized": null, "native": "ネイティブ" } }
            ] }
            """.trimIndent(),
        )

        entries[0].title shouldBe "Romaji Name"
        // An empty primary is not a title; the native one is the last fallback.
        entries[1].title shouldBe "ネイティブ"
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
        // The site's current download name.
        importer.matchesFileName("mangabaka-library-export-2026-11-26T20-00-36Z.json") shouldBe true
        importer.matchesFileName("backup.csv") shouldBe false
        importer.matchesFileName(null) shouldBe false
    }

    @Test
    fun `the adapter is registered and reachable by id`() {
        val adapters = LibraryImportAdapters(
            mangabaka = importer,
            kototoro = KototoroLibraryImporter(),
            usagi = UsagiLibraryImporter(),
            aniyomi = AniyomiLibraryImporter(),
            backupPreferences = mockk<BackupPreferences> {
                every { libraryInterop().get() } returns false
            },
        )

        adapters.byId(MangabakaLibraryImporter.ID).shouldNotBeNull()
        adapters.all.size shouldBe 1
    }

    @Test
    fun `interop adapters only appear while the interoperability switch is on`() {
        val preference = mockk<BackupPreferences>()
        every { preference.libraryInterop().get() } returns true
        val adapters = LibraryImportAdapters(
            mangabaka = importer,
            kototoro = KototoroLibraryImporter(),
            usagi = UsagiLibraryImporter(),
            aniyomi = AniyomiLibraryImporter(),
            backupPreferences = preference,
        )

        adapters.all.map { it.id } shouldBe listOf("mangabaka", "kototoro", "usagi", "aniyomi")
    }

    private companion object {
        /**
         * The site's `schema_version: 2` export: `titles` is an object and the series id lives in
         * `source.mangabaka`, not in a `series` object. Trimmed from a real export
         * (`mangabaka-library-export-2026-11-26T20-00-36Z.json`), which is what failed with
         * `Expected JsonArray, but had JsonObject ... at path: $.titles`.
         */
        val SCHEMA_V2_EXPORT = """
            {
              "schema_version": 2,
              "exported_at": "2026-11-26T20:00:36.626Z",
              "lists": [{ "id": 5755, "name": "Dropped" }],
              "entries": [
                {
                  "entry": {
                    "note": null, "read_link": null, "rating": 100, "state": "reading",
                    "priority": 20, "is_private": false, "number_of_rereads": null,
                    "progress_chapter": 11, "progress_volume": null,
                    "start_date": "2026-07-04T00:00:00.000Z", "finish_date": null,
                    "imported_at": null, "created_at": "2026-07-05T18:19:01.058Z",
                    "updated_at": "2026-07-05T18:19:08.336Z"
                  },
                  "source": {
                    "anilist": null, "anime_news_network": null, "kitsu": null,
                    "manga_updates": null, "mangabaka": 376205, "my_anime_list": null,
                    "shikimori": null
                  },
                  "titles": {
                    "primary": "The Legendary Hero is an Academy Honors Student",
                    "native": "전설급 영웅은 아카데미 우등생",
                    "romanized": null
                  },
                  "lists": []
                },
                {
                  "entry": {
                    "note": null, "read_link": null, "rating": 85, "state": "completed",
                    "priority": 20, "is_private": false, "number_of_rereads": null,
                    "progress_chapter": 1, "progress_volume": null,
                    "start_date": "2026-07-06T00:00:00.000Z", "finish_date": null,
                    "imported_at": null, "created_at": "2026-07-07T16:15:23.781Z",
                    "updated_at": "2026-10-18T23:42:11.746Z"
                  },
                  "source": {
                    "anilist": 88868, "anime_news_network": null, "kitsu": 25544,
                    "manga_updates": "w3icd9i", "mangabaka": 37387, "my_anime_list": null,
                    "shikimori": null
                  },
                  "titles": {
                    "primary": "The Lonely Princess",
                    "native": "ひとりぼっちのお姫様",
                    "romanized": "Hitoribocchi no Ohimesama"
                  },
                  "lists": []
                }
              ]
            }
        """.trimIndent()

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
