package eu.kanade.tachiyomi.data.export

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.importer.model.ImportEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reading and writing the Kotatsu-family backup format (Kototoro and Usagi).
 *
 * Both projects descend from Kotatsu and never changed the wire format: a ZIP whose entries are
 * JSON arrays, one per section. The export writes the sections a foreign app can actually use -
 * index, categories, favourites, history and sources - and the reader tolerates the sections it
 * does not know about (bookmarks, statistics, scrobbling, settings), because those are the same
 * names in Kototoro and Usagi today and adding one must not make an otherwise readable backup
 * unreadable.
 *
 * A favourite in this format is *one row per category membership*, so a series in three
 * categories appears three times with the same manga body. The reader merges those rows by manga
 * identity and unions the categories, which is also why exporting twice never doubles an import
 * session: the entry ids are derived from the manga identity, not the row order.
 *
 * This file is deliberately free of Android and database types so the whole conversion can be
 * asserted in a JVM test.
 */
object LibraryInteropFormat {

    const val KOTOTORO_APP_ID = "org.skepsun.kototoro"
    const val USAGI_APP_ID = "org.draken.usagi"

    /** Which foreign app an export is meant for. Kototoro reads the Usagi dialect too. */
    enum class Target(val appId: String, val fileName: String) {
        KOTOTORO(KOTOTORO_APP_ID, "hyoukan_kototoro.bk.zip"),
        USAGI(USAGI_APP_ID, "hyoukan_usagi.bk.zip"),
    }

    /** One book as the format carries it. */
    data class Book(
        val id: Long,
        val title: String,
        val url: String,
        val source: String,
        val author: String? = null,
        val description: String? = null,
        val coverUrl: String? = null,
        val largeCoverUrl: String? = null,
        val tags: List<String> = emptyList(),
        val state: String? = null,
        val contentType: String = "manga",
        val publicUrl: String? = null,
        val categoryIds: List<Long> = emptyList(),
        /** Last read position inside the reading session, if the library has one. */
        val history: History? = null,
    )

    data class History(
        /** Chapter the reader stopped at, as a chapter number rather than a foreign row id. */
        val chapterNumber: Double,
        val totalChapters: Int,
        val percent: Double,
        val readAt: Long,
        val updatedAt: Long,
    )

    data class Category(
        val id: Long,
        val name: String,
        val order: Long,
    )

    /** A book plus the categories it belongs to, as read back from an archive. */
    data class DecodedLibrary(
        val appId: String?,
        val books: List<Book>,
    ) {
        fun toImportEntries(): List<ImportEntry> = books.map { book ->
            ImportEntry(
                id = listOf(book.source, book.url).joinToString(":").ifBlank { book.title },
                title = book.title,
                author = book.author,
                description = book.description,
                coverUrl = book.coverUrl,
                tags = book.tags,
                status = book.state,
                type = book.contentType,
                totalChapters = maxOf(
                    book.history?.totalChapters?.toLong() ?: 0L,
                    book.history?.chapterNumber?.toLong() ?: 0L,
                ),
                progressChapter = book.history?.let { history ->
                    // Our own exports name the chapter the reader stopped at. A foreign archive has
                    // no such field, so the last chapter count and the fraction read inside it are
                    // the only progress signal Kotatsu-family backups carry.
                    when {
                        history.chapterNumber > 0.0 -> history.chapterNumber
                        history.totalChapters <= 0 -> 0.0
                        else -> (history.totalChapters - 1).coerceAtLeast(0) + history.percent.coerceIn(0.0, 1.0)
                    }
                } ?: 0.0,
                seriesUrl = book.publicUrl ?: book.url,
                extra = buildMap {
                    put("source", book.source)
                    put("url", book.url)
                },
            )
        }.distinctBy { it.id }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Serialises [books] and [categories] as a ZIP both Kototoro and Usagi can restore.
     *
     * History is written only for books that carry it: a row without progress is indistinguishable
     * from "never read" to the receiving app and would overwrite nothing, but it would still add a
     * history entry to its screen.
     */
    fun encode(
        target: Target,
        appVersion: Long,
        books: List<Book>,
        categories: List<Category>,
        now: Long = System.currentTimeMillis(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.writeSection(
                "index",
                listOf(IndexRow(appId = target.appId, appVersion = appVersion, createdAt = now)),
                IndexRow.serializer(),
            )
            zip.writeSection("categories", categories.map { it.toRow(now) }, CategoryRow.serializer())
            zip.writeSection("favourites", books.flatMap { it.toFavouriteRows(now) }, FavouriteRow.serializer())
            zip.writeSection("history", books.mapNotNull { it.toHistoryRow() }, HistoryRow.serializer())
            zip.writeSection("sources", books.sourceRows(), SourceRow.serializer())
        }
        return out.toByteArray()
    }

    /** Reads any Kotatsu-family archive, ignoring sections this app does not consume. */
    fun decode(bytes: ByteArray): DecodedLibrary {
        var appId: String? = null
        var sawFavourites = false
        val favouriteRows = mutableListOf<FavouriteRow>()
        val historyByManga = mutableMapOf<Long, HistoryRow>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val section = entry.name.substringAfterLast('/').lowercase()
                when (section) {
                    "index" -> appId = zip.readJsonArray(IndexRow.serializer()).firstOrNull()?.appId
                    "favourites" -> {
                        sawFavourites = true
                        favouriteRows += zip.readJsonArray(FavouriteRow.serializer())
                    }

                    "history" -> zip.readJsonArray(HistoryRow.serializer())
                        .forEach { historyByManga[it.mangaId] = it }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!sawFavourites) {
            throw IllegalArgumentException("Not a Kotatsu-family backup: no favourites section")
        }
        val books = favouriteRows
            .groupBy { it.manga.id to it.manga.source }
            .mapNotNull { (_, rows) ->
                val manga = rows.first().manga
                if (manga.title.isBlank() || manga.url.isBlank()) return@mapNotNull null
                Book(
                    id = manga.id,
                    title = manga.title,
                    url = manga.url,
                    source = manga.source,
                    author = manga.author,
                    coverUrl = manga.coverUrl.takeIf { it.isNotBlank() },
                    largeCoverUrl = manga.largeCoverUrl,
                    tags = manga.tags.map { it.title }.filter { it.isNotBlank() },
                    state = manga.state,
                    contentType = manga.contentType ?: "manga",
                    publicUrl = manga.publicUrl.takeIf { it.isNotBlank() },
                    categoryIds = rows.map { it.categoryId }.distinct(),
                    history = historyByManga[manga.id]?.toHistory(),
                )
            }
        return DecodedLibrary(appId = appId, books = books)
    }

    /** True when this byte stream starts like a ZIP archive. */
    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun Book.toFavouriteRows(now: Long): List<FavouriteRow> {
        val categoryIds = categoryIds.ifEmpty { listOf(0L) }
        return categoryIds.map { categoryId ->
            FavouriteRow(
                mangaId = id,
                categoryId = categoryId,
                sortKey = 0,
                pinned = false,
                createdAt = now,
                manga = ContentRow(
                    id = id,
                    title = title,
                    url = url,
                    publicUrl = publicUrl ?: url,
                    coverUrl = coverUrl.orEmpty(),
                    largeCoverUrl = largeCoverUrl,
                    state = state,
                    author = author,
                    source = source,
                    contentType = contentType,
                    tags = tags.mapIndexed { index, tag ->
                        TagRow(id = index.toLong(), title = tag, key = tag, source = source)
                    },
                ),
            )
        }
    }

    private fun Book.toHistoryRow(): HistoryRow? = history?.let {
        HistoryRow(
            mangaId = id,
            createdAt = it.readAt,
            updatedAt = it.updatedAt,
            page = 0,
            percent = it.percent.toFloat(),
            chaptersCount = it.totalChapters,
            chapterNumber = it.chapterNumber,
            manga = ContentRow(
                id = id,
                title = title,
                url = url,
                publicUrl = publicUrl ?: url,
                coverUrl = coverUrl.orEmpty(),
                source = source,
                author = author,
            ),
        )
    }

    private fun List<Book>.sourceRows(): List<SourceRow> =
        map { it.source }
            .distinct()
            .mapIndexed { index, source ->
                SourceRow(source = source, sortKey = index)
            }

    private fun Category.toRow(now: Long) = CategoryRow(
        categoryId = id,
        createdAt = now,
        sortKey = order.toInt(),
        title = name,
    )

    private fun HistoryRow.toHistory() = History(
        chapterNumber = chapterNumber,
        totalChapters = chaptersCount,
        percent = percent.toDouble(),
        readAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun <T> ZipOutputStream.writeSection(name: String, rows: List<T>, serializer: kotlinx.serialization.KSerializer<T>) {
        putNextEntry(ZipEntry(name))
        val listSerializer = ListSerializer(serializer)
        write(json.encodeToString(listSerializer, rows).toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun <T> ZipInputStream.readJsonArray(serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val bytes = readBytes()
        if (bytes.isEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(serializer), bytes.toString(Charsets.UTF_8))
        }.getOrElse {
            // A section written as a single object (older Kotatsu exports did) is accepted too.
            listOf(json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)))
        }
    }

    // ---- wire rows -------------------------------------------------------------------------

    @Serializable
    private data class IndexRow(
        @SerialName("app_id") val appId: String,
        @SerialName("app_version") val appVersion: Long = 0,
        @SerialName("created_at") val createdAt: Long = 0,
    )

    @Serializable
    private data class CategoryRow(
        @SerialName("category_id") val categoryId: Long,
        @SerialName("created_at") val createdAt: Long = 0,
        @SerialName("sort_key") val sortKey: Int = 0,
        @SerialName("title") val title: String,
        @SerialName("order") val order: String = "NEWEST",
        @SerialName("track") val track: Boolean = true,
        @SerialName("show_in_lib") val showInLibrary: Boolean = true,
    )

    @Serializable
    private data class TagRow(
        @SerialName("id") val id: Long = 0,
        @SerialName("title") val title: String,
        @SerialName("key") val key: String = "",
        @SerialName("source") val source: String = "",
        @SerialName("pinned") val pinned: Boolean = false,
    )

    @Serializable
    private data class ContentRow(
        @SerialName("id") val id: Long = 0,
        @SerialName("title") val title: String,
        @SerialName("url") val url: String,
        @SerialName("public_url") val publicUrl: String = "",
        @SerialName("rating") val rating: Float = -1f,
        @SerialName("nsfw") val nsfw: Boolean = false,
        @SerialName("cover_url") val coverUrl: String = "",
        @SerialName("large_cover_url") val largeCoverUrl: String? = null,
        @SerialName("state") val state: String? = null,
        @SerialName("author") val author: String? = null,
        @SerialName("source") val source: String,
        @SerialName("content_type") val contentType: String? = null,
        @SerialName("tags") val tags: List<TagRow> = emptyList(),
    )

    @Serializable
    private data class FavouriteRow(
        @SerialName("manga_id") val mangaId: Long,
        @SerialName("category_id") val categoryId: Long = 0,
        @SerialName("sort_key") val sortKey: Int = 0,
        @SerialName("pinned") val pinned: Boolean = false,
        @SerialName("created_at") val createdAt: Long = 0,
        @SerialName("manga") val manga: ContentRow,
    )

    @Serializable
    private data class HistoryRow(
        @SerialName("manga_id") val mangaId: Long,
        @SerialName("created_at") val createdAt: Long = 0,
        @SerialName("updated_at") val updatedAt: Long = 0,
        @SerialName("chapter_id") val chapterId: Long = 0,
        @SerialName("page") val page: Int = 0,
        @SerialName("scroll") val scroll: Float = 0f,
        @SerialName("percent") val percent: Float = 0f,
        @SerialName("chapters") val chaptersCount: Int = 0,
        /** Hyoukan extension: the chapter number the reader stopped at (ignored by other apps). */
        @SerialName("chapter") val chapterNumber: Double = 0.0,
        @SerialName("manga") val manga: ContentRow,
    )

    @Serializable
    private data class SourceRow(
        @SerialName("source") val source: String,
        @SerialName("sort_key") val sortKey: Int = 0,
        @SerialName("used_at") val lastUsedAt: Long = 0,
        @SerialName("added_in") val addedIn: Int = 1,
        @SerialName("pinned") val pinned: Boolean = false,
        @SerialName("enabled") val enabled: Boolean = true,
    )
}
