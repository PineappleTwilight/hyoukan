package eu.kanade.tachiyomi.data.export

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import java.io.IOException

/**
 * Writes the library as a Kotatsu-family backup (#106).
 *
 * Kototoro and Usagi both restore the same ZIP-of-JSON archives Kotatsu introduced, so one
 * exporter serves both: only the `index` app id differs, which is what stops a receiving app from
 * mistaking the file for one of its own older exports.
 *
 * What travels: the library entries with their source, url, cover, author, genres and categories,
 * plus the last-read chapter for entries that have been read. What cannot travel: tracker
 * credentials and app settings - exporting them would hand another app a token it cannot use.
 */
@SingleIn(AppScope::class)
@Inject
class LibraryInteropExporter(
    private val context: Context,
    private val getFavorites: GetFavorites,
    private val getCategories: GetCategories,
    private val mangaRepository: MangaRepository,
) {

    data class Summary(
        val exportedEntries: Int,
        val exportedCategories: Int,
    )

    sealed interface Result {
        data class Success(val summary: Summary) : Result
        data class Failed(val cause: Throwable) : Result
        data object EmptyLibrary : Result
    }

    suspend fun export(target: LibraryInteropFormat.Target, uri: Uri): Result = withContext(Dispatchers.IO) {
        val mangas = getFavorites.await()
        if (mangas.isEmpty()) return@withContext Result.EmptyLibrary

        try {
            val backupOptions = BackupOptions(
                libraryEntries = true,
                categories = true,
                chapters = true,
                tracking = false,
                history = true,
                readEntries = true,
                appSettings = false,
                extensionStores = false,
                sourceSettings = false,
                privateSettings = false,
                customInfo = false,
                savedSearchesFeeds = false,
                customCovers = false,
            )
            val backupMangas = MangaBackupCreator()(mangas, backupOptions)
            val categories = getCategories.await()
            val memberships = mangaRepository.getFavoriteCategoryPairs()
            val books = toBooks(mangas, backupMangas, memberships)
            val bytes = LibraryInteropFormat.encode(
                target = target,
                appVersion = BuildConfig.VERSION_CODE.toLong(),
                books = books,
                categories = categories.map { LibraryInteropFormat.Category(it.id, it.name, it.order) },
            )
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw IOException("Could not open the picked file for writing")
            stream.use { it.write(bytes) }
            Result.Success(Summary(exportedEntries = books.size, exportedCategories = categories.size))
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Interop export to $target failed" }
            Result.Failed(e)
        }
    }

    private fun toBooks(
        mangas: List<Manga>,
        backupMangas: List<BackupManga>,
        memberships: Map<Long, Set<Long>>,
    ): List<LibraryInteropFormat.Book> {
        // MangaBackupCreator maps asynchronously but awaits in list order, so index i of the two
        // lists is the same library entry. Deriving the id from the Manga rather than the backup
        // body keeps the favourite rows joinable.
        return mangas.mapIndexed { index, manga ->
            val backup = backupMangas.getOrNull(index)
            LibraryInteropFormat.Book(
                id = manga.id,
                title = manga.title,
                url = manga.url,
                source = manga.source.toString(),
                author = manga.author,
                description = manga.description,
                coverUrl = manga.thumbnailUrl,
                tags = manga.genre.orEmpty(),
                state = backupState(manga.status),
                contentType = if (manga.isLightNovel) "novel" else "manga",
                publicUrl = manga.url,
                categoryIds = memberships[manga.id]?.toList().orEmpty(),
                history = backup?.toHistory(),
            )
        }
    }

    private fun BackupManga.toHistory(): LibraryInteropFormat.History? {
        val lastReadChapter = chapters
            .filter { it.read || it.lastPageRead > 0 }
            .maxByOrNull { it.chapterNumber }
        val lastHistory = history.maxByOrNull { it.lastRead }
        if (lastReadChapter == null && lastHistory == null) return null
        val readAt = lastHistory?.lastRead?.takeIf { it > 0 } ?: lastModifiedAt
        return LibraryInteropFormat.History(
            chapterNumber = lastReadChapter?.chapterNumber?.toDouble() ?: 0.0,
            totalChapters = chapters.size,
            // A chapter counts as fully read once its "read" flag is on. Kotatsu records the
            // fraction inside the chapter, which this app does not track, so an unfinished
            // chapter is exported as not read rather than as a made-up percentage.
            percent = if (lastReadChapter?.read == true) 1.0 else 0.0,
            readAt = readAt,
            updatedAt = maxOf(readAt, lastModifiedAt, lastHistory?.lastRead ?: 0L),
        )
    }

    private fun backupState(status: Long): String = when (status) {
        1L -> "ongoing"
        2L -> "finished"
        5L -> "abandoned"
        6L -> "paused"
        else -> "unknown"
    }
}
