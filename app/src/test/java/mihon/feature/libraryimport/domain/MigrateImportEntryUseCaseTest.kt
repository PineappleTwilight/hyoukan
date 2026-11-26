package mihon.feature.libraryimport.domain

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.core.TrackerId
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.source.models.RemoteMangaUpdate
import mihon.feature.libraryimport.model.ImportMigrateFlag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.domain.importer.model.ImportTrackingLink
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

/**
 * What an import entry carries over into the library entry it becomes.
 *
 * The interesting parts are the guards: a source that cannot be read must not leave a favorited
 * shell behind, an established tracker binding must not be replaced by an older export, and
 * progress must never mark a chapter the source cannot number.
 */
@Execution(ExecutionMode.CONCURRENT)
class MigrateImportEntryUseCaseTest {

    private val updateMangaFromRemote = mockk<UpdateMangaFromRemote>()
    private val updateManga = mockk<UpdateManga>()
    private val getChaptersByMangaId = mockk<GetChaptersByMangaId>()
    private val updateChapter = mockk<UpdateChapter>()
    private val getTracks = mockk<GetTracks>()
    private val insertTrack = mockk<InsertTrack>()
    private val trackerManager = mockk<TrackerManager>()

    private val target = Manga.create().copy(id = 42L, source = 7L)

    private val useCase = MigrateImportEntryUseCase(
        updateMangaFromRemote = updateMangaFromRemote,
        updateManga = updateManga,
        getChaptersByMangaId = getChaptersByMangaId,
        updateChapter = updateChapter,
        getTracks = getTracks,
        insertTrack = insertTrack,
        trackerManager = trackerManager,
    )

    private fun entry(
        progress: Double = 0.0,
        note: String? = null,
        links: List<ImportTrackingLink> = emptyList(),
    ) = ImportEntry(
        id = "101",
        title = "Imported",
        totalChapters = 20L,
        progressChapter = progress,
        note = note,
        trackingLinks = links,
    )

    private fun stubRemoteUpdateSuccess() {
        coEvery {
            updateMangaFromRemote(
                any<Manga>(),
                any<Boolean>(),
                any<Boolean>(),
                any<Boolean>(),
                any<Pair<Long, Long>>(),
                any<suspend () -> Unit>(),
            )
        } returns Result.success(RemoteMangaUpdate(target, emptyList()))
    }

    private fun stubTracker(loggedIn: Boolean = true) {
        val tracker = mockk<Tracker>()
        every { tracker.id } returns TrackerId.MANGABAKA
        every { tracker.name } returns "MangaBaka"
        every { tracker.isLoggedIn } returns loggedIn
        every { tracker.getReadingStatus() } returns MangaBaka.READING
        every { tracker.getCompletionStatus() } returns MangaBaka.COMPLETED
        every { tracker.getRereadingStatus() } returns MangaBaka.REREADING
        every { tracker.getStatusList() } returns listOf(
            MangaBaka.READING,
            MangaBaka.COMPLETED,
            MangaBaka.PAUSED,
            MangaBaka.DROPPED,
            MangaBaka.PLAN_TO_READ,
            MangaBaka.REREADING,
            MangaBaka.CONSIDERING,
        )
        every { trackerManager.trackers } returns listOf(tracker)
    }

    @Test
    fun `a successful import favorites the target and stamps it now`() = runTest {
        stubRemoteUpdateSuccess()
        val update = slot<MangaUpdate>()
        coEvery { updateManga.await(capture(update)) } returns true

        val before = System.currentTimeMillis()
        val result = useCase(entry(), target, emptySet())

        result.shouldBeTrue()
        update.captured.id shouldBe target.id
        update.captured.favorite shouldBe true
        val dateAdded = update.captured.dateAdded ?: 0L
        (dateAdded != 0L).shouldBeTrue()
        (dateAdded >= before).shouldBeTrue()
    }

    @Test
    fun `an entry already in the library keeps its dateAdded`() = runTest {
        stubRemoteUpdateSuccess()
        val update = slot<MangaUpdate>()
        coEvery { updateManga.await(capture(update)) } returns true

        val alreadyFavorite = target.copy(favorite = true)
        useCase(entry(), alreadyFavorite, emptySet())

        update.captured.favorite shouldBe true
        update.captured.dateAdded shouldBe null
    }

    @Test
    fun `a failed remote fetch does not favorite anything`() = runTest {
        coEvery {
            updateMangaFromRemote(
                any<Manga>(),
                any<Boolean>(),
                any<Boolean>(),
                any<Boolean>(),
                any<Pair<Long, Long>>(),
                any<suspend () -> Unit>(),
            )
        } returns Result.failure(IllegalStateException("source gone"))

        val result = useCase(entry(), target, emptySet())

        result.shouldBeFalse()
        coVerify(exactly = 0) { updateManga.await(any()) }
    }

    @Test
    fun `the note is carried only when notes are selected`() = runTest {
        stubRemoteUpdateSuccess()
        val update = slot<MangaUpdate>()
        coEvery { updateManga.await(capture(update)) } returns true

        useCase(entry(note = "remember this"), target, setOf(ImportMigrateFlag.NOTES))
        update.captured.notes shouldBe "remember this"

        useCase(entry(note = "remember this"), target, emptySet())
        update.captured.notes shouldBe null
    }

    @Test
    fun `read progress marks every chapter up to the export's count`() = runTest {
        stubRemoteUpdateSuccess()
        coEvery { updateManga.await(any()) } returns true
        coEvery { getChaptersByMangaId.await(target.id) } returns listOf(
            chapter(id = 1, number = 1.0),
            chapter(id = 2, number = 2.0),
            chapter(id = 3, number = 3.0),
            chapter(id = 4, number = 4.5),
            chapter(id = 5, number = -1.0),
        )
        val updates = slot<List<ChapterUpdate>>()
        coEvery { updateChapter.awaitAll(capture(updates)) } returns Unit

        useCase(entry(progress = 3.0), target, setOf(ImportMigrateFlag.CHAPTER))

        updates.captured shouldHaveSize 3
        updates.captured.map { it.id } shouldBe listOf(1L, 2L, 3L)
        updates.captured.all { it.read == true }.shouldBeTrue()
    }

    @Test
    fun `chapters already read are left untouched`() = runTest {
        stubRemoteUpdateSuccess()
        coEvery { updateManga.await(any()) } returns true
        coEvery { getChaptersByMangaId.await(target.id) } returns listOf(
            chapter(id = 1, number = 1.0, read = true),
        )

        useCase(entry(progress = 10.0), target, setOf(ImportMigrateFlag.CHAPTER))

        coVerify(exactly = 0) { updateChapter.awaitAll(any()) }
    }

    @Test
    fun `progress is not carried when the flag is off`() = runTest {
        stubRemoteUpdateSuccess()
        coEvery { updateManga.await(any()) } returns true

        useCase(entry(progress = 10.0), target, emptySet())

        coVerify(exactly = 0) { getChaptersByMangaId.await(any()) }
    }

    @Test
    fun `the export's tracking row is attached when the tracker is logged in`() = runTest {
        stubRemoteUpdateSuccess()
        stubTracker(loggedIn = true)
        coEvery { updateManga.await(any()) } returns true
        coEvery { getTracks.await(target.id) } returns emptyList()
        val inserted = slot<Track>()
        coEvery { insertTrack.await(capture(inserted)) } returns Unit

        useCase(entry(links = listOf(mangabakaLink())), target, setOf(ImportMigrateFlag.TRACK))

        inserted.captured.mangaId shouldBe target.id
        inserted.captured.trackerId shouldBe TrackerId.MANGABAKA
        inserted.captured.remoteId shouldBe 101L
        inserted.captured.lastChapterRead shouldBe 12.0
        inserted.captured.totalChapters shouldBe 20L
        inserted.captured.status shouldBe MangaBaka.READING
        inserted.captured.remoteUrl shouldBe "https://mangabaka.org/101"
        inserted.captured.score shouldBe 85.0
        inserted.captured.private shouldBe true
        (inserted.captured.startDate > 0L).shouldBeTrue()
    }

    @Test
    fun `an already tracked entry is not bound twice`() = runTest {
        stubRemoteUpdateSuccess()
        stubTracker(loggedIn = true)
        coEvery { updateManga.await(any()) } returns true
        coEvery { getTracks.await(target.id) } returns listOf(
            Track(
                id = 1L,
                mangaId = target.id,
                trackerId = TrackerId.MANGABAKA,
                remoteId = 999L,
                libraryId = null,
                title = "Existing",
                lastChapterRead = 3.0,
                totalChapters = 20L,
                status = MangaBaka.READING,
                score = 0.0,
                remoteUrl = "",
                startDate = 0L,
                finishDate = 0L,
                private = false,
            ),
        )

        useCase(entry(links = listOf(mangabakaLink())), target, setOf(ImportMigrateFlag.TRACK))

        coVerify(exactly = 0) { insertTrack.await(any()) }
    }

    @Test
    fun `nothing is attached when the tracker is logged out`() = runTest {
        stubRemoteUpdateSuccess()
        stubTracker(loggedIn = false)
        coEvery { updateManga.await(any()) } returns true

        val result = useCase(entry(links = listOf(mangabakaLink())), target, setOf(ImportMigrateFlag.TRACK))

        result.shouldBeTrue()
        coVerify(exactly = 0) { insertTrack.await(any()) }
    }

    @Test
    fun `tracking is not attached when the flag is off`() = runTest {
        stubRemoteUpdateSuccess()
        stubTracker(loggedIn = true)
        coEvery { updateManga.await(any()) } returns true

        useCase(entry(links = listOf(mangabakaLink())), target, emptySet())

        coVerify(exactly = 0) { insertTrack.await(any()) }
    }

    @Test
    fun `an unknown state falls back to the tracker's first status`() = runTest {
        stubRemoteUpdateSuccess()
        stubTracker(loggedIn = true)
        coEvery { updateManga.await(any()) } returns true
        coEvery { getTracks.await(target.id) } returns emptyList()
        val inserted = slot<Track>()
        coEvery { insertTrack.await(capture(inserted)) } returns Unit

        useCase(
            entry(links = listOf(mangabakaLink(status = "some_new_state"))),
            target,
            setOf(ImportMigrateFlag.TRACK),
        )

        inserted.captured.status shouldBe MangaBaka.READING
    }

    @Test
    fun `a paused export state maps to the paused tracker id`() = runTest {
        stubRemoteUpdateSuccess()
        stubTracker(loggedIn = true)
        coEvery { updateManga.await(any()) } returns true
        coEvery { getTracks.await(target.id) } returns emptyList()
        val inserted = slot<Track>()
        coEvery { insertTrack.await(capture(inserted)) } returns Unit

        useCase(
            entry(links = listOf(mangabakaLink(status = "paused"))),
            target,
            setOf(ImportMigrateFlag.TRACK),
        )

        inserted.captured.status shouldBe MangaBaka.PAUSED
    }

    @Test
    fun `a completed export state maps to the completed tracker id`() = runTest {
        stubRemoteUpdateSuccess()
        stubTracker(loggedIn = true)
        coEvery { updateManga.await(any()) } returns true
        coEvery { getTracks.await(target.id) } returns emptyList()
        val inserted = slot<Track>()
        coEvery { insertTrack.await(capture(inserted)) } returns Unit

        useCase(
            entry(links = listOf(mangabakaLink(status = "completed"))),
            target,
            setOf(ImportMigrateFlag.TRACK),
        )

        inserted.captured.status shouldBe MangaBaka.COMPLETED
    }

    @Test
    fun `no tracking link means nothing to attach`() = runTest {
        stubRemoteUpdateSuccess()
        stubTracker(loggedIn = true)
        coEvery { updateManga.await(any()) } returns true

        useCase(entry(links = emptyList()), target, setOf(ImportMigrateFlag.TRACK))

        coVerify(exactly = 0) { insertTrack.await(any()) }
    }

    @Test
    fun `an empty chapter list does not fail the import`() = runTest {
        stubRemoteUpdateSuccess()
        coEvery { updateManga.await(any()) } returns true
        coEvery { getChaptersByMangaId.await(target.id) } returns emptyList()

        val result = useCase(entry(progress = 5.0), target, setOf(ImportMigrateFlag.CHAPTER))

        result.shouldBeTrue()
        coVerify(exactly = 1) { getChaptersByMangaId.await(target.id) }
        coVerify(exactly = 0) { updateChapter.awaitAll(any()) }
    }

    private fun chapter(
        id: Long,
        number: Double,
        read: Boolean = false,
    ): Chapter = Chapter.create().copy(
        id = id,
        mangaId = target.id,
        chapterNumber = number,
        read = read,
        sourceOrder = id,
    )

    private fun mangabakaLink(status: String? = "reading") = ImportTrackingLink(
        trackerId = TrackerId.MANGABAKA,
        remoteId = 101L,
        remoteUrl = "https://mangabaka.org/101",
        status = status,
        score = 85.0,
        progressChapter = 12.0,
        startDate = "2024-01-02",
        isPrivate = true,
    )
}
