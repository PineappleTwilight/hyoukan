package mihon.feature.libraryimport.data

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.importer.model.ImportSession
import tachiyomi.domain.importer.repository.ImportSessionRepository
import java.io.File

/**
 * File-backed store for resumable library imports.
 *
 * Sessions are small - a few hundred entries of a few hundred bytes each - and are read whole
 * whenever a screen opens, so one JSON file is a better fit than a database table: it needs no
 * schema migration, and the format is yours to read when something goes wrong. Writes go through a
 * temp file and a rename, so a process death mid-write cannot leave a half-written session list.
 *
 * Mutations are read-modify-write under a single [Mutex] because a session is updated by the
 * migration flow while the session screen is collecting it; the in-memory copy is the authority
 * between writes, and every write republishes it.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ImportSessionStore(
    private val context: Context,
    private val json: Json,
) : ImportSessionRepository {

    private val mutex = Mutex()

    /** `null` until the file has been read once. */
    private val cache = MutableStateFlow<List<ImportSession>?>(null)

    private val file: File
        get() = File(context.filesDir, "$DIRECTORY/$FILE_NAME").also { it.parentFile?.mkdirs() }

    override val sessions: Flow<List<ImportSession>> = cache
        .filterNotNull()
        // The store is lazy: nothing has read the file until someone asks. `onStart` makes the
        // first collector (the sessions screen) load it, so the screen shows the sessions on disk
        // before any write has happened this session.
        .onStart { getAll() }
        .map { list -> list.sortedByDescending { it.updatedAt } }

    override suspend fun getAll(): List<ImportSession> = mutex.withLock {
        loadLocked().sortedByDescending { it.updatedAt }
    }

    override suspend fun get(id: String): ImportSession? = mutex.withLock {
        loadLocked().firstOrNull { it.id == id }
    }

    override suspend fun save(session: ImportSession) = mutex.withLock {
        val current = loadLocked().toMutableList()
        val index = current.indexOfFirst { it.id == session.id }
        if (index >= 0) {
            current[index] = session
        } else {
            current += session
        }
        persistLocked(current)
    }

    override suspend fun delete(id: String) = mutex.withLock {
        val remaining = loadLocked().filterNot { it.id == id }
        persistLocked(remaining)
    }

    override suspend fun markImported(sessionId: String, entryId: String) = updateEntry(sessionId, entryId) { it.withImported(entryId) }

    override suspend fun markSkipped(sessionId: String, entryId: String) = updateEntry(sessionId, entryId) { it.withSkipped(entryId) }

    override suspend fun markPending(sessionId: String, entryId: String) = updateEntry(sessionId, entryId) { it.withPending(entryId) }

    private suspend fun updateEntry(
        sessionId: String,
        entryId: String,
        transform: (ImportSession) -> ImportSession,
    ) = mutex.withLock {
        val current = loadLocked()
        val session = current.firstOrNull { it.id == sessionId } ?: return@withLock
        // An id the session does not contain is a caller's mistake, not progress: recording it
        // would grow the done sets forever and could hide a real entry that later reuses the id.
        if (session.entries.none { it.id == entryId }) return@withLock
        val updated = transform(session)
        persistLocked(current.toMutableList().apply { replaceAll { if (it.id == sessionId) updated else it } })
    }

    private fun loadLocked(): List<ImportSession> {
        cache.value?.let { return it }

        val loaded = try {
            if (file.exists()) {
                json.decodeFromString<List<ImportSession>>(file.readText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            // A file we cannot read must not take the import UI down with it. Keep it on disk so
            // it can be inspected, and start from an empty list.
            logcat(LogPriority.ERROR, e) { "Could not read library import sessions" }
            emptyList()
        }
        cache.value = loaded
        return loaded
    }

    private fun persistLocked(list: List<ImportSession>) {
        cache.value = list
        try {
            val target = file
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(json.encodeToString(list))
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Could not persist library import sessions" }
        }
    }

    private companion object {
        const val DIRECTORY = "library_imports"
        const val FILE_NAME = "sessions.json"
    }
}
