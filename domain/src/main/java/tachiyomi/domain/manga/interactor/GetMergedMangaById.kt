package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaMergeRepository

@Inject
class GetMergedMangaById(
    private val mangaMergeRepository: MangaMergeRepository,
) {

    suspend fun await(id: Long): List<Manga> {
        return try {
            mangaMergeRepository.getMergedMangaById(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    suspend fun subscribe(id: Long): Flow<List<Manga>> {
        return mangaMergeRepository.subscribeMergedMangaById(id)
    }

    /**
     * The merged children of several entries, in as few queries as SQLite's bind limit allows.
     *
     * Callers that hold a page of library entries should use this instead of looping over
     * [await]: each [await] is one database dispatch, so a 500-entry library meant 500 of them
     * before the filter pass even started. Ids are deduplicated first, and one failed chunk is
     * logged and skipped rather than discarding the chunks that succeeded.
     */
    suspend fun awaitAll(ids: List<Long>): Map<Long, List<Manga>> {
        if (ids.isEmpty()) return emptyMap()
        val merged = mutableMapOf<Long, List<Manga>>()
        ids.distinct().chunked(MAX_IDS_PER_QUERY).forEach { chunk ->
            try {
                merged.putAll(mangaMergeRepository.getMergedMangaByIds(chunk))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
        return merged
    }

    companion object {
        /**
         * SQLite's default host-parameter limit is 999; leaving headroom keeps the chunk below it
         * on builds that reserve a few slots for other bindings in the same statement.
         */
        internal const val MAX_IDS_PER_QUERY = 900
    }
}
