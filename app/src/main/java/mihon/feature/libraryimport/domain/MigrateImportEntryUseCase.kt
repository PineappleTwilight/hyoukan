package mihon.feature.libraryimport.domain

import dev.zacsweers.metro.Inject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.core.TrackerId
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.feature.libraryimport.model.ImportMigrateFlag
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.importer.model.ImportEntry
import tachiyomi.domain.importer.model.ImportTrackingLink
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns an import entry into a real library entry on [target]'s source.
 *
 * This is the second half of the "migrate to a source" flow the import screens start: the reader
 * picked a search result, and this adds it to the library with the parts of the export that still
 * mean something for a real entry - read progress, the note, and the tracking row.
 *
 * It is not [mihon.domain.migration.usecases.MigrateMangaUseCase] because there is no source entry
 * to migrate *from*: the import shell has no chapters, no categories and no cover of its own, so
 * most of that use case's work would be copying nothing. What it does share is the target side -
 * fetch the entry from its source so its chapters exist, then favorite it.
 */
@Inject
class MigrateImportEntryUseCase(
    private val updateMangaFromRemote: UpdateMangaFromRemote,
    private val updateManga: UpdateManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val updateChapter: UpdateChapter,
    private val getTracks: GetTracks,
    private val insertTrack: InsertTrack,
    private val trackerManager: TrackerManager,
) {
    /**
     * @return true when the entry is now in the library, false when the source could not be read
     * (the usual cause is a dead extension or a removed series).
     */
    suspend operator fun invoke(
        entry: ImportEntry,
        target: Manga,
        flags: Set<ImportMigrateFlag>,
    ): Boolean {
        return try {
            // A source that throws here has nothing to add - do not favorite a shell.
            updateMangaFromRemote(
                manga = target,
                fetchChapters = true,
            ).getOrThrow()

            updateManga.await(
                MangaUpdate(
                    id = target.id,
                    favorite = true,
                    // An entry that is already in the library keeps the date it was added; only a
                    // newly added one is stamped now, so importing does not reshuffle the library.
                    dateAdded = if (target.favorite) null else Instant.now().toEpochMilli(),
                    notes = entry.note.takeIf { ImportMigrateFlag.NOTES in flags && !it.isNullOrBlank() },
                ),
            )

            if (ImportMigrateFlag.CHAPTER in flags && entry.progressChapter > 0.0) {
                markProgressRead(target, entry.progressChapter)
            }

            if (ImportMigrateFlag.TRACK in flags) {
                attachTracking(entry, target)
            }

            true
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e) { "Could not import '${entry.title}'" }
            false
        }
    }

    /**
     * Marks every chapter up to the export's progress as read.
     *
     * Only chapters with a recognizable number are touched: a chapter the source numbers
     * differently from the exporting service cannot be matched to the export's count, and marking
     * it read would invent progress the reader never made.
     */
    private suspend fun markProgressRead(target: Manga, progress: Double) {
        val chapters = getChaptersByMangaId.await(target.id)
        val updates = chapters
            .filter { it.isRecognizedNumber && it.chapterNumber <= progress && !it.read }
            .map { it.copy(read = true).toChapterUpdate() }
        if (updates.isNotEmpty()) {
            updateChapter.awaitAll(updates)
        }
    }

    /**
     * Attaches the export's tracking row, so a library entry imported from MangaBaka comes back
     * tracked without the reader binding it by hand.
     *
     * Nothing is uploaded: the row is written locally with the remote id the export already knows,
     * and the next tracker refresh adopts the service's own values. That also means an entry the
     * reader already tracks is left alone - [getTracks] sees the existing row and nothing is
     * inserted, which is what keeps an established tracker binding from being overwritten by an
     * older export.
     */
    private suspend fun attachTracking(entry: ImportEntry, target: Manga) {
        val loggedInTrackers = trackerManager.trackers.filter { it.isLoggedIn }.map { it.id }.toSet()
        val links = entry.trackingLinks.filter { it.trackerId in loggedInTrackers }
        if (links.isEmpty()) return

        val existing = getTracks.await(target.id)

        links
            .filterNot { link -> existing.any { it.trackerId == link.trackerId } }
            .forEach { link ->
                insertTrack.await(link.toTrack(entry, target))
            }
    }

    private fun ImportTrackingLink.toTrack(entry: ImportEntry, target: Manga): Track {
        val tracker = trackerManager.trackers.firstOrNull { it.id == trackerId }
        return Track(
            id = 0L,
            mangaId = target.id,
            trackerId = trackerId,
            remoteId = remoteId,
            libraryId = null,
            title = entry.title,
            lastChapterRead = progressChapter,
            totalChapters = entry.totalChapters,
            status = tracker?.getStatusList()?.let { statuses -> resolveStatus(status, tracker, statuses) } ?: 0L,
            score = score ?: 0.0,
            remoteUrl = remoteUrl.orEmpty(),
            startDate = parseDate(startDate),
            finishDate = parseDate(finishDate),
            private = isPrivate,
        )
    }

    /**
     * Maps the export's state name onto the tracker's own status ids.
     *
     * The states the export can express are the exporting service's, not the tracker's, so each
     * name is resolved against the tracker's own ids: the reading/completed/rereading ones come
     * from the base tracker API, and the remaining MangaBaka states from that tracker's own
     * constants. An unknown name falls back to the list's first status, the one that claims no
     * progress.
     */
    private fun resolveStatus(state: String?, tracker: Tracker, statuses: List<Long>): Long {
        val normalized = state?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_')

        fun pick(id: Long?): Long? = id?.takeIf { it in statuses }

        val generic = when (normalized) {
            "reading", "currently_reading" -> pick(tracker.getReadingStatus())
            "completed", "complete", "finished" -> pick(tracker.getCompletionStatus())
            "rereading", "repeating" -> pick(tracker.getRereadingStatus())
            else -> null
        }
        if (generic != null) return generic

        if (tracker.id == TrackerId.MANGABAKA) {
            val mangabaka = when (normalized) {
                "paused", "on_hold", "onhold" -> pick(MangaBaka.PAUSED)
                "dropped" -> pick(MangaBaka.DROPPED)
                "plan_to_read", "planning", "planned" -> pick(MangaBaka.PLAN_TO_READ)
                "considering" -> pick(MangaBaka.CONSIDERING)
                else -> null
            }
            if (mangabaka != null) return mangabaka
        }

        return statuses.firstOrNull() ?: 0L
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            LocalDate.parse(value.substringBefore('T'))
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }
}
