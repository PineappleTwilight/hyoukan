package tachiyomi.domain.manga.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MergeMangaSettingsUpdate
import tachiyomi.domain.manga.model.MergedMangaReference

interface MangaMergeRepository {
    suspend fun getMergedManga(): List<Manga>

    suspend fun subscribeMergedManga(): Flow<List<Manga>>

    suspend fun getMergedMangaById(id: Long): List<Manga>

    /**
     * Merged children for several merge entries at once.
     *
     * The library filters, bulk downloads and bulk deletes all ask "which manga does this merged
     * entry consist of?" for a page-sized list of entries. Answering that per id is a database
     * dispatch per entry, so this returns the whole page in one call: `mergeId -> children`.
     * Entries without children are absent from the result, matching [getMergedMangaById]'s empty
     * list for the same id.
     */
    suspend fun getMergedMangaByIds(ids: List<Long>): Map<Long, List<Manga>>

    suspend fun subscribeMergedMangaById(id: Long): Flow<List<Manga>>

    suspend fun getReferencesById(id: Long): List<MergedMangaReference>

    suspend fun subscribeReferencesById(id: Long): Flow<List<MergedMangaReference>>

    suspend fun getReferencesByManga(mangaId: Long, mangaUrl: String, mangaSourceId: Long): List<MergedMangaReference>

    suspend fun updateSettings(update: MergeMangaSettingsUpdate): Boolean

    suspend fun updateAllSettings(values: List<MergeMangaSettingsUpdate>): Boolean

    suspend fun insert(reference: MergedMangaReference): Long?

    suspend fun insertAll(references: List<MergedMangaReference>)

    suspend fun deleteById(id: Long)

    suspend fun deleteByMergeId(mergeId: Long)

    suspend fun getMergeMangaForDownloading(mergeId: Long): List<Manga>
}
