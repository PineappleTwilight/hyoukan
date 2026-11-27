package tachiyomi.domain.manga.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaMergeRepository

/**
 * The batched lookup behind the library's merged-entry prefetch (#97).
 *
 * The point of the interactor is to turn a page of ids into as few repository calls as SQLite's
 * bind limit allows, and to keep the successful chunks when one fails. A regression here is
 * invisible in behaviour - the library simply goes back to being slow - so the call count is the
 * assertion that matters, not just the returned map.
 */
@Execution(ExecutionMode.CONCURRENT)
class GetMergedMangaByIdTest {

    private val repository: MangaMergeRepository = mockk()
    private val getMergedMangaById = GetMergedMangaById(repository)

    private fun manga(id: Long) = Manga.create().copy(id = id)

    @Test
    fun `an empty id list never reaches the database`() = runTest {
        getMergedMangaById.awaitAll(emptyList()) shouldBe emptyMap()
        coVerify(exactly = 0) { repository.getMergedMangaByIds(any()) }
    }

    @Test
    fun `ids are de-duplicated before querying`() = runTest {
        coEvery { repository.getMergedMangaByIds(any()) } answers {
            firstArg<List<Long>>().associateWith { listOf(manga(it)) }
        }

        val result = getMergedMangaById.awaitAll(listOf(7L, 7L, 7L, 8L))

        result.keys shouldBe setOf(7L, 8L)
        coVerify(exactly = 1) { repository.getMergedMangaByIds(listOf(7L, 8L)) }
    }

    @Test
    fun `a long id list is split under the sqlite bind limit`() = runTest {
        coEvery { repository.getMergedMangaByIds(any()) } answers {
            firstArg<List<Long>>().associateWith { listOf(manga(it)) }
        }
        val ids = (1L..1000L).toList()

        val result = getMergedMangaById.awaitAll(ids)

        result.size shouldBe 1000
        coVerify(exactly = 2) { repository.getMergedMangaByIds(any()) }
        coVerify(exactly = 1) {
            repository.getMergedMangaByIds(match { it.size == GetMergedMangaById.MAX_IDS_PER_QUERY })
        }
        coVerify(exactly = 1) {
            repository.getMergedMangaByIds(
                match { it.size == 1000 - GetMergedMangaById.MAX_IDS_PER_QUERY },
            )
        }
    }

    @Test
    fun `the chunk size stays below the 999 parameter limit`() {
        (GetMergedMangaById.MAX_IDS_PER_QUERY < 999) shouldBe true
    }

    @Test
    fun `one failed chunk does not discard the successful ones`() = runTest {
        coEvery {
            repository.getMergedMangaByIds(match { it.size == GetMergedMangaById.MAX_IDS_PER_QUERY })
        } returns mapOf(1L to listOf(manga(1L)))
        coEvery {
            repository.getMergedMangaByIds(match { it.size == 1 })
        } throws IllegalStateException("boom")

        val result = getMergedMangaById.awaitAll((1L..(GetMergedMangaById.MAX_IDS_PER_QUERY + 1L)).toList())

        result shouldBe mapOf(1L to listOf(manga(1L)))
    }

    @Test
    fun `entries without children are absent rather than empty lists`() = runTest {
        // Callers read the map with .orEmpty(); carrying an empty list per childless entry would
        // make the map grow with the library for no benefit.
        coEvery { repository.getMergedMangaByIds(any()) } returns emptyMap()

        getMergedMangaById.awaitAll(listOf(1L, 2L, 3L)) shouldBe emptyMap()
    }
}
