package tachiyomi.domain.importer.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.importer.model.ImportSession

/**
 * Persistence for resumable library imports.
 *
 * Implementations are expected to keep the whole session list in one place (the app stores it as
 * a JSON file) and to serialize mutations, since a session is updated from the UI while it is also
 * being observed.
 */
interface ImportSessionRepository {
    val sessions: Flow<List<ImportSession>>

    suspend fun getAll(): List<ImportSession>

    suspend fun get(id: String): ImportSession?

    suspend fun save(session: ImportSession)

    suspend fun delete(id: String)

    /** Records [entryId] as migrated into the library. No-op when the session is gone. */
    suspend fun markImported(sessionId: String, entryId: String)

    /** Records [entryId] as deliberately skipped. No-op when the session is gone. */
    suspend fun markSkipped(sessionId: String, entryId: String)

    /** Returns [entryId] to the pending list. No-op when the session is gone. */
    suspend fun markPending(sessionId: String, entryId: String)
}
