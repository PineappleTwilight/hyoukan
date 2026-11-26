package tachiyomi.domain.importer.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.importer.repository.ImportSessionRepository

/**
 * Records the outcome for one entry of an import session.
 *
 * Kept as read-modify-write in the repository rather than on a session object the UI holds, so a
 * mark that lands while the session is being observed cannot resurrect an older copy of it.
 */
@Inject
class MarkImportEntryStatus(
    private val repository: ImportSessionRepository,
) {
    suspend fun imported(sessionId: String, entryId: String) = repository.markImported(sessionId, entryId)

    suspend fun skipped(sessionId: String, entryId: String) = repository.markSkipped(sessionId, entryId)

    suspend fun pending(sessionId: String, entryId: String) = repository.markPending(sessionId, entryId)
}
