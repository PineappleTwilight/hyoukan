package eu.kanade.tachiyomi.data.export

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.feature.libraryimport.data.AniyomiLibraryImporter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Kotatsu-family conversion behind #106: what Hyoukan writes must restore in Kototoro and
 * Usagi, and what they write must become import entries without losing the reader's progress.
 */
@Execution(ExecutionMode.CONCURRENT)
class LibraryInteropFormatTest {

    private val books = listOf(
        LibraryInteropFormat.Book(
            id = 1,
            title = "First Series",
            url = "/series/1",
            source = "42",
            author = "Writer",
            coverUrl = "https://example.com/1.jpg",
            tags = listOf("Action", "Comedy"),
            state = "ongoing",
            categoryIds = listOf(10, 11),
            history = LibraryInteropFormat.History(
                chapterNumber = 12.0,
                totalChapters = 30,
                percent = 1.0,
                readAt = 1_700_000_000_000,
                updatedAt = 1_700_000_100_000,
            ),
        ),
        LibraryInteropFormat.Book(
            id = 2,
            title = "Second Series",
            url = "/series/2",
            source = "42",
            author = "Other",
            coverUrl = "https://example.com/2.jpg",
            tags = emptyList(),
            state = "finished",
        ),
    )

    private val categories = listOf(
        LibraryInteropFormat.Category(id = 10, name = "Reading", order = 0),
        LibraryInteropFormat.Category(id = 11, name = "Favourites", order = 1),
    )

    @Test
    fun `an export decodes back to the same library`() {
        val bytes = LibraryInteropFormat.encode(
            target = LibraryInteropFormat.Target.USAGI,
            appVersion = 123,
            books = books,
            categories = categories,
            now = 99,
        )

        val decoded = LibraryInteropFormat.decode(bytes)

        decoded.appId shouldBe LibraryInteropFormat.USAGI_APP_ID
        decoded.books.size shouldBe 2
        val first = decoded.books.first { it.title == "First Series" }
        first.url shouldBe "/series/1"
        first.source shouldBe "42"
        first.author shouldBe "Writer"
        first.tags shouldBe listOf("Action", "Comedy")
        first.state shouldBe "ongoing"
        // One favourite row per category membership, merged back into a single entry.
        first.categoryIds.toSet() shouldBe setOf(10L, 11L)
        first.history?.chapterNumber shouldBe 12.0
        first.history?.totalChapters shouldBe 30
    }

    @Test
    fun `import entries carry progress as a chapter number`() {
        val bytes = LibraryInteropFormat.encode(
            target = LibraryInteropFormat.Target.KOTOTORO,
            appVersion = 1,
            books = books,
            categories = categories,
            now = 0,
        )

        val entries = LibraryInteropFormat.decode(bytes).toImportEntries()

        entries.size shouldBe 2
        val first = entries.first { it.title == "First Series" }
        first.progressChapter shouldBe 12.0
        first.totalChapters shouldBe 30L
        first.tags shouldBe listOf("Action", "Comedy")
        first.id shouldBe "42:/series/1"
        entries.map { it.id }.toSet().size shouldBe 2
    }

    @Test
    fun `a foreign archive with unknown sections still imports`() {
        val bytes = zipOf(
            "index" to """[{"app_id":"org.skepsun.kototoro","app_version":5,"created_at":1}]""",
            "favourites" to """
                [
                  {"manga_id":7,"category_id":1,"created_at":1,
                   "manga":{"id":7,"title":"Their Series","url":"/their/7","source":"web:1",
                            "author":"A","cover_url":"","state":"finished",
                            "tags":[{"id":1,"title":"Fantasy","key":"fantasy","source":"web:1"}]}},
                  {"manga_id":7,"category_id":2,"created_at":1,
                   "manga":{"id":7,"title":"Their Series","url":"/their/7","source":"web:1",
                            "author":"A","cover_url":"","state":"finished","tags":[]}}
                ]
            """.trimIndent(),
            "history" to """
                [{"manga_id":7,"created_at":5,"updated_at":9,"chapter_id":77,"page":3,
                  "percent":0.5,"chapters":20,
                  "manga":{"id":7,"title":"Their Series","url":"/their/7","source":"web:1"}}]
            """.trimIndent(),
            "bookmarks" to "[]",
            "statistics" to "[]",
        )

        val decoded = LibraryInteropFormat.decode(bytes)
        val entry = decoded.toImportEntries().single()

        entry.title shouldBe "Their Series"
        entry.tags shouldBe listOf("Fantasy")
        entry.totalChapters shouldBe 20L
        // 20 chapters recorded, half of the last one read -> chapter 19.5.
        entry.progressChapter shouldBe 19.5
    }

    @Test
    fun `a file without a favourites section is rejected`() {
        val bytes = zipOf("index" to "[]")
        runCatching { LibraryInteropFormat.decode(bytes) }.exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `an Aniyomi backup yields both manga and anime entries`() {
        val backup = TestAniyomiBackup(
            manga = listOf(
                TestAniyomiEntry(
                    source = 11,
                    url = "/manga/1",
                    title = "Manga One",
                    genre = listOf("Action"),
                    tracking = listOf(
                        TestAniyomiTracking(syncId = 2, mediaId = 4242, lastEpisodeSeen = 5f, score = 8f),
                    ),
                ),
            ),
            anime = listOf(
                TestAniyomiEntry(source = 22, url = "/anime/1", title = "Anime One", genre = listOf("Drama")),
            ),
        )
        val protobuf = ProtoBuf.encodeToByteArray(TestAniyomiBackup.serializer(), backup)
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(protobuf) }
        }.toByteArray()

        val entries = AniyomiLibraryImporter().parse(gzipped)

        entries.size shouldBe 2
        val manga = entries.first { it.type == "manga" }
        manga.title shouldBe "Manga One"
        manga.trackingLinks.single().remoteId shouldBe 4242L
        manga.trackingLinks.single().progressChapter shouldBe 5.0
        entries.first { it.type == "anime" }.title shouldBe "Anime One"
    }

    @Test
    fun `an empty Aniyomi backup is not accepted as an import`() {
        val protobuf = ProtoBuf.encodeToByteArray(TestAniyomiBackup.serializer(), TestAniyomiBackup())
        runCatching { AniyomiLibraryImporter().parse(protobuf) }.exceptionOrNull()
            .shouldBeInstanceOf<IllegalArgumentException>()
    }

    @Test
    fun `zip detection does not mistake a protobuf for an archive`() {
        LibraryInteropFormat.isZip(byteArrayOf(0x1f, 0x8b.toByte())) shouldBe false
        LibraryInteropFormat.isZip(zipOf("fav" to "[]")) shouldBe true
        LibraryInteropFormat.decode(zipOf("favourites" to "[]")).books shouldBe emptyList()
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // Mirrors the Aniyomi message numbers the importer declares; the importer's own DTOs are
    // private so the fixture is written independently on purpose.
    @Serializable
    private data class TestAniyomiBackup(
        @ProtoNumber(1) val manga: List<TestAniyomiEntry> = emptyList(),
        @ProtoNumber(501) val anime: List<TestAniyomiEntry> = emptyList(),
    )

    @Serializable
    private data class TestAniyomiEntry(
        @ProtoNumber(1) val source: Long = 0,
        @ProtoNumber(2) val url: String = "",
        @ProtoNumber(3) val title: String = "",
        @ProtoNumber(7) val genre: List<String> = emptyList(),
        @ProtoNumber(18) val tracking: List<TestAniyomiTracking> = emptyList(),
    )

    @Serializable
    private data class TestAniyomiTracking(
        @ProtoNumber(1) val syncId: Int = 0,
        @ProtoNumber(4) val trackingUrl: String = "",
        @ProtoNumber(6) val lastEpisodeSeen: Float = 0f,
        @ProtoNumber(8) val score: Float = 0f,
        @ProtoNumber(100) val mediaId: Long = 0,
    )
}
