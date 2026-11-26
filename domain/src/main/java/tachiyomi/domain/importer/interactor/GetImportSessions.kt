package tachiyomi.domain.importer.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.importer.model.ImportSession
import tachiyomi.domain.importer.repository.ImportSessionRepository

@Inject
class GetImportSessions(
    private val repository: ImportSessionRepository,
) {
    fun subscribe(): Flow<List<ImportSession>> = repository.sessions

    suspend fun await(): List<ImportSession> = repository.getAll()

    suspend fun await(id: String): ImportSession? = repository.get(id)
}
