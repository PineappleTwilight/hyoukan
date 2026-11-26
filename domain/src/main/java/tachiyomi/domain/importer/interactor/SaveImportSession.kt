package tachiyomi.domain.importer.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.importer.model.ImportSession
import tachiyomi.domain.importer.repository.ImportSessionRepository

@Inject
class SaveImportSession(
    private val repository: ImportSessionRepository,
) {
    suspend fun await(session: ImportSession) = repository.save(session)
}
